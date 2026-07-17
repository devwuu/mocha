package com.devwuu.mocha.slack;

import com.devwuu.mocha.domain.NoteMeta;
import com.devwuu.mocha.domain.PendingNote;
import com.devwuu.mocha.domain.PhotoBuffer;
import com.devwuu.mocha.domain.Sourced;
import com.devwuu.mocha.image.ImageFormat;
import com.devwuu.mocha.llm.VisionExtraction;
import com.devwuu.mocha.llm.VisionHint;
import com.devwuu.mocha.llm.PhotoInfoExtractor;
import com.devwuu.mocha.repository.PendingStore;
import com.devwuu.mocha.repository.PhotoBufferStore;
import com.devwuu.mocha.repository.PhotoStore;
import com.devwuu.mocha.repository.StagedImage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 사진 수신 경로 전담 — 다운로드·포맷 입구 검증(ADR-29, V-12)·스테이징·버퍼 그룹핑(FR-10)·OCR
 * 오버레이(ADR-23, V-6)를 전담 소유한다(ADR-31, changes/0013). 라우터({@link AgentConversationRouter})가
 * 조립하는 내부 협력자라 Spring 빈이 아니다.
 * <p>이름에 Slack을 붙인 이유: HEIC → Slack 썸네일 대체(ADR-29)처럼 {@link IncomingPhoto}의 Slack 파일
 * 메타(mimetype·썸네일 URL)에 기대는 전송 계층 특화 정책을 품는다 — "URL → 바이트"만 아는 경계 인터페이스
 * {@link PhotoDownloader}(범용 추상)와 레벨을 구분한다.
 */
class SlackPhotoIntake {

    private static final Logger log = LoggerFactory.getLogger(SlackPhotoIntake.class);

    private final PendingStore pendingStore;
    private final SlackResponder responder;
    private final PhotoDownloader photoDownloader;
    private final PhotoStore photoStore;
    private final PhotoBufferStore photoBufferStore;
    private final PhotoInfoExtractor photoInfoExtractor;
    private final Duration bufferWindow;
    private final Clock clock;

    SlackPhotoIntake(
            PendingStore pendingStore,
            SlackResponder responder,
            PhotoDownloader photoDownloader,
            PhotoStore photoStore,
            PhotoBufferStore photoBufferStore,
            PhotoInfoExtractor photoInfoExtractor,
            Duration bufferWindow,
            Clock clock) {
        this.pendingStore = pendingStore;
        this.responder = responder;
        this.photoDownloader = photoDownloader;
        this.photoStore = photoStore;
        this.photoBufferStore = photoBufferStore;
        this.photoInfoExtractor = photoInfoExtractor;
        this.bufferWindow = bufferWindow;
        this.clock = clock;
    }

    /**
     * 사진 수신 → 버퍼 그룹핑(FR-10, AC-8) — 라우터 {@code onMedia} 위임의 실제 구현.
     * pending이 있으면 진행 중 노트에 첨부해 미리보기를 갱신하고, 없으면 버퍼에 담아 뒤이을 텍스트를 기다린다.
     */
    void receive(IncomingMedia media) {
        String userId = media.userId();
        String channelId = media.channelId();
        try {
            Optional<PendingNote> pending = pendingStore.get(userId);
            if (pending.isPresent()) {
                // 진행 중 노트가 있으면 사진은 스테이징에만 둔다 — 아카이브 전용이라 draft·미리보기를 건드리지
                // 않고(렌더 없음), [저장] 시 commitStaged가 photos/<slug>/<date>/로 옮긴다(changes/0014 ADR-32).
                List<String> staged = stageAll(userId, media);
                log.info("pending 중 사진 스테이징: user={} photos={}", userId, staged.size());
            } else {
                // 담을 노트가 아직 없다 → 버퍼에 쌓아 뒤이을 텍스트를 기다린다(FR-10).
                bufferMedia(userId, media);
            }
        } catch (Exception e) {
            // 다운로드/스테이징/전송 실패는 삼키지 않고 안내로 수렴한다(plan §7).
            log.warn("사진 수신 실패: user={}", userId, e);
            responder.post(channelId, FlowMessages.PHOTO_FAILED);
        }
    }

    /**
     * 텍스트보다 먼저 도착해 버퍼링된 사진의 흡수 판정(FR-10, AC-8) — 윈도우 안이면 스테이징 파일명을 돌려주고
     * (소비는 호출부가 pending 전송 성공 뒤 {@link #clearBuffer}로), 윈도우 밖이면 버려진 스테이징을 정리하고
     * 빈 Optional(새 흐름)로 수렴한다.
     */
    Optional<List<String>> absorbFreshBuffer(String userId, OffsetDateTime now) {
        Optional<PhotoBuffer> buffer = photoBufferStore.get(userId);
        if (buffer.isEmpty()) {
            return Optional.empty();
        }
        if (withinBufferWindow(buffer.get().lastMediaAt(), now)) {
            return Optional.of(buffer.get().stagedNames());
        }
        photoStore.discard(userId);
        photoBufferStore.clear(userId);
        return Optional.empty();
    }

