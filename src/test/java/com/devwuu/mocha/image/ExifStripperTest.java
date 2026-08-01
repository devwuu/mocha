package com.devwuu.mocha.image;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TΔ8a(changes/0029): EXIF 제거 — <b>메타데이터는 사라지고 그림은 그대로</b>
 * (ref: delta.md#D-7, spec AC-7).
 *
 * <p>검증의 축이 둘이고 <b>둘 다 있어야 의미가 있다</b>: 지웠는가(개인정보)와 안 망가뜨렸는가(원본 보존).
 * 하나만 보면 통과하는 잘못된 구현이 각각 있다 — 전부 지우면 첫째만, 아무것도 안 하면 둘째만 통과한다.
 * 그래서 <b>실제 디코더로 다시 읽어 픽셀을 대조</b>한다(재인코딩하지 않으므로 픽셀이 정확히 같아야 한다).
 */
class ExifStripperTest {

    @Test
    @DisplayName("AC-7: JPEG의 EXIF(APP1)가 사라진다 — GPS 좌표가 바이트 어디에도 남지 않는다")
    void removesExifFromJpeg() {
        byte[] withExif = TestImages.jpegWithExif();
        assertThat(contains(withExif, TestImages.SECRET)).as("전제: 주입한 EXIF가 원본에 있다").isTrue();

        byte[] stripped = ExifStripper.strip(ImageFormat.JPEG, withExif);

        assertThat(contains(stripped, TestImages.SECRET)).isFalse();
        assertThat(contains(stripped, "Exif")).isFalse();
    }

    @Test
    @DisplayName("D-7: 걷어낸 JPEG는 여전히 같은 그림이다 — 재인코딩하지 않으므로 픽셀이 정확히 같다(FR-10 원본 저장)")
    void strippedJpegStillDecodesToTheSamePixels() {
        byte[] original = TestImages.jpeg();

        byte[] stripped = ExifStripper.strip(ImageFormat.JPEG, TestImages.jpegWithExif());

        assertThat(TestImages.pixels(stripped))
                .as("걷어낸 뒤 디코딩되지 않으면 사진을 잃은 것이다")
                .isNotNull()
                .isEqualTo(TestImages.pixels(original));
    }

    @Test
    @DisplayName("AC-7: PNG의 eXIf 청크가 사라진다")
    void removesExifChunkFromPng() {
        byte[] withExif = TestImages.pngWithExif();
        assertThat(contains(withExif, TestImages.SECRET)).as("전제: 주입한 eXIf가 원본에 있다").isTrue();

        byte[] stripped = ExifStripper.strip(ImageFormat.PNG, withExif);

        assertThat(contains(stripped, TestImages.SECRET)).isFalse();
        assertThat(contains(stripped, "eXIf")).isFalse();
    }

    @Test
    @DisplayName("AC-7: PNG의 텍스트 청크(tEXt)도 걷는다 — XMP·설명에 위치가 문자열로 실릴 수 있다")
    void removesTextChunksFromPng() {
        byte[] stripped = ExifStripper.strip(ImageFormat.PNG, TestImages.pngWithTextChunk());

        assertThat(contains(stripped, TestImages.SECRET)).isFalse();
        assertThat(contains(stripped, "tEXt")).isFalse();
    }

    @Test
    @DisplayName("D-7: 걷어낸 PNG도 같은 그림이다 — 픽셀 청크(IDAT)는 손대지 않는다")
    void strippedPngStillDecodesToTheSamePixels() {
        byte[] original = TestImages.png();

        byte[] stripped = ExifStripper.strip(ImageFormat.PNG, TestImages.pngWithExif());

        assertThat(TestImages.pixels(stripped)).isNotNull().isEqualTo(TestImages.pixels(original));
    }

    @Test
    @DisplayName("TΔ8a: 걷어낼 것이 없는 사진은 그대로 통과한다 — 정상 업로드가 대부분 이 경로다")
    void cleanImagesPassThrough() {
        assertThat(TestImages.pixels(ExifStripper.strip(ImageFormat.PNG, TestImages.png())))
                .isEqualTo(TestImages.pixels(TestImages.png()));
        assertThat(TestImages.pixels(ExifStripper.strip(ImageFormat.JPEG, TestImages.jpeg())))
                .isEqualTo(TestImages.pixels(TestImages.jpeg()));
    }

    @Test
    @DisplayName("POLICY: 구조가 깨져 걷어낼 수 없으면 원본을 통과시키지 않고 거부한다 — 개인 데이터는 보수적으로")
    void malformedBytesAreRejectedRatherThanPassedThrough() {
        // 매직바이트는 JPEG인데 뒤가 잘렸다 — 게이트를 통과한 뒤 구조가 어긋나는 경우다.
        byte[] truncated = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE1, 0x00};
        assertThatThrownBy(() -> ExifStripper.strip(ImageFormat.JPEG, truncated))
                .isInstanceOf(IllegalArgumentException.class);

        // PNG 시그니처 뒤에 청크 헤더가 없다.
        byte[] headerOnly = {(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00};
        assertThatThrownBy(() -> ExifStripper.strip(ImageFormat.PNG, headerOnly))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("TΔ8a: 수용 포맷이 아닌 것은 애초에 오지 않는다 — 와도 거부한다(게이트와 이중 방어)")
    void unsupportedFormatIsRejected() {
        assertThatThrownBy(() -> ExifStripper.strip(ImageFormat.HEIC, new byte[]{1, 2, 3}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ExifStripper.strip(ImageFormat.UNKNOWN, new byte[]{1, 2, 3}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static boolean contains(byte[] haystack, String needle) {
        return new String(haystack, StandardCharsets.ISO_8859_1)
                .contains(new String(needle.getBytes(StandardCharsets.UTF_8), StandardCharsets.ISO_8859_1));
    }
}
