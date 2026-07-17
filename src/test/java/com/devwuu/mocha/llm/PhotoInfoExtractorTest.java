package com.devwuu.mocha.llm;

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

    // 스테이징 게이트(ADR-29)를 통과한 정상 사진 — mime은 매직바이트로 판별되므로 유효한 JPEG 선두를 준다.
    private static byte[] jpeg() {
        return new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0, 0, 0, 0, 0};
    }

    private static List<StagedImage> stagedImages(int n) {
        List<StagedImage> images = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            images.add(new StagedImage("bag-" + i + ".jpg", jpeg()));
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
    @DisplayName("AC-Δ3: mime는 확장자가 아니라 매직바이트로 판별한다(확장자와 무관)")
    void encodesMimeFromMagicBytesNotExtension() {
        RecordingVision vision = new RecordingVision();
        PhotoInfoExtractor extractor = new PhotoInfoExtractor(vision, 4);

        // 파일명 확장자와 실제 바이트를 일부러 어긋나게 — 판별은 바이트를 따라야 한다.
        byte[] png = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
        byte[] webp = {0x52, 0x49, 0x46, 0x46, 0, 0, 0, 0, 0x57, 0x45, 0x42, 0x50}; // RIFF..WEBP
        extractor.extract(List.of(
                new StagedImage("a.jpg", png),    // 이름은 jpg지만 바이트는 PNG
                new StagedImage("b.heic", webp)), // 이름은 heic지만 바이트는 WebP
                hint());

        assertThat(vision.lastImageUrls.get(0)).startsWith("data:image/png;base64,");
        assertThat(vision.lastImageUrls.get(1)).startsWith("data:image/webp;base64,");
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