    /**
     * [2.5] 수신 사진 OCR(ADR-23) — 흡수된 버퍼 사진이 있을 때만 1콜 시도한다. 없으면 빈 결과(호출 없음).
     * hint.coffeeName은 nullable — 사진-only 흐름은 커피명을 사진에서 읽는다.
     */
    VisionExtraction readPhotoInfo(String userId, List<String> bufferNames, VisionHint hint) {
        if (bufferNames.isEmpty()) {
            return VisionExtraction.empty();
        }
        List<StagedImage> images = photoStore.readStaged(userId);
        if (images.isEmpty()) {
            return VisionExtraction.empty();
        }
        VisionExtraction result = photoInfoExtractor.extract(images, hint);
        log.info("수신 사진 OCR 시도: user={} images={} coffeeName={}",
                userId, images.size(), result.coffeeName() != null);
        return result;
    }

    // OCR로 읽은 값을 사용자 draft 위에 오버레이한다 — 빈 필드만 source=photo로 채우고 사용자 값은 불가침(V-6).
    // POLICY: 우선순위 user > photo — 사진 OCR은 source=user 필드를 덮지 않는다(coffee_name 포함) (ADR-23, V-6).
    static NoteMeta overlayPhotoInfo(NoteMeta user, VisionExtraction photo) {
        if (photo == null) {
            return user;
        }
        return new NoteMeta(
                fillPhoto(user.coffeeName(), photo.coffeeName()),
                fillPhoto(user.roastery(), photo.roastery()),
                fillPhoto(user.origin(), photo.origin()),
                fillPhoto(user.process(), photo.process()),
                fillPhoto(user.roastLevel(), photo.roastLevel()),
                fillPhotoList(user.officialNotes(), photo.officialNotes()),
                user.sources());
    }

    /** 스테이징 원본을 photos/&lt;slug&gt;/&lt;date&gt;/로 이동해 확정하고 상대 경로 목록을 돌려준다(V-4, FR-10). */
    List<String> commitStaged(String userId, String slug, String date) {
        return photoStore.commit(userId, slug, date);
    }

    /** 수정 세션 날짜 이동 시 그 엔트리의 아카이브 폴더를 새 날짜로 동반 이동한다(ADR-32, FR-21) — best-effort는 호출부가 감싼다. */
    void moveEntryPhotos(String slug, String fromDate, String toDate) {
        photoStore.moveEntryPhotos(slug, fromDate, toDate);
    }

    /** 대기 중이던 스테이징 사진·버퍼를 함께 폐기한다 — 취소·pending 만료 경로(FR-10). */
    void discard(String userId) {
        photoStore.discard(userId);
        photoBufferStore.clear(userId);
    }

    /** 버퍼만 비운다 — 사진이 pending·노트로 이관된 뒤(스테이징 원본은 commit이 옮긴다). */
    void clearBuffer(String userId) {
        photoBufferStore.clear(userId);
    }

    // 수신 사진을 내려받아 매직바이트로 포맷을 검증한 뒤 스테이징에 저장하고 스테이징된 파일명을 순서대로 돌려준다.
    // POLICY: 스테이징에는 vision 지원 포맷(JPEG/PNG/GIF/WebP)만 — 매직바이트 검증 전 저장 금지(ADR-29, V-12).
    // HEIC는 Slack 썸네일(실측 PNG)로 대체하고(AC-45), 그 외 미지원·썸네일 부재는 그 사진만 버리고 안내한다
    // (조용히 버리지 않는다). 같은 배치의 정상 사진 처리는 불변(AC-46).
    private List<String> stageAll(String userId, IncomingMedia media) {
        List<String> names = new ArrayList<>();
        boolean rejected = false;
        for (IncomingPhoto photo : media.photos()) {
            StageableImage stageable = resolveStageable(userId, photo);
            if (stageable == null) {
                rejected = true;
                continue;
            }
            names.add(photoStore.stage(userId, stageable.filename(), stageable.bytes()));
        }
        if (rejected) {
            responder.post(media.channelId(), FlowMessages.UNSUPPORTED_FORMAT);
        }
        return names;
    }

    // 사진 1건을 스테이징 가능한 (파일명, 바이트)로 해석한다 — 미지원·대체 실패는 null(그 사진만 거부).
    // vision 지원 원본은 그대로, HEIC는 Slack 썸네일로 대체, 그 외 미지원은 거부한다(ADR-29, V-12).
    private StageableImage resolveStageable(String userId, IncomingPhoto photo) {
        byte[] bytes = photoDownloader.download(photo.urlPrivate());
        ImageFormat format = ImageFormat.detect(bytes);
        if (format.isVisionSupported()) {
            // 일반 JPEG/PNG 등 — 원본 그대로(대체 미발동, AC-45 후단).
            return new StageableImage(photo.filename(), bytes);
        }
        // 미지원 포맷. HEIC(매직바이트 확정 + 메타 mimetype 참고)면 Slack 썸네일로 대체 다운로드한다(AC-45).
        if (isHeic(format, photo)) {
            StageableImage thumbnail = downloadVisionThumbnail(userId, photo);
            if (thumbnail != null) {
                return thumbnail;
            }
        }
        // 대체 불가(HEIC 아님 / 썸네일 부재·실패) — poison이 스테이징에 못 들어가게 입구에서 차단한다(delta #2·#3).
        log.info("미지원 포맷 사진 거부(스테이징 제외): user={} filename={} format={}",
                userId, photo.filename(), format);
        return null;
    }

