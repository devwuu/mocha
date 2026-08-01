package com.devwuu.mocha.image;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/**
 * 업로드된 사진에서 <b>메타데이터를 바이트 레벨로 걷어낸다</b>
 * (ref: changes/0029 delta.md#D-7, tasks.md TΔ8a; spec AC-7).
 *
 * <p><b>왜 필요한가</b>: 아이폰 사진의 EXIF에는 촬영 시각과 GPS 좌표가 들어 있고, 그것이 쌓이면
 * <i>"언제 어디에 있었는지의 시계열"</i> — 카페 방문 이력, 곧 생활 반경 — 이 된다. vision OCR도 카드
 * 렌더링도 EXIF를 읽지 않으므로 <b>잃는 것이 없다</b>(D-7).
 *
 * <p><b>재인코딩하지 않는다.</b> 픽셀 데이터를 담은 세그먼트·청크는 바이트 그대로 복사하고 메타데이터를
 * 실을 수 있는 것만 건너뛴다 — 원본 저장(FR-10)이 요구하는 성질이고, 디코딩·재인코딩은 화질을 손실시키면서
 * 같은 목적을 달성한다. Commons Imaging은 기각했다(사용자 확정 2026-08-01): stable 릴리즈가 없고
 * (최신 {@code 1.0.0-alpha6}) EXIF 제거 API가 JPEG 전용이라 두 포맷의 경로가 갈린다.
 *
 * <p>POLICY: <b>구조를 해석하지 못하면 통과시키지 않고 거부한다</b> — 원본을 그대로 내보내면 제거에
 * 실패한 사진이 조용히 스테이징에 들어간다. 개인 데이터는 "실패 시 보수적으로"가 기본값이다
 * (ref: 루트 CLAUDE.md §5, changes/0029 delta.md#D-7).
 *
 * <p>대상 포맷은 업로드 수용 포맷과 같다 — JPEG/PNG뿐이다({@link ImageFormat#isAccepted()}). 입구
 * 게이트(ADR-29)가 먼저 판별하므로 여기 도달하는 바이트는 둘 중 하나다.
 */
public final class ExifStripper {

    /**
     * PNG의 메타데이터 청크 — 텍스트 3종은 XMP(GPS 포함 가능)를 담을 수 있고, {@code eXIf}는 EXIF 자체,
     * {@code tIME}은 최종 수정 시각이다. 나머지 청크(색·감마·투명도·픽셀)는 렌더링에 쓰이므로 남긴다.
     */
    private static final Set<String> PNG_METADATA_CHUNKS = Set.of("eXIf", "tEXt", "zTXt", "iTXt", "tIME");

    private ExifStripper() {
    }

    /**
     * 메타데이터를 제거한 바이트를 돌려준다. 제거할 것이 없으면 내용이 같은 새 배열이다.
     *
     * @param format 입구 게이트가 매직바이트로 판별한 포맷(JPEG/PNG).
     * @param bytes  업로드 원본 바이트.
     * @throws IllegalArgumentException 지원하지 않는 포맷이거나 구조가 깨져 안전하게 걷어낼 수 없을 때.
     */
    public static byte[] strip(ImageFormat format, byte[] bytes) {
        return switch (format) {
            case JPEG -> stripJpeg(bytes);
            case PNG -> stripPng(bytes);
            default -> throw new IllegalArgumentException("메타데이터 제거를 지원하지 않는 포맷: " + format);
        };
    }

