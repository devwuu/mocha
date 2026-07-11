package com.devwuu.mocha.slack;

import com.devwuu.mocha.domain.Entry;
import com.devwuu.mocha.domain.MatchInfo;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.NoteMeta;
import com.devwuu.mocha.domain.PendingNote;
import com.devwuu.mocha.domain.PhotoBuffer;
import com.devwuu.mocha.domain.Sourced;
import com.devwuu.mocha.pipeline.ExtractionResult;
import com.devwuu.mocha.pipeline.IntentClassifier;
import com.devwuu.mocha.pipeline.MatchResult;
import com.devwuu.mocha.pipeline.MessageIntent;
import com.devwuu.mocha.pipeline.NoteCandidate;
import com.devwuu.mocha.pipeline.NoteEnricher;
import com.devwuu.mocha.pipeline.NoteExtractor;
import com.devwuu.mocha.pipeline.NoteMatcher;
import com.devwuu.mocha.pipeline.PendingReviser;
import com.devwuu.mocha.render.NoteRenderer;
import com.devwuu.mocha.repository.NoteRepository;
import com.devwuu.mocha.repository.PendingStore;
import com.devwuu.mocha.repository.PhotoBufferStore;
import com.devwuu.mocha.repository.PhotoStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 확인 상태 머신의 오케스트레이터 — {@link ConversationRouter}가 정한 분기의 실제 파이프라인 일을 맡는다
 * (ref: plan.md §1 [2]~[7], ADR-3). T3-2 임시 스텁(LoggingConfirmationFlow)을 대체한다.
 * <ul>
 *   <li>{@link #startNewNote} — 입구 의도 게이트([1.5], ADR-18)로 기록 요청만 들인 뒤 신규 파이프라인 배선:
 *       후보 조회 → {@link NoteExtractor 추출} →
 *       {@link NoteMatcher 매칭} → draft 조립(source=user 마킹) → {@link NoteEnricher 보강} → slug 확정 →
 *       {@link PendingStore#put} → {@link PreviewMessenger#publish} → preview_ts 반영 재저장. (tasks T3-6)</li>
 *   <li>{@link #confirmSave} — pending 로드·TTL 판정(V-7) → {@link NoteRepository#upsertEntry}로 커밋 →
 *       pending clear → {@link NoteRenderer#renderEntryCard 방금 엔트리 카드 증분 렌더} →
 *       {@link SlackResponder#postImage 카드 JPG 배달}(실패 시 텍스트 폴백, AC-18). (tasks T3-5 / changes/0002 TΔ5)</li>
 *   <li>{@link #revisePending} — pending 수정 반영 배선: {@link PendingReviser#revise}로 draft에 수정 병합 →
 *       {@link PendingStore#put} → {@link PreviewMessenger#publish}로 같은 미리보기 메시지 edit(preview_ts 보존).
 *       엔트리 개수는 불변이다(AC-5). (tasks T3-7)</li>
 *   <li>{@link #cancel} — pending 폐기 + 취소 안내. 저장은 일어나지 않는다(AC-4).</li>
 *   <li>{@link #receiveMedia} — 사진 수신 버퍼 그룹핑: pending 있으면 첨부, 없으면 버퍼링. 윈도우 밖은
 *       새 흐름(FR-10, AC-8). [저장] 시 {@link PhotoStore#commit}으로 스테이징을 노트 트리로 확정한다. (tasks T4-2)</li>
 * </ul>
 * <p>POLICY: 사용자 [저장] 확인 없이 {@link NoteRepository} 쓰기를 호출하지 않는다 (ref: plan.md#ADR-3, AC-4).
 * 신규 파이프라인은 미리보기 단계까지만 진행하며 {@link PendingStore}에만 기록한다 — 노트 JSON은 손대지 않는다.
 */
@Component
public class DefaultConfirmationFlow implements ConfirmationFlow {

    private static final Logger log = LoggerFactory.getLogger(DefaultConfirmationFlow.class);

    // 날짜/타임스탬프는 Asia/Seoul 기준 — NoteRepository·PendingStore와 동일(V-3).
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    // --- 멘트(모카 톤) 상수 — 구현 디테일, spec 결정 아님. PreviewBlocks와 같은 강아지 말투("멍" + 🐾). ---
    static final String SAVE_DONE_CAPTION = "저장했어요 멍! 🐾"; // 배달하는 카드 JPG의 캡션(AC-Δ1)
    static final String SAVE_DONE_NO_IMAGE = "저장했어요 멍! 카드 이미지는 잠시 뒤에 다시 만들어 볼게요 🐾"; // 카드 렌더/전송 실패 폴백(AC-18)
    static final String NOTHING_TO_SAVE = "저장할 기록을 못 찾았어요 멍… 만료됐거나 이미 처리됐나 봐요 🐾"; // 만료/부재 안내(V-7)
    static final String CANCELED = "이번 기록은 지웠어요 멍! 다음에 또 마시면 불러주세요 🐾"; // 취소 안내
    static final String BROKEN_PENDING = "기록이 뭔가 이상해요 멍… 다시 보내주시겠어요? 🐾"; // 방어(엔트리/슬러그 결손)
    static final String NEW_NOTE_FAILED = "기록을 정리하다 문제가 생겼어요 멍… 잠시 뒤 다시 보내주시겠어요? 🐾"; // 추출/검색/전송 실패(plan §7)
    static final String REVISE_FAILED = "수정을 반영하다 문제가 생겼어요 멍… 다시 말씀해 주시겠어요? 🐾"; // 수정 병합/전송 실패(plan §7). 기존 pending은 보존
    static final String PHOTO_FAILED = "사진을 받다 문제가 생겼어요 멍… 다시 올려주시겠어요? 🐾"; // 다운로드/스테이징/전송 실패(plan §7)
    static final String NOT_A_RECORD = "저는 커피 감상을 기록하는 강아지예요 멍! 마신 커피 이야기를 들려주세요 🐾"; // 의도 게이트 other 판정 안내(AC-Δ3)

    private final PendingStore pendingStore;
    private final NoteRepository noteRepository;
    private final NoteRenderer noteRenderer;
    private final SlackResponder responder;
    private final IntentClassifier intentClassifier;
    private final NoteExtractor noteExtractor;
    private final NoteMatcher noteMatcher;
    private final NoteEnricher noteEnricher;
    private final PendingReviser pendingReviser;
    private final PreviewMessenger previewMessenger;
    private final PhotoDownloader photoDownloader;
    private final PhotoStore photoStore;
    private final PhotoBufferStore photoBufferStore;
    private final Duration bufferWindow;
    private final Clock clock;

    @Autowired
    public DefaultConfirmationFlow(
            PendingStore pendingStore,
            NoteRepository noteRepository,
            NoteRenderer noteRenderer,
            SlackResponder responder,
            IntentClassifier intentClassifier,
            NoteExtractor noteExtractor,
            NoteMatcher noteMatcher,
            NoteEnricher noteEnricher,
            PendingReviser pendingReviser,
            PreviewMessenger previewMessenger,
            PhotoDownloader photoDownloader,
            PhotoStore photoStore,
            PhotoBufferStore photoBufferStore,
            @Value("${mocha.photo.buffer-window}") Duration bufferWindow) {
        this(pendingStore, noteRepository, noteRenderer, responder, intentClassifier,
                noteExtractor, noteMatcher, noteEnricher, pendingReviser, previewMessenger,
                photoDownloader, photoStore, photoBufferStore, bufferWindow, Clock.system(SEOUL));
    }

    // 테스트에서 시간을 고정하기 위한 생성자(NoteRepository·PendingStore와 동일 패턴).
    DefaultConfirmationFlow(
            PendingStore pendingStore,
            NoteRepository noteRepository,
            NoteRenderer noteRenderer,
            SlackResponder responder,
            IntentClassifier intentClassifier,
            NoteExtractor noteExtractor,
            NoteMatcher noteMatcher,
            NoteEnricher noteEnricher,
            PendingReviser pendingReviser,
            PreviewMessenger previewMessenger,
            PhotoDownloader photoDownloader,
            PhotoStore photoStore,
            PhotoBufferStore photoBufferStore,
            Duration bufferWindow,
            Clock clock) {
        this.pendingStore = pendingStore;
        this.noteRepository = noteRepository;
        this.noteRenderer = noteRenderer;
        this.responder = responder;
        this.intentClassifier = intentClassifier;
        this.noteExtractor = noteExtractor;
        this.noteMatcher = noteMatcher;
        this.noteEnricher = noteEnricher;
        this.pendingReviser = pendingReviser;
        this.previewMessenger = previewMessenger;
        this.photoDownloader = photoDownloader;
        this.photoStore = photoStore;
        this.photoBufferStore = photoBufferStore;
        this.bufferWindow = bufferWindow;
        this.clock = clock;
    }

    @Override
    public void startNewNote(IncomingMessage message) {
        String userId = message.userId();
        String channelId = message.channelId();

        // [1.5] 입구 의도 게이트 — 파이프라인 진입 전(버퍼 처리보다도 앞)에서 기록 요청만 들인다.
        // POLICY: 입구 의도 게이트는 진입 분기만 — 저장/취소 커밋은 버튼(action_id)만(ADR-3 불변).
        //         게이트 실패·애매함은 record로 진행(fail-open, 기록 유실 방지) (ref: plan.md#ADR-18, spec FR-17).
        if (!isRecordRequest(message.text(), userId)) {
            // other 판정 — 추출·보강·pending·미리보기 없이 짧은 안내로 종료한다. 버퍼는 건드리지 않는다(AC-Δ3).
            log.info("입구 의도 게이트 other — 파이프라인 미진입: user={}", userId);
            responder.post(channelId, NOT_A_RECORD);
            return;
        }

        try {
            LocalDate today = LocalDate.now(clock);
            OffsetDateTime now = OffsetDateTime.now(clock);
            List<Note> existingNotes = noteRepository.findAll();

            // FR-10 버퍼 그룹핑: 텍스트보다 먼저 도착해 버퍼링된 사진이 윈도우 안이면 이 노트로 흡수한다.
            // 소비(clear)는 pending 전송이 성공한 뒤에만 — 실패 시 사진이 버퍼에 남아 재시도로 살아남게 한다.
            // 윈도우 밖 버퍼는 이 텍스트에 묶이지 않는다: 버려진 스테이징을 정리하고 새 흐름으로 진행한다(AC-8 후반).
            List<String> bufferNames = List.of();
            boolean consumeBuffer = false;
            Optional<PhotoBuffer> buffer = photoBufferStore.get(userId);
            if (buffer.isPresent()) {
                if (withinBufferWindow(buffer.get().lastMediaAt(), now)) {
                    bufferNames = buffer.get().stagedNames();
                    consumeBuffer = true;
                } else {
                    photoStore.discard(userId);
                    photoBufferStore.clear(userId);
                }
            }

            // [2] 추출 → [3] 매칭. LLM/스키마 실패는 여기서 예외로 던져져 아래 catch로 수렴한다(plan §7, V-1).
            ExtractionResult extraction =
                    noteExtractor.extract(message.text(), today, candidatesOf(existingNotes));
            MatchResult match = noteMatcher.match(extraction, existingNotes);

            // draft 조립(source=user 마킹) → [4] 보강(빈 필드만 source=search, V-6). 외부 호출은 여기서 끝낸다(CLAUDE.md §3).
            NoteMeta enriched = noteEnricher.enrich(userDraftMeta(extraction));

            // slug 확정: 기존=매칭 노트 slug, 신규=최초 기록일 기반 대체키(V-2, data-model §2.1).
            String slug = match.isNew()
                    ? noteRepository.nextAvailableSlug(match.targetDate().toString())
                    : match.matchedNote().slug();

            // 흡수한 버퍼 사진의 임시 미리보기 경로. 실제 저장 경로는 [저장] 시 commit이 확정한다(V-4).
            List<String> photoPaths = provisionalPhotoPaths(slug, match.targetDate(), bufferNames);
            Entry entry = new Entry(match.targetDate(), extraction.myTaste(), extraction.rating(), photoPaths, now);
            Note draft = assembleDraft(slug, extraction.coffeeName(), enriched, entry, now);
            MatchInfo matchInfo = match.toMatchInfo();

            // POLICY: 노트 JSON은 건드리지 않는다 — 미리보기 단계는 pending에만 기록한다(AC-4).
            // put을 먼저 해 재시작 생존(NFR-2)을 확보하고, 전송 후 확정된 preview_ts로 재저장한다.
            pendingStore.put(userId, new PendingNote(draft, matchInfo, null, now));
            try {
                String previewTs = previewMessenger.publish(channelId, new PendingNote(draft, matchInfo, null, now));
                pendingStore.put(userId, new PendingNote(draft, matchInfo, previewTs, now));
            } catch (Exception publishFailure) {
                // 전송 실패 시 절반만 만들어진 pending을 남기지 않는다 — "미리보기 없으면 pending 없음" 불변 유지.
                pendingStore.clear(userId);
                throw publishFailure;
            }
            // 사진은 pending으로 이관됐다 — 버퍼는 비운다(스테이징 원본은 [저장] 시 commit이 옮긴다).
            if (consumeBuffer) {
                photoBufferStore.clear(userId);
            }
            log.info("신규 파이프라인 미리보기 전송: user={} slug={} match={} photos={}",
                    userId, slug, matchInfo.type(), bufferNames.size());
        } catch (Exception e) {
            // 추출·검색·전송 등 어느 단계 실패든 오류 응답으로 수렴하고 pending은 남기지 않는다(plan §7).
            log.warn("신규 파이프라인 실패(pending 미생성): user={}", userId, e);
            responder.post(channelId, NEW_NOTE_FAILED);
        }
    }

    // [1.5] 입구 의도 게이트 판정 — record면 파이프라인 진입, other면 미진입(안내). 게이트 실패는 fail-open(record)으로 흡수한다.
    private boolean isRecordRequest(String text, String userId) {
        try {
            MessageIntent intent = intentClassifier.classify(text).intent();
            // 관측(plan §6): 판정 분포(record/other)를 로깅해 오분류 프록시로 삼는다.
            log.info("입구 의도 게이트 판정: user={} intent={}", userId, intent.value());
            return intent == MessageIntent.RECORD;
        } catch (Exception e) {
            // fail-open: 게이트 호출/스키마 실패 시 record로 간주해 진행한다 — 기록 유실 방지(AC-Δ4, plan §7).
            log.warn("입구 의도 게이트 실패 — record로 진행(fail-open): user={}", userId, e);
            return true;
        }
    }

    // 기존 노트를 추출 요청의 매칭 후보(최소 식별 정보)로 축약 (ref: data-model.md#3 existing_notes).
    private static List<NoteCandidate> candidatesOf(List<Note> existingNotes) {
        return existingNotes.stream()
                .map(note -> new NoteCandidate(
                        note.slug(), note.coffeeName(),
                        note.roastery() == null ? null : note.roastery().value()))
                .toList();
    }

    // 추출 결과를 "사용자가 말한 것"만 담은 NoteMeta로 조립 — 언급한 필드만 source=user, 나머지는 null(보강 대상).
    private static NoteMeta userDraftMeta(ExtractionResult extraction) {
        return new NoteMeta(
                extraction.coffeeName(),
                userSourced(extraction.roastery()),
                userSourced(extraction.origin()),
                userSourced(extraction.process()),
                userSourced(extraction.roastLevel()),
                null,        // official_notes는 사용자 언급 없음 전제 — 보강(로스터리 출처 한정)이 채운다(FR-3)
                List.of());  // sources는 보강이 병합한다
    }

    // 보강 완료된 메타 + 이번 시음 엔트리 1건으로 확인용 draft Note를 만든다.
    private static Note assembleDraft(String slug, String coffeeName, NoteMeta meta, Entry entry, OffsetDateTime now) {
        return new Note(
                slug,
                coffeeName,
                meta.roastery(),
                meta.origin(),
                meta.process(),
                meta.roastLevel(),
                meta.officialNotes(),
                meta.sources(),
                List.of(entry),
                now,
                now);
    }

    private static Sourced<String> userSourced(String value) {
        return value == null ? null : Sourced.user(value);
    }

    @Override
    public void revisePending(IncomingMessage message, PendingNote pending) {
        String userId = message.userId();
        String channelId = message.channelId();
        try {
            // [1] 수정 분기: 수정 텍스트를 LLM 패치로 받아 기존 draft에 병합한다 — 엔트리 개수 불변, 새 노트 미생성(AC-5).
            // match·preview_ts·created_at은 PendingReviser가 보존하므로 같은 미리보기 메시지를 edit로 갱신하게 된다.
            PendingNote revised = pendingReviser.revise(pending, message.text());

            // 갱신본을 먼저 영속화(재시작 생존, NFR-2) → preview_ts가 살아 있어 publish는 재전송이 아닌 edit로 갱신한다.
            pendingStore.put(userId, revised);
            previewMessenger.publish(channelId, revised);
            log.info("pending 수정 반영: user={} slug={}", userId, revised.draft().slug());
        } catch (Exception e) {
            // 수정 병합·전송 실패 — 신규와 달리 기존 pending은 폐기하지 않는다(이전 미리보기가 여전히 유효). 오류만 안내한다(plan §7).
            log.warn("pending 수정 실패(기존 pending 보존): user={}", userId, e);
            responder.post(channelId, REVISE_FAILED);
        }
    }

    @Override
    public void confirmSave(IncomingAction action) {
        String userId = action.userId();
        // V-7: pending 부재/TTL 초과면 저장하지 않고 만료 안내 — get()이 만료분을 빈 Optional로 준다.
        Optional<PendingNote> pendingOpt = pendingStore.get(userId);
        if (pendingOpt.isEmpty()) {
            log.info("[저장] 무효 — pending 부재/만료: user={}", userId);
            // 만료/부재면 대기 중이던 스테이징 사진도 버려진 것 — 노트 트리로 새지 않게 정리한다(FR-10).
            photoStore.discard(userId);
            photoBufferStore.clear(userId);
            responder.post(action.channelId(), NOTHING_TO_SAVE);
            return;
        }

        Note draft = pendingOpt.get().draft();
        String slug = draft.slug();
        Entry entry = latestEntry(draft);
        // 커밋 대상은 draft.slug()(신규는 상위 파이프라인이 할당, 기존은 매칭 slug). 결손이면 저장하지 않는다.
        if (slug == null || slug.isBlank() || entry == null) {
            log.warn("[저장] 무효 — 손상된 pending(slug/entry 결손): user={} slug={}", userId, slug);
            responder.post(action.channelId(), BROKEN_PENDING);
            return;
        }

        // 사진 커밋: 스테이징 원본을 photos/<slug>/<date>/로 이동하고 상대 경로를 확정한다(V-4, FR-10).
        // 로컬 move라 저장 커밋 경계 안에서 수행한다(외부 I/O는 수신 시점에 이미 끝남, CLAUDE.md §3).
        String date = entry.date().toString();
        List<String> committedPhotos = photoStore.commit(userId, slug, date);
        Entry committedEntry = new Entry(
                entry.date(), entry.myTaste(), entry.rating(), committedPhotos, entry.updatedAt());

        // POLICY: 사용자 [저장] 확인을 거친 뒤에만 저장한다 (ref: plan.md#ADR-3, AC-4).
        Note saved = noteRepository.upsertEntry(slug, metaOf(draft), committedEntry);
        pendingStore.clear(userId);
        photoBufferStore.clear(userId);
        log.info("[저장] 커밋 완료: slug={} entries={} photos={}",
                saved.slug(), saved.entries().size(), committedPhotos.size());

        // 저장은 이미 커밋됨 — 카드 렌더·전송 실패는 데이터 손실이 아니다. 실패해도 저장은 유지하고 안내 텍스트로 폴백한다.
        // POLICY: 저장 시점 렌더는 증분 — 방금 그 (slug,date) 엔트리 카드 1장만 굽는다(전체 재래스터화는 --rerender)
        //         (ref: plan.md#ADR-10, AC-Δ7).
        // POLICY: 카드 이미지 생성·전송 실패는 저장을 되돌리지 않는다 — 안내 텍스트로 폴백 (ref: plan.md §7, AC-18).
        try {
            Path card = noteRenderer.renderEntryCard(slug, committedEntry.date());
            responder.postImage(action.channelId(), card, SAVE_DONE_CAPTION);
        } catch (RuntimeException e) {
            log.warn("카드 배달 실패(노트는 저장됨, --rerender로 복구 가능): slug={} date={}", saved.slug(), date, e);
            responder.post(action.channelId(), SAVE_DONE_NO_IMAGE);
        }
    }

    @Override
    public void cancel(IncomingAction action) {
        // [취소]는 저장 없이 pending만 폐기한다(AC-4). 대기 중이던 스테이징 사진·버퍼도 함께 정리한다(FR-10).
        pendingStore.clear(action.userId());
        photoStore.discard(action.userId());
        photoBufferStore.clear(action.userId());
        log.info("[취소] pending 폐기: user={}", action.userId());
        responder.post(action.channelId(), CANCELED);
    }

    @Override
    public void receiveMedia(IncomingMedia media) {
        String userId = media.userId();
        String channelId = media.channelId();
        try {
            Optional<PendingNote> pending = pendingStore.get(userId);
            if (pending.isPresent()) {
                // 진행 중 노트가 있으면 사진은 그 노트에 첨부한다 — pending 중 텍스트=수정(ADR-3)과 같은 정신.
                attachToPending(userId, channelId, pending.get(), stageAll(userId, media));
            } else {
                // 담을 노트가 아직 없다 → 버퍼에 쌓아 뒤이을 텍스트를 기다린다(FR-10).
                bufferMedia(userId, media);
            }
        } catch (Exception e) {
            // 다운로드/스테이징/전송 실패는 삼키지 않고 안내로 수렴한다(plan §7).
            log.warn("사진 수신 실패: user={}", userId, e);
            responder.post(channelId, PHOTO_FAILED);
        }
    }

    // 수신 사진을 내려받아 사용자 스테이징에 저장하고 스테이징된 파일명을 순서대로 돌려준다.
    private List<String> stageAll(String userId, IncomingMedia media) {
        List<String> names = new ArrayList<>();
        for (IncomingPhoto photo : media.photos()) {
            byte[] bytes = photoDownloader.download(photo.urlPrivate());
            names.add(photoStore.stage(userId, photo.filename(), bytes));
        }
        return names;
    }

    // 진행 중 pending의 이번 시음 엔트리에 사진을 덧붙이고 미리보기를 갱신한다(preview_ts 보존 → edit).
    private void attachToPending(String userId, String channelId, PendingNote pending, List<String> newNames)
            throws Exception {
        Note draft = pending.draft();
        Entry entry = latestEntry(draft);
        if (entry == null) {
            // 방어: 엔트리 없는 pending에는 첨부할 자리가 없다 — 버퍼로 흘리지 않고 그냥 로그만.
            log.warn("사진 첨부 무효 — 엔트리 없는 pending: user={}", userId);
            return;
        }
        List<String> merged = new ArrayList<>(entry.photos());
        merged.addAll(provisionalPhotoPaths(draft.slug(), entry.date(), newNames));
        Entry withPhotos = new Entry(entry.date(), entry.myTaste(), entry.rating(), merged, entry.updatedAt());
        PendingNote updated = new PendingNote(
                withLatestEntry(draft, withPhotos), pending.match(), pending.previewTs(), pending.createdAt());

        pendingStore.put(userId, updated);
        previewMessenger.publish(channelId, updated); // preview_ts 있음 → 같은 미리보기 edit
        log.info("pending 사진 첨부: user={} slug={} photos={}", userId, draft.slug(), merged.size());
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

    // 스테이징 파일명을 확정 저장 경로 규칙(photos/<slug>/<date>/<name>)에 맞춘 임시 미리보기 경로로 변환한다.
    // 실제 파일 이동·최종 경로는 [저장] 시 PhotoStore.commit이 정한다(충돌 시 -N 접미 가능).
    private static List<String> provisionalPhotoPaths(String slug, LocalDate date, List<String> names) {
        if (names.isEmpty()) {
            return List.of();
        }
        String prefix = "photos/" + slug + "/" + date + "/";
        return names.stream().map(name -> prefix + name).toList();
    }

    // draft의 이번 시음 엔트리(마지막 1건)를 교체한 새 draft를 만든다.
    private static Note withLatestEntry(Note draft, Entry entry) {
        List<Entry> entries = new ArrayList<>(draft.entries());
        entries.set(entries.size() - 1, entry);
        return new Note(
                draft.slug(), draft.coffeeName(), draft.roastery(), draft.origin(), draft.process(),
                draft.roastLevel(), draft.officialNotes(), draft.sources(),
                entries, draft.createdAt(), draft.updatedAt());
    }

    private boolean withinBufferWindow(OffsetDateTime lastMediaAt, OffsetDateTime now) {
        return Duration.between(lastMediaAt, now).compareTo(bufferWindow) <= 0;
    }

    // draft(Note)에서 노트 단위 메타만 뽑는다 — 엔트리·slug·타임스탬프는 upsertEntry가 다룬다.
    private static NoteMeta metaOf(Note draft) {
        return new NoteMeta(
                draft.coffeeName(),
                draft.roastery(),
                draft.origin(),
                draft.process(),
                draft.roastLevel(),
                draft.officialNotes(),
                draft.sources());
    }

    // 이번 시음 엔트리 — draft.entries는 1건 전제(확인 미리보기와 동일 가정). 마지막 엔트리를 취한다.
    private static Entry latestEntry(Note draft) {
        List<Entry> entries = draft.entries();
        if (entries == null || entries.isEmpty()) {
            return null;
        }
        return entries.get(entries.size() - 1);
    }
}