    // HEIC 판별 — 매직바이트가 1차(확정), Slack 메타 mimetype은 매직바이트가 못 잡은 HEIF 변형을 보강하는 힌트(ADR-29).
    private static boolean isHeic(ImageFormat format, IncomingPhoto photo) {
        if (format == ImageFormat.HEIC) {
            return true;
        }
        String mimetype = photo.mimetype();
        return mimetype != null && mimetype.toLowerCase(Locale.ROOT).startsWith("image/hei"); // image/heic·image/heif
    }

    // HEIC 원본 대신 vision 지원 포맷 썸네일을 최대 해상도부터 내려받아 매직바이트로 재검증한다(실측: PNG — findings-TΔ0).
    // 썸네일 부재·다운로드 실패·미지원 포맷이면 null → 상위가 거부 경로로 수렴한다(AC-45 후단).
    private StageableImage downloadVisionThumbnail(String userId, IncomingPhoto photo) {
        for (String url : photo.thumbnailUrls()) {
            byte[] bytes;
            try {
                bytes = photoDownloader.download(url);
            } catch (RuntimeException e) {
                log.warn("HEIC 썸네일 다운로드 실패 — 다음 후보 시도: user={} filename={}", userId, photo.filename(), e);
                continue;
            }
            ImageFormat format = ImageFormat.detect(bytes);
            if (!format.isVisionSupported()) {
                continue;
            }
            // 확장자는 실측 포맷 기반 — ".jpg" 하드코딩 금지, 원본 HEIC 확장자를 실제 포맷(PNG→.png)으로 교체(findings-TΔ0 §3).
            String filename = withExtension(photo.filename(), format.extension());
            log.info("HEIC 사진 → Slack 썸네일 대체: user={} filename={} format={} bytes={}",
                    userId, filename, format, bytes.length);
            return new StageableImage(filename, bytes);
        }
        return null;
    }

    // 파일명의 확장자를 실측 포맷 확장자로 교체한다(점 없는 확장자 입력). 확장자가 없으면 붙인다.
    private static String withExtension(String filename, String extension) {
        String base = filename == null || filename.isBlank() ? "photo" : filename;
        int dot = base.lastIndexOf('.');
        if (dot > 0) {
            base = base.substring(0, dot);
        }
        return base + "." + extension;
    }

    // 스테이징 가능한 사진 1건 — 대체 다운로드 시 파일명(확장자)이 원본과 달라질 수 있어 바이트와 함께 실어 나른다.
    private record StageableImage(String filename, byte[] bytes) {
    }

    // pending 없음: 윈도우 밖 이전 버퍼는 버리고 새로 시작, 안이면 이어붙여 버퍼링한다(AC-8).
    private void bufferMedia(String userId, IncomingMedia media) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<String> priorNames = List.of();
        Optional<PhotoBuffer> existing = photoBufferStore.get(userId);
        if (existing.isPresent()) {
            if (withinBufferWindow(existing.get().lastMediaAt(), now)) {
                priorNames = existing.get().stagedNames();
            } else {
                // 윈도우 밖 = 이전 버퍼는 버려진 것 → 새 흐름. 새 사진을 담기 전에 옛 스테이징을 비운다(AC-8 후반).
                photoStore.discard(userId);
                photoBufferStore.clear(userId);
            }
        }
        List<String> names = new ArrayList<>(priorNames);
        names.addAll(stageAll(userId, media));
        photoBufferStore.put(userId, new PhotoBuffer(now, names));
        log.info("사진 버퍼링: user={} photos={}", userId, names.size());
    }

    private boolean withinBufferWindow(OffsetDateTime lastMediaAt, OffsetDateTime now) {
        return Duration.between(lastMediaAt, now).compareTo(bufferWindow) <= 0;
    }

    private static Sourced<String> fillPhoto(Sourced<String> current, String photoValue) {
        if (current != null && current.value() != null && !current.value().isBlank()) {
            return current; // 사용자 값 불가침(V-6)
        }
        if (photoValue == null || photoValue.isBlank()) {
            return current; // 사진에서 못 읽음 — 원래 상태(보통 null) 유지, 검색 보강 대상으로 넘김
        }
        return Sourced.photo(photoValue);
    }

    private static Sourced<List<String>> fillPhotoList(Sourced<List<String>> current, List<String> photoValue) {
        if (current != null && current.value() != null && !current.value().isEmpty()) {
            return current;
        }
        if (photoValue == null || photoValue.isEmpty()) {
            return current;
        }
        return Sourced.photo(List.copyOf(photoValue));
    }
}
