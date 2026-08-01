package com.devwuu.mocha.image;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.CRC32;

/**
 * 테스트용 <b>실제</b> JPEG/PNG 바이트 + 메타데이터 주입 (changes/0029 TΔ8a).
 *
 * <p>손으로 지어낸 유사 바이트를 쓰지 않는 것이 의도다: {@link ExifStripper}가 지켜야 할 성질이
 * <i>"걷어낸 뒤에도 여전히 디코딩되는 같은 그림"</i>이라, 진짜 디코더가 읽을 수 있는 입력이어야 그 성질을
 * 단언할 수 있다. {@link ImageIO}가 만든 원본에 메타데이터 세그먼트·청크를 끼워 넣어 대상을 만든다.
 */
public final class TestImages {

    /** 주입한 메타데이터에 심는 표지 — 걷어낸 뒤 바이트 어디에도 없어야 한다. */
    public static final String SECRET = "GPS 37.5665,126.9780";

    private TestImages() {
    }

    /** 2×2 그라데이션 — 색이 균일하면 인코딩 차이를 못 잡는다. */
    public static BufferedImage sample() {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_RGB);
        image.setRGB(0, 0, 0xFF0000);
        image.setRGB(1, 0, 0x00FF00);
        image.setRGB(0, 1, 0x0000FF);
        image.setRGB(1, 1, 0xFFFFFF);
        return image;
    }

    public static byte[] jpeg() {
        return encode(sample(), "jpg");
    }

    public static byte[] png() {
        return encode(sample(), "png");
    }

    /** SOI 바로 뒤에 EXIF를 담은 APP1 세그먼트를 끼운다 — 아이폰 사진이 GPS를 싣는 자리다. */
    public static byte[] jpegWithExif() {
        byte[] original = jpeg();
        byte[] payload = ("Exif\0\0" + SECRET).getBytes(StandardCharsets.UTF_8);
        int length = payload.length + 2; // 길이 필드는 자기 자신(2)을 포함한다.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(original, 0, 2); // SOI
        out.write(0xFF);
        out.write(0xE1); // APP1
        out.write((length >> 8) & 0xFF);
        out.write(length & 0xFF);
        out.write(payload, 0, payload.length);
        out.write(original, 2, original.length - 2);
        return out.toByteArray();
    }

    /** IHDR 뒤에 {@code eXIf} 청크를 끼운다. PNG의 EXIF 자리다. */
    public static byte[] pngWithExif() {
        return pngWithChunk("eXIf", SECRET.getBytes(StandardCharsets.UTF_8));
    }

    /** IHDR 뒤에 {@code tEXt} 청크를 끼운다 — XMP·설명이 실려 GPS가 문자열로 남을 수 있는 자리다. */
    public static byte[] pngWithTextChunk() {
        return pngWithChunk("tEXt", ("Comment\0" + SECRET).getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] pngWithChunk(String type, byte[] data) {
        byte[] original = png();
        // 시그니처(8) + IHDR(길이 4 + 타입 4 + 데이터 13 + CRC 4 = 25) 뒤가 삽입 지점이다.
        int insertAt = 8 + 25;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(original, 0, insertAt);
        writeInt(out, data.length);
        byte[] typeBytes = type.getBytes(StandardCharsets.US_ASCII);
        out.write(typeBytes, 0, 4);
        out.write(data, 0, data.length);
        CRC32 crc = new CRC32();
        crc.update(typeBytes);
        crc.update(data);
        writeInt(out, (int) crc.getValue());
        out.write(original, insertAt, original.length - insertAt);
        return out.toByteArray();
    }

    private static void writeInt(ByteArrayOutputStream out, int value) {
        out.write((value >> 24) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }

    private static byte[] encode(BufferedImage image, String format) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            ImageIO.write(image, format, out);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /** 디코딩해 픽셀을 그대로 돌려준다 — 걷어내기 전후 비교용. 못 읽으면 null. */
    public static int[] pixels(byte[] bytes) {
        try {
            BufferedImage image = ImageIO.read(new java.io.ByteArrayInputStream(bytes));
            if (image == null) {
                return null;
            }
            return image.getRGB(0, 0, image.getWidth(), image.getHeight(), null, 0, image.getWidth());
        } catch (IOException e) {
            return null;
        }
    }
}
