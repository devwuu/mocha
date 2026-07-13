package com.devwuu.mocha.image;

import java.nio.charset.StandardCharsets;

/**
 * 사진 바이트의 <b>매직바이트</b>로 이미지 포맷을 판별한다
 * (ref: plan.md#ADR-29, data-model.md#V-12, changes/0013-v3-field-quality-and-photo-intake).
 * <p>확장자·Slack 메타 {@code mimetype}은 신뢰하지 않는다 — 아이폰 HEIC가 확장자 기반 판별
 * ({@code PhotoInfoExtractor.mimeOf})을 통과해 그대로 OpenAI vision에 전달돼 400을 유발한 실사용
 * 관측(delta #2) 때문이다. vision 지원 포맷(JPEG/PNG/GIF/WebP)만 스테이징을 통과시키는 입구 게이트가
 * 이 판별을 근거로 삼는다(V-12). HEIC는 vision 미지원으로 표시되지만 조용히 버리지 않는다 — 상위가
 * Slack 썸네일 대체(TΔ3) 또는 "지원하지 않는 포맷" 안내로 수렴시킨다.
 */
public enum ImageFormat {
    JPEG("image/jpeg", "jpg", true),
    PNG("image/png", "png", true),
    GIF("image/gif", "gif", true),
    WEBP("image/webp", "webp", true),
    // vision 미지원 — 원본은 스테이징하지 않고, HEIC는 Slack 썸네일(실측 PNG)로 대체한다(ADR-29, TΔ3).
    HEIC("image/heic", "heic", false),
    // 판별 불가 — 스테이징 금지, 안내로 수렴.
    UNKNOWN(null, null, false);

    private final String mimeType;
    private final String extension;
    private final boolean visionSupported;

    ImageFormat(String mimeType, String extension, boolean visionSupported) {
        this.mimeType = mimeType;
        this.extension = extension;
        this.visionSupported = visionSupported;
    }

    /** OpenAI vision이 읽을 수 있는 포맷인가(JPEG/PNG/GIF/WebP). 스테이징 입구 게이트의 통과 기준. */
    public boolean isVisionSupported() {
        return visionSupported;
    }

    /** data URI·스테이징에 쓸 MIME. {@link #UNKNOWN}에는 없다 — 호출 전 지원 여부를 확인할 것. */
    public String mimeType() {
        if (mimeType == null) {
            throw new IllegalStateException("판별되지 않은 포맷에는 MIME이 없다: " + this);
        }
        return mimeType;
    }

    /** 스테이징 파일 확장자(점 없음). {@link #UNKNOWN}에는 없다. */
    public String extension() {
        if (extension == null) {
            throw new IllegalStateException("판별되지 않은 포맷에는 확장자가 없다: " + this);
        }
        return extension;
    }

    /**
     * 선두 바이트로 포맷을 판별한다. 확장자·메타는 보지 않는다.
     *
     * @param bytes 원본(또는 다운로드) 바이트. null·너무 짧음·미상은 {@link #UNKNOWN}.
     */
    public static ImageFormat detect(byte[] bytes) {
        if (bytes == null) {
            return UNKNOWN;
        }
        if (matchesAt(bytes, 0, 0xFF, 0xD8, 0xFF)) {
            return JPEG;
        }
        if (matchesAt(bytes, 0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)) {
            return PNG;
        }
        if (matchesAt(bytes, 0, 0x47, 0x49, 0x46, 0x38)) { // "GIF8" — 87a/89a 공통 선두
            return GIF;
        }
        // RIFF 컨테이너의 8~11 바이트가 "WEBP".
        if (matchesAt(bytes, 0, 0x52, 0x49, 0x46, 0x46) && matchesAt(bytes, 8, 0x57, 0x45, 0x42, 0x50)) {
            return WEBP;
        }
        // ISO-BMFF ftyp 박스(4~7 "ftyp") + 8~11 브랜드가 HEIF 계열.
        if (matchesAt(bytes, 4, 0x66, 0x74, 0x79, 0x70) && isHeifBrand(bytes)) {
            return HEIC;
        }
        return UNKNOWN;
    }

    // offset부터 magic 바이트열과 정확히 일치하는가(범위 밖이면 false).
    private static boolean matchesAt(byte[] bytes, int offset, int... magic) {
        if (bytes.length < offset + magic.length) {
            return false;
        }
        for (int i = 0; i < magic.length; i++) {
            if ((bytes[offset + i] & 0xFF) != magic[i]) {
                return false;
            }
        }
        return true;
    }

    // ftyp 박스의 major brand(8~11)가 HEIF/HEIC 계열인지. HEVC 코딩·이미지/시퀀스 브랜드를 아우른다.
    private static boolean isHeifBrand(byte[] bytes) {
        if (bytes.length < 12) {
            return false;
        }
        String brand = new String(bytes, 8, 4, StandardCharsets.US_ASCII);
        return switch (brand) {
            case "heic", "heix", "heim", "heis", "hevc", "hevx", "hevm", "hevs", "heif", "mif1", "msf1" -> true;
            default -> false;
        };
    }
}
