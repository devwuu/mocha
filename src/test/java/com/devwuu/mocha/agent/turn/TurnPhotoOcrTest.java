package com.devwuu.mocha.agent.turn;

import com.devwuu.mocha.llm.PhotoInfoExtractor;
import com.devwuu.mocha.llm.VisionClient;
import com.devwuu.mocha.llm.VisionExtraction;
import com.devwuu.mocha.llm.VisionHint;
import com.devwuu.mocha.repository.RecordingPhotoStore;
import com.devwuu.mocha.repository.StagedImage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TΔ8a(changes/0029): 이번 턴에 실린 사진만 골라 OCR에 넣는다
 * (ref: delta.md#D-11, plan.md#ADR-23·#ADR-44, spec FR-19/AC-Δ5).
 *
 * <p>검증의 축은 <b>무엇이 vision에 실리는가</b>다. 스테이징에는 저장·취소 전까지 올린 사진이 누적되므로,
 * 전부 읽으면 앞선 메시지의 사진이 매 턴 다시 OCR에 실려 <b>과금과 문맥이 함께 늘어난다</b> — 이름으로
 * 고르는 것이 그것을 막는 유일한 장치다(사진과 발화를 사용자가 직접 묶는다는 D-11의 실현).
 */
class TurnPhotoOcrTest {

    private static final String USER = "local";

    private RecordingPhotoStore photoStore;
    private RecordingVisionClient visionClient;
    private TurnPhotoOcr photoOcr;

    @BeforeEach
    void setUp() {
        photoStore = new RecordingPhotoStore();
        visionClient = new RecordingVisionClient();
        photoOcr = new TurnPhotoOcr(photoStore, new PhotoInfoExtractor(visionClient, 4));
    }

    @Test
    @DisplayName("D-11: 이번 턴에 실린 이름의 사진만 vision에 간다 — 스테이징에 누적된 앞선 사진은 빠진다")
    void readsOnlyThePhotosAttachedToThisTurn() {
        photoStore.staged.addAll(List.of(image("old.jpg"), image("front.jpg"), image("back.jpg")));

        photoOcr.read(USER, List.of("front.jpg", "back.jpg"));

        assertThat(visionClient.calls).isEqualTo(1);
        assertThat(visionClient.lastImageCount).isEqualTo(2);
    }

    @Test
    @DisplayName("TΔ8a: 사진이 없는 턴은 vision을 부르지 않는다 — 대부분의 턴이 이 경로다")
    void turnWithoutPhotosSkipsVision() {
        photoStore.staged.addAll(List.of(image("front.jpg")));

        assertThat(photoOcr.read(USER, List.of())).isEqualTo(VisionExtraction.empty());
        assertThat(photoOcr.read(USER, null)).isEqualTo(VisionExtraction.empty());
        assertThat(visionClient.calls).isZero();
        // 스테이징을 읽지도 않는다 — 부를 것이 없으면 파일 I/O도 없다.
        assertThat(photoStore.reads).isZero();
    }

    @Test
    @DisplayName("TΔ8a: 실린 이름이 스테이징에 없으면 빈 결과다 — 재시작·고아 청소로 사라진 뒤가 그렇다")
    void missingStagedPhotosYieldEmptyResult() {
        

        assertThat(photoOcr.read(USER, List.of("gone.jpg"))).isEqualTo(VisionExtraction.empty());
        assertThat(visionClient.calls).isZero();
    }

    @Test
    @DisplayName("TΔ8a: 일부만 남아 있으면 있는 것으로 진행한다 — 사진 한 장 때문에 발화를 잃지 않는다")
    void partiallyMissingPhotosStillRun() {
        photoStore.staged.addAll(List.of(image("front.jpg")));

        photoOcr.read(USER, List.of("front.jpg", "gone.jpg"));

        assertThat(visionClient.calls).isEqualTo(1);
        assertThat(visionClient.lastImageCount).isEqualTo(1);
    }

    @Test
    @DisplayName("ADR-23: 힌트는 비운다 — 사진-only 흐름은 커피명을 사진에서 읽는다")
    void callsVisionWithoutHint() {
        photoStore.staged.addAll(List.of(image("front.jpg")));

        photoOcr.read(USER, List.of("front.jpg"));

        assertThat(visionClient.lastHint).isEqualTo(new VisionHint(null, null));
    }

    @Test
    @DisplayName("AC-Δ5: vision 실패는 턴을 막지 않는다 — 빈 결과로 진행하고 사진은 첨부로만 남는다")
    void visionFailureFallsBackToEmpty() {
        photoStore.staged.addAll(List.of(image("front.jpg")));
        visionClient.failure = new RuntimeException("vision 400");

        assertThat(photoOcr.read(USER, List.of("front.jpg"))).isEqualTo(VisionExtraction.empty());
    }

    private static StagedImage image(String name) {
        // 매직바이트가 JPEG여야 data URI mime 판별을 지난다(PhotoInfoExtractor).
        byte[] bytes = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0};
        return new StagedImage(name, bytes);
    }

    /** 몇 장이 실제로 실렸는지만 붙드는 fake — 실 API는 여기 오지 않는다(§5.3 단위 분류). */
    static final class RecordingVisionClient implements VisionClient {

        final List<String> lastImageUrls = new ArrayList<>();
        int calls;
        int lastImageCount;
        VisionHint lastHint;
        RuntimeException failure;

        @Override
        public VisionExtraction read(List<String> imageUrls, VisionHint hint) {
            calls++;
            lastImageCount = imageUrls.size();
            lastImageUrls.clear();
            lastImageUrls.addAll(imageUrls);
            lastHint = hint;
            if (failure != null) {
                throw failure;
            }
            return new VisionExtraction("Kenya AA", null, List.of(), null, List.of());
        }
    }
}