    /**
     * JPEG — SOI 뒤의 마커 세그먼트를 훑어 <b>APP0~APP15와 COM을 버린다</b>.
     *
     * <p>APPn을 가려 받지 않고 전부 버리는 것이 판단이다: EXIF는 APP1이지만 XMP도 APP1, IPTC는 APP13,
     * 그 밖에 제조사가 임의로 쓰는 자리도 APPn이라 <i>"어느 APPn이 안전한가"</i>가 유지해야 할 정책이 된다.
     * 대가로 JFIF 밀도(APP0)와 ICC 프로파일(APP2)이 함께 사라지지만, 대상이 원두 봉투 사진이고 카드
     * 렌더링은 색 관리를 하지 않으므로 실질 손실이 없다.
     *
     * <p>SOS(스캔 시작)를 만나면 <b>거기서부터 파일 끝까지 그대로</b> 복사한다 — 뒤는 엔트로피 부호화된
     * 픽셀 데이터라 마커 구조로 읽을 수 없고(0xFF00·RSTn이 섞인다) 메타데이터도 있을 수 없다.
     */
    private static byte[] stripJpeg(byte[] bytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length);
        out.write(0xFF);
        out.write(0xD8);
        int i = 2;
        while (i < bytes.length) {
            if (unsigned(bytes, i) != 0xFF) {
                throw malformed("JPEG 마커가 0xFF로 시작하지 않는다", i);
            }
            // 마커 앞의 fill 바이트(0xFF 반복)는 규격상 허용된다 — 하나로 정규화해 넘어간다.
            while (i < bytes.length && unsigned(bytes, i) == 0xFF) {
                i++;
            }
            if (i >= bytes.length) {
                throw malformed("마커 번호 없이 파일이 끝났다", i);
            }
            int marker = unsigned(bytes, i);
            i++;
            if (marker == 0xD9) { // EOI — 뒤에 붙은 잔재는 버린다.
                out.write(0xFF);
                out.write(0xD9);
                return out.toByteArray();
            }
            if (marker == 0x01 || (marker >= 0xD0 && marker <= 0xD7)) { // TEM·RSTn — 길이 없는 단독 마커.
                out.write(0xFF);
                out.write(marker);
                continue;
            }
            if (i + 1 >= bytes.length) {
                throw malformed("세그먼트 길이가 잘렸다", i);
            }
            int length = (unsigned(bytes, i) << 8) | unsigned(bytes, i + 1);
            if (length < 2 || i + length > bytes.length) {
                throw malformed("세그먼트 길이가 파일 범위를 벗어난다: " + length, i);
            }
            int segmentStart = i - 2; // 0xFF + 마커 번호 위치.
            if (marker == 0xDA) { // SOS — 여기부터 끝까지 픽셀이다.
                out.write(bytes, segmentStart, bytes.length - segmentStart);
                return out.toByteArray();
            }
            boolean metadata = (marker >= 0xE0 && marker <= 0xEF) || marker == 0xFE; // APPn · COM
            if (!metadata) {
                out.write(bytes, segmentStart, length + 2);
            }
            i += length;
        }
        // SOS도 EOI도 없이 끝났다 — 픽셀이 없는 파일이므로 사진이 아니다.
        throw malformed("SOS/EOI 없이 끝났다", bytes.length);
    }

    /**
     * PNG — 8바이트 시그니처 뒤의 청크를 훑어 {@link #PNG_METADATA_CHUNKS}만 버린다.
     *
     * <p>JPEG와 달리 <b>허용 목록이 아니라 제외 목록</b>인 것은 PNG 청크가 대부분 렌더링에 쓰이기
     * 때문이다(색·감마·투명도·해상도). 표준 메타데이터 자리는 위 5종이 전부다.
     */
    private static byte[] stripPng(byte[] bytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length);
        out.write(bytes, 0, 8);
        int i = 8;
        while (i < bytes.length) {
            if (i + 12 > bytes.length) {
                throw malformed("청크 헤더가 잘렸다", i);
            }
            int length = readInt(bytes, i);
            // 길이는 부호 없는 31비트이나 규격 상한이 2^31-1이라 음수는 곧 손상이다.
            if (length < 0 || i + 12L + length > bytes.length) {
                throw malformed("청크 길이가 파일 범위를 벗어난다: " + length, i);
            }
            String type = new String(bytes, i + 4, 4, StandardCharsets.US_ASCII);
            if (!PNG_METADATA_CHUNKS.contains(type)) {
                out.write(bytes, i, 12 + length); // 길이(4) + 타입(4) + 데이터 + CRC(4)
            }
            i += 12 + length;
            if ("IEND".equals(type)) { // 뒤에 붙은 잔재는 버린다.
                return out.toByteArray();
            }
        }
        throw malformed("IEND 없이 끝났다", bytes.length);
    }

    private static int unsigned(byte[] bytes, int index) {
        return bytes[index] & 0xFF;
    }

    private static int readInt(byte[] bytes, int index) {
        return (unsigned(bytes, index) << 24)
                | (unsigned(bytes, index + 1) << 16)
                | (unsigned(bytes, index + 2) << 8)
                | unsigned(bytes, index + 3);
    }

    // 원본 바이트를 메시지에 싣지 않는다 — 사진은 개인 데이터이고 로그는 비커밋이어도 최소로 남긴다(NFR-7).
    private static IllegalArgumentException malformed(String reason, int offset) {
        return new IllegalArgumentException("메타데이터를 걷어낼 수 없다(구조 손상): " + reason + " at " + offset);
    }
}
