package com.devwuu.mocha.image;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * ImageFormat 매직바이트 판별 검증 (ref: plan.md#ADR-29, data-model.md#V-12, AC-Δ3).
 * <p>확장자·메타가 아니라 선두 바이트로만 판별하고, vision 지원 여부가 스테이징 입구 게이트의 기준이 됨을 본다.
 */
class ImageFormatTest {

    @Test
    @DisplayName("AC-Δ3: JPEG/PNG/GIF/WebP 매직바이트를 각 포맷으로 판별하고 vision 지원으로 표시한다")
    void detectsSupportedFormats() {
        assertThat(ImageFormat.detect(bytes(0xFF, 0xD8, 0xFF, 0xE0, 0, 0, 0, 0, 0, 0, 0, 0)))
                .isEqualTo(ImageFormat.JPEG);
        assertThat(ImageFormat.detect(bytes(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0)))
                .isEqualTo(ImageFormat.PNG);
        assertThat(ImageFormat.detect("GIF89a".getBytes(java.nio.charset.StandardCharsets.US_ASCII)))
                .isEqualTo(ImageFormat.GIF);
        assertThat(ImageFormat.detect(bytes(0x52, 0x49, 0x46, 0x46, 0, 0, 0, 0, 0x57, 0x45, 0x42, 0x50)))
                .isEqualTo(ImageFormat.WEBP);

        assertThat(ImageFormat.JPEG.isVisionSupported()).isTrue();
        assertThat(ImageFormat.PNG.isVisionSupported()).isTrue();
        assertThat(ImageFormat.GIF.isVisionSupported()).isTrue();
        assertThat(ImageFormat.WEBP.isVisionSupported()).isTrue();
    }

    @Test
    @DisplayName("ADR-29: HEIC(ftyp heic 계열)는 판별되지만 vision 미지원이다")
    void detectsHeicButNotVisionSupported() {
        // findings-TΔ0: 아이폰 HEIC. ISO-BMFF ftyp 박스의 브랜드가 heic.
        byte[] heic = bytes(0, 0, 0, 0x18, 0x66, 0x74, 0x79, 0x70, 0x68, 0x65, 0x69, 0x63); // "ftypheic"
        assertThat(ImageFormat.detect(heic)).isEqualTo(ImageFormat.HEIC);
        assertThat(ImageFormat.HEIC.isVisionSupported()).isFalse();

        // mif1(HEIF still image) 브랜드도 계열로 인식.
        byte[] mif1 = bytes(0, 0, 0, 0x18, 0x66, 0x74, 0x79, 0x70, 0x6D, 0x69, 0x66, 0x31); // "ftypmif1"
        assertThat(ImageFormat.detect(mif1)).isEqualTo(ImageFormat.HEIC);
    }

    @Test
    @DisplayName("V-12: 미상·너무 짧음·null 바이트는 UNKNOWN이며 vision 미지원이다")
    void unknownForUnrecognizedBytes() {
        assertThat(ImageFormat.detect(null)).isEqualTo(ImageFormat.UNKNOWN);
        assertThat(ImageFormat.detect(new byte[]{1, 2, 3})).isEqualTo(ImageFormat.UNKNOWN);
        assertThat(ImageFormat.detect(new byte[0])).isEqualTo(ImageFormat.UNKNOWN);
        // RIFF지만 WEBP가 아닌 컨테이너(예: WAV)는 이미지가 아니다.
        assertThat(ImageFormat.detect(bytes(0x52, 0x49, 0x46, 0x46, 0, 0, 0, 0, 0x57, 0x41, 0x56, 0x45)))
                .isEqualTo(ImageFormat.UNKNOWN);
        assertThat(ImageFormat.UNKNOWN.isVisionSupported()).isFalse();
    }

    @Test
    @DisplayName("판별된 지원 포맷은 mime·확장자를 주고, UNKNOWN은 접근 시 던진다")
    void mimeAndExtensionForSupportedOnly() {
        assertThat(ImageFormat.PNG.mimeType()).isEqualTo("image/png");
        assertThat(ImageFormat.PNG.extension()).isEqualTo("png");
        assertThat(ImageFormat.JPEG.extension()).isEqualTo("jpg");

        assertThatThrownBy(ImageFormat.UNKNOWN::mimeType).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(ImageFormat.UNKNOWN::extension).isInstanceOf(IllegalStateException.class);
    }

    private static byte[] bytes(int... values) {
        byte[] out = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            out[i] = (byte) values[i];
        }
        return out;
    }
}
