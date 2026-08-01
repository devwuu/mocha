package com.devwuu.mocha.web;

/**
 * 아카이브 상대 경로 → 클라이언트가 {@code <img src>}에 그대로 꽂는 URL
 * (ref: changes/0029 tasks.md TΔ5a·TΔ12·TΔ13a, 계약 {@code thumbnail_url_prefix}·{@code photo_url_prefix}).
 *
 * <p><b>이 규칙의 소유자는 서버 하나다</b>. 클라이언트가 {@code photos/…}(V-4)를 받아 조립하면 같은 규칙이
 * 양쪽에 이중화되고, 폴더 접미가 생성 시점 스냅샷이라(ADR-75) 클라이언트가 재계산할 수 있는 값도 아니다.
 * 목록의 썸네일과 상세의 사진이 <b>같은 접두</b>를 쓰는 것도 여기가 한 자리이기 때문이다 — 갈리면
 * 갤러리에서 보던 사진과 상세의 히어로가 다른 규칙으로 만들어진다.
 *
 * <p><b>바이트를 돌려주는 쪽과 짝이다</b>: {@code WebConfig}가 이 접두를 아카이브 디렉터리에 매핑한다.
 * 상대 경로가 이미 {@code photos/}로 시작하므로 붙는 것은 {@code /api/}뿐이고, 그래서 URL의 나머지가
 * 아카이브 안의 경로와 <b>글자 그대로 같다</b> — 어긋날 자리가 없다.
 */
final class PhotoUrl {

    /** 사진 서빙 URL 접두 — {@code WebConfig}의 리소스 핸들러 패턴과 짝이다. */
    static final String PREFIX = "/api/photos/";

    /** V-4 상대 경로의 접두 — 이 뒤가 아카이브 안의 경로다. */
    private static final String ARCHIVE_PREFIX = "photos/";

    private PhotoUrl() {
    }

    /**
     * @param relativePath {@code photos/}로 시작하는 아카이브 상대 경로. {@code null}이면 {@code null}
     *                     (사진 없는 노트가 정상 상태다 — 화면이 그 자리를 플레이스홀더로 채운다).
     */
    static String of(String relativePath) {
        if (relativePath == null) {
            return null;
        }
        // 규약을 어긴 경로는 URL로 만들지 않는다 — 만들면 서빙 핸들러가 못 찾아 화면에 깨진 이미지가 뜨고,
        // 원인이 DB 값에 있다는 사실이 URL 뒤로 가려진다. null이면 "사진 없음"으로 정확히 그려진다.
        if (!relativePath.startsWith(ARCHIVE_PREFIX)) {
            return null;
        }
        return "/api/" + relativePath;
    }
}
