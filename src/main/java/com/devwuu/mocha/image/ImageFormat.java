package com.devwuu.mocha.image;

import java.nio.charset.StandardCharsets;

/**
 * 사진 바이트의 <b>매직바이트</b>로 이미지 포맷을 판별한다
 * (ref: plan.md#ADR-29, data-model.md#V-12, changes/0013-v3-field-quality-and-photo-intake).
 * <p>확장자·Slack 메타 {@code mimetype}은 신뢰하지 않는다 — 아이폰 HEIC가 확장자 기반 판별
 * ({@code PhotoInfoExtractor.mimeOf})을 통과해 그대로 OpenAI vision에 전달돼 400을 유발한 실사용
 * 관측(delta #2) 때문이다. vision 지원 포맷(JPEG/PNG/GIF/WebP)만 스테이징을 통과시키는 입구 게이트가
 * 이 판별을 근거로 삼는다(V-12). HEIC는 vision 미지원으로 표시되지만 조용히 버리지 않는다 — 상위가
 * "지원하지 않는 포맷" 안내로 수렴시킨다.
 * <p><b>0029 TΔ16에서 HEIC 우회(Slack 썸네일 대체, changes/0013 TΔ3)가 소멸했다</b> — 그 경로는 Slack 파일
 * 메타(mimetype·썸네일 URL)에 기대어 REST에 존재할 수 없고, iOS 이미지 피커가 업로드 시 JPEG로 변환한다.
 * 판별 자체(이 enum)는 남는다 — 입구 게이트는 그대로다(ADR-29).
 *
 * <p><b>0029 TΔ8a에서 수용 포맷이 JPEG/PNG로 좁혀졌다</b>(사용자 확정 2026-08-01). 게이트가 묻는 질문이
 * 바뀐 것이다 — 구 기준은 <i>"vision이 읽을 수 있는가"</i>였고 GIF·WebP는 지금도 읽을 수 있지만, 4종으로
 * 넓었던 이유가 <i>"Slack이 받는 포맷이 넓어서"</i>였고 앱 업로드에는 그 사정이 없다. 좁히면
 * {@link ExifStripper}가 다뤄야 할 컨테이너도 둘로 고정된다.
 */
public enum ImageFormat {
    JPEG("image/jpeg", "jpg", true),
    PNG("image/png", "png", true),
    // vision은 읽지만 업로드로 받지 않는다(TΔ8a) — 판별은 남겨 거부 사유를 구분 관측한다.
    GIF("image/gif", "gif", false),
    WEBP("image/webp", "webp", false),
    // vision 미지원 — 구 Slack 썸네일 대체는 TΔ16에서 사라졌다. iOS 피커가 업로드 시 JPEG로 변환한다.
    HEIC("image/heic", "heic", false),
    // 판별 불가 — 스테이징 금지, 안내로 수렴.
    UNKNOWN(null, null, false);

    private final String mimeType;
    private final String extension;
    private final boolean accepted;

    ImageFormat(String mimeType, String extension, boolean accepted) {
        this.mimeType = mimeType;
        this.extension = extension;
        this.accepted = accepted;
    }

    /**
     * 업로드로 받는 포맷인가(JPEG/PNG) — 스테이징 입구 게이트의 통과 기준(ADR-29, V-12).
     * <p>POLICY: 수용 포맷은 JPEG/PNG뿐이다 — 구 4종은 Slack 수신 포맷 폭에서 온 값이고 앱 업로드에는
     * 그 사정이 없다 (ref: changes/0029 tasks.md TΔ8a, 사용자 확정 2026-08-01).
     */
    public boolean isAccepted() {
        return accepted;
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
