package com.devwuu.mocha.service;

import com.devwuu.mocha.image.ExifStripper;
import com.devwuu.mocha.image.ImageFormat;
import com.devwuu.mocha.repository.PhotoStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 사진 업로드 유스케이스 — <b>포맷 게이트 → EXIF 제거 → 스테이징</b>
 * (ref: changes/0029 tasks.md TΔ8a, delta.md#D-7·#D-11; spec FR-10/AC-7, plan.md#ADR-29, data-model.md#V-12).
 *
 * <p><b>OCR은 여기서 돌지 않는다</b>(D-11). 업로드가 하는 일은 사진을 서버에 세우는 것까지고, 읽기·검색
 * 보강·병합은 그 사진이 실린 <i>턴</i>이 한다({@code agent/turn/TurnPhotoOcr}). 병합의 소유자를 모델
 * 하나로 유지하기 위한 경계이고, 그래서 업로드 응답은 파일명뿐이다.
 *
 * <p>구 {@code SlackPhotoIntake}의 자리이지만 <b>이어받은 것이 아니라 다시 쓴 것</b>이다(TΔ16 확정) —
 * 다운로드·버퍼 그룹핑(FR-10 시간 윈도우)·HEIC 썸네일 대체가 전부 Slack 파일 메타에 기대던 것이라
 * REST에 존재할 수 없다. 앱에서는 사용자가 ＋ 버튼으로 사진과 발화를 직접 묶으므로 추정할 윈도우가 없다.
 *
 * <p>service이므로 {@code @Transactional}이 없다(백엔드 CLAUDE.md §3) — 사진은 파일이고 노트 행과 같은
 * 원자 단위가 아니다. 스테이징에 남은 것은 시작 시 고아 청소({@link StagingSweeper})가 걷는다.
 */
public class PhotoService {

    private static final Logger log = LoggerFactory.getLogger(PhotoService.class);

    private final PhotoStore photoStore;

    public PhotoService(PhotoStore photoStore) {
        this.photoStore = photoStore;
    }

    /**
     * 업로드 묶음을 검증·정제해 스테이징에 세우고 스테이징 파일명을 순서대로 돌려준다.
     *
     * <p>POLICY: <b>한 장이라도 거부되면 아무것도 스테이징하지 않는다</b>(TΔ8a). 계약이 응답에 성공분만
     * 싣는 형태(<i>{@code {photos:[{name}]}}</i>, delta.md#D-11)라 부분 성공을 표현할 자리가 없고, 표현할 수
     * 있더라도 <i>"몇 장은 올라갔고 몇 장은 아니다"</i>는 사용자가 다시 고르는 것보다 복잡하다. 구 Slack
     * 경로가 정상분만 처리하고 안내하던 것(AC-46)은 <b>사용자가 무엇을 보낼지 고를 수 없던</b> 매체의
     * 사정이었고, 앱 피커에는 그 사정이 없다.
     *
     * @param userId  스테이징 격리 키 — 고정 단일 사용자({@code com.devwuu.mocha.SingleUser})다(A3 비범위).
     * @param uploads 업로드된 사진 원본. 빈 목록이면 빈 결과(스테이징 무변화).
     * @return 스테이징된 파일명(입력 순서). 이 값이 그대로 턴 요청의 {@code photos}가 된다.
     * @throws IllegalArgumentException 수용하지 않는 포맷이거나 메타데이터를 걷어낼 수 없는 사진이 섞였을 때.
     */
    public List<String> stage(String userId, List<PhotoUpload> uploads) {
        if (uploads == null || uploads.isEmpty()) {
            return List.of();
        }
        // 검증·정제를 전부 끝낸 뒤에 쓴다 — 거부가 섞이면 아무것도 남기지 않기 위해서다(위 POLICY).
        List<PhotoUpload> cleaned = new ArrayList<>(uploads.size());
        for (PhotoUpload upload : uploads) {
            ImageFormat format = ImageFormat.detect(upload.bytes());
            // POLICY: 매직바이트로 판별한 포맷만 통과한다 — 확장자·Content-Type은 신뢰하지 않는다
            //         (ref: plan.md#ADR-29, data-model.md#V-12). poison이 스테이징에 들어가면
            //         vision 호출이 400으로 터지는 자리라 입구에서 막는다(changes/0013 delta #2).
            if (!format.isAccepted()) {
                log.info("업로드 거부(수용하지 않는 포맷): user={} filename={} format={}",
                        userId, upload.filename(), format);
                throw new IllegalArgumentException("수용하지 않는 포맷: " + format);
            }
            // EXIF·GPS는 여기서 사라진다(AC-7) — 실패하면 원본을 통과시키지 않고 거부한다(ExifStripper POLICY).
            cleaned.add(new PhotoUpload(upload.filename(), ExifStripper.strip(format, upload.bytes())));
        }
        List<String> names = new ArrayList<>(cleaned.size());
        for (PhotoUpload upload : cleaned) {
            names.add(photoStore.stage(userId, upload.filename(), upload.bytes()));
        }
        log.info("사진 업로드 스테이징: user={} photos={}", userId, names.size());
        return names;
    }

    /**
     * 대기 중이던 스테이징 사진을 폐기한다 — 작성 취소 경로(FR-10).
     *
     * <p>구 {@code SlackCommitHandler.cancel}이 하던 일이고, 그 자리는 [취소] 통지
     * ({@code POST /api/agent/cancel})가 이어받았다. 저장 확정 쪽 이동은 {@code NoteService.commit}이
     * 가져갔다(TΔ8b) — 아카이브 경로가 저장된 노트에서 나오므로 사진 유스케이스가 아니라 저장 유스케이스다.
     */
    public void discard(String userId) {
        photoStore.discard(userId);
    }
}
