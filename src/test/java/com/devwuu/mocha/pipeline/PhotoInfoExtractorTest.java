package com.devwuu.mocha.pipeline;

import com.devwuu.mocha.llm.VisionClient;
import com.devwuu.mocha.llm.VisionExtraction;
import com.devwuu.mocha.llm.VisionHint;
import com.devwuu.mocha.repository.StagedImage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PhotoInfoExtractor([2.5], FR-19/ADR-23) 계약 검증 — 묶인 사진 1콜 멀티이미지 전달, 상한 절삭,
 * 실패·무사진의 빈 결과 수렴을 fake VisionClient로 결정론적으로 확인한다(ref: CLAUDE.md §5.2).
 */
class PhotoInfoExtractorTest {

    /** vision 호출 여부·전달 이미지·힌트를 기록하는 fake. */
    private static final class RecordingVision implements VisionClient {
        VisionExtraction canned = new VisionExtraction(
                "와이키키", "모모스", "에티오피아", "워시드", "라이트", List.of("자스민"));
        RuntimeException toThrow = null;
        int calls = 0;
        List<String> lastImageUrls = List.of();
        VisionHint lastHint = null;

        @Override
        public VisionExtraction read(List<String> imageUrls, VisionHint hint) {
            calls++;
            lastImageUrls = imageUrls;
            lastHint = hint;
            if (toThrow != null) {
                throw toThrow;
            }
            return canned;
        }
    }

    private static List<StagedImage> stagedImages(int n) {
        List<StagedImage> images = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            images.add(new StagedImage("bag-" + i + ".jpg", new byte[]{(byte) i}));
        }
        return images;
    }

    private static VisionHint hint() {
        return new VisionHint(null, null); // 사진-only 흐름 — 커피명 미상
    }

    @Test
    @DisplayName("ADR-23: 묶인 사진 전부를 vision 1콜에 data URI로 전달한다")
    void sendsAllImagesInSingleCallAsDataUri() {
        RecordingVision vision = new RecordingVision();
        PhotoInfoExtractor extractor = new PhotoInfoExtractor(vision, 4);

        VisionExtraction result = extractor.extract(stagedImages(3), hint());

        assertThat(vision.calls).isEqualTo(1);
        assertThat(vision.lastImageUrls).hasSize(3);
        assertThat(vision.lastImageUrls).allSatisfy(url ->
                assertThat(url).startsWith("data:image/jpeg;base64,"));
        assertThat(result.coffeeName()).isEqualTo("와이키키");
    }

    @Test
    @DisplayName("AC-Δ6: 상한 초과 사진은 절삭돼 호출에 상한만큼만 실린다(초과분 미전달)")
    void capsImagesToMaxImages() {
        RecordingVision vision = new RecordingVision();
        PhotoInfoExtractor extractor = new PhotoInfoExtractor(vision, 4);

        extractor.extract(stagedImages(6), hint());

        assertThat(vision.calls).isEqualTo(1);
        assertThat(vision.lastImageUrls).hasSize(4);
    }

    @Test
    @DisplayName("확장자별 mime — png/webp/heic를 data URI에 반영한다")
    void encodesMimeFromExtension() {
        RecordingVision vision = new RecordingVision();
        PhotoInfoExtractor extractor = new PhotoInfoExtractor(vision, 4);

        extractor.extract(List.of(
                new StagedImage("a.png", new byte[]{1}),
                new StagedImage("b.webp", new byte[]{2}),
                new StagedImage("c.heic", new byte[]{3})), hint());

        assertThat(vision.lastImageUrls.get(0)).startsWith("data:image/png;base64,");
        assertThat(vision.lastImageUrls.get(1)).startsWith("data:image/webp;base64,");
        assertThat(vision.lastImageUrls.get(2)).startsWith("data:image/heic;base64,");
    }

    @Test
    @DisplayName("사진이 없으면 vision 호출 없이 빈 결과로 수렴한다")
    void noImagesYieldsEmptyWithoutCall() {
        RecordingVision vision = new RecordingVision();
        PhotoInfoExtractor extractor = new PhotoInfoExtractor(vision, 4);

        VisionExtraction result = extractor.extract(List.of(), hint());

        assertThat(vision.calls).isEqualTo(0);
        assertThat(result).isEqualTo(VisionExtraction.empty());
    }

    @Test
    @DisplayName("AC-Δ5: vision 호출이 예외를 던져도 빈 결과로 수렴한다(예외 미전파)")
    void callFailureYieldsEmpty() {
        RecordingVision vision = new RecordingVision();
        vision.toThrow = new RuntimeException("timeout");
        PhotoInfoExtractor extractor = new PhotoInfoExtractor(vision, 4);

        VisionExtraction result = extractor.extract(stagedImages(2), hint());

        assertThat(result).isEqualTo(VisionExtraction.empty());
    }

    @Test
    @DisplayName("커피명 힌트(nullable)를 그대로 vision에 전달한다")
    void passesNullableHint() {
        RecordingVision vision = new RecordingVision();
        PhotoInfoExtractor extractor = new PhotoInfoExtractor(vision, 4);

        extractor.extract(stagedImages(1), new VisionHint("예가체프", "커피베라"));

        assertThat(vision.lastHint.coffeeName()).isEqualTo("예가체프");
        assertThat(vision.lastHint.roastery()).isEqualTo("커피베라");
    }
}
