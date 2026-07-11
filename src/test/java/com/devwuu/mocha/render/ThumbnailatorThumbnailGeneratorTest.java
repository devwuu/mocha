package com.devwuu.mocha.render;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4-3: ThumbnailatorThumbnailGenerator — 임시 디렉토리 실제 파일 I/O로 검증(CLAUDE.md §5.2).
 * <p>핵심 회귀 가드: EXIF orientation 6(가로 저장 → 세로 표시) 사진이 눕지 않게 보정되는가(plan.md §5 선례).
 * 부수로 photos/→thumbs/ 트리 미러링과 thumb-width 스케일을 확인한다.
 */
class ThumbnailatorThumbnailGeneratorTest {

    private static final int THUMB_WIDTH = 50;

    private Path dataDir;
    private Path siteDir;
    private ThumbnailatorThumbnailGenerator generator;

    @BeforeEach
    void setUp(@TempDir Path root) {
        this.dataDir = root.resolve("data");
        this.siteDir = root.resolve("site");
        this.generator = new ThumbnailatorThumbnailGenerator(dataDir, siteDir, THUMB_WIDTH);
    }

    @Test
    @DisplayName("T4-3: EXIF orientation 6 사진은 보정돼 세로 썸네일이 된다(눕는 회귀 가드)")
    void correctsExifOrientationSoThumbnailStaysUpright() throws IOException {
        // 저장된 픽셀은 가로(200x100)지만 orientation=6 → 표시 방향은 세로(100x200).
        writePhoto("photos/coffeevera/2026-07-11/cup.jpg", jpegWithOrientation6(200, 100));

        String thumbRel = generator.makeThumbnail("photos/coffeevera/2026-07-11/cup.jpg");

        BufferedImage thumb = ImageIO.read(siteDir.resolve(thumbRel).toFile());
        // 보정됐다면 세로(높이>너비). 미보정이면 가로(너비>높이)가 됐을 것.
        assertThat(thumb.getHeight()).isGreaterThan(thumb.getWidth());
    }

    @Test
    @DisplayName("T4-3: photos/ 원본 트리를 thumbs/로 미러링하고 site 아래에 쓴다(상대 경로만, AC-11)")
    void mirrorsPhotosTreeUnderThumbs() throws IOException {
        writePhoto("photos/note-a/2026-07-11/a.jpg", plainJpeg(200, 100));

        String thumbRel = generator.makeThumbnail("photos/note-a/2026-07-11/a.jpg");

        assertThat(thumbRel).isEqualTo("thumbs/note-a/2026-07-11/a.jpg");
        assertThat(Path.of(thumbRel).isAbsolute()).isFalse();
        assertThat(siteDir.resolve(thumbRel)).exists();
        // 파생물만 쓴다 — 원본 트리(data/photos)는 건드리지 않는다.
        assertThat(dataDir.resolve("thumbs")).doesNotExist();
    }

    @Test
    @DisplayName("T4-3: 썸네일 폭은 mocha.photo.thumb-width로 맞춘다")
    void scalesToThumbWidth() throws IOException {
        writePhoto("photos/note-b/2026-07-11/b.jpg", plainJpeg(400, 200));

        String thumbRel = generator.makeThumbnail("photos/note-b/2026-07-11/b.jpg");

        BufferedImage thumb = ImageIO.read(siteDir.resolve(thumbRel).toFile());
        assertThat(thumb.getWidth()).isEqualTo(THUMB_WIDTH);
    }

    private void writePhoto(String relPath, byte[] bytes) throws IOException {
        Path original = dataDir.resolve(relPath);
        Files.createDirectories(original.getParent());
        Files.write(original, bytes);
    }

    private static byte[] plainJpeg(int width, int height) throws IOException {
        BufferedImage img = solid(width, height);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "jpg", baos);
        return baos.toByteArray();
    }

    // ImageIO JPEG(SOI 직후 APP1 Exif orientation=6 세그먼트 삽입) — Thumbnailator가 보정하는지 검증하는 픽스처.
    private static byte[] jpegWithOrientation6(int width, int height) throws IOException {
        byte[] jpeg = plainJpeg(width, height);
        byte[] app1 = exifApp1Orientation6();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, 2);                       // SOI (FF D8)
        out.write(app1);                             // APP1 Exif(orientation=6)
        out.write(jpeg, 2, jpeg.length - 2);         // 나머지
        return out.toByteArray();
    }

    // APP1(FFE1) + "Exif\0\0" + big-endian TIFF: IFD0에 Orientation(0x0112)=SHORT 6 한 엔트리.
    private static byte[] exifApp1Orientation6() {
        return new byte[]{
                (byte) 0xFF, (byte) 0xE1,             // APP1 마커
                0x00, 0x22,                           // 세그먼트 길이 = 34
                0x45, 0x78, 0x69, 0x66, 0x00, 0x00,   // "Exif\0\0"
                0x4D, 0x4D,                           // 바이트 정렬 MM(big-endian)
                0x00, 0x2A,                           // 매직 42
                0x00, 0x00, 0x00, 0x08,               // IFD0 오프셋 = 8
                0x00, 0x01,                           // 엔트리 수 = 1
                0x01, 0x12,                           // 태그 = Orientation
                0x00, 0x03,                           // 타입 = SHORT
                0x00, 0x00, 0x00, 0x01,               // 카운트 = 1
                0x00, 0x06, 0x00, 0x00,               // 값 = 6 (+패딩)
                0x00, 0x00, 0x00, 0x00                // 다음 IFD 없음
        };
    }

    private static BufferedImage solid(int width, int height) {
        BufferedImage img = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, width, height);
        g.dispose();
        return img;
    }
}
