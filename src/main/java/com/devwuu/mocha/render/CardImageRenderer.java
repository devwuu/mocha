package com.devwuu.mocha.render;

import java.nio.file.Path;

/**
 * 카드 HTML(시음 엔트리 1건) → JPG 래스터화 경계 (ref: plan.md#ADR-11, §3; changes/0002-instagram-share-card TΔ2).
 * <p>SiteRenderer가 엔트리 카드를 구울 때 이 경계 뒤로 렌더 엔진(Playwright/Chromium)을 숨긴다 — 카드 HTML은
 * 굽는 순간의 <b>중간 입력</b>이며 파일로 남기지 않는다(ADR-10). 산출물은 {@code cards/<slug>/<date>.jpg}뿐이다.
 * <p>POLICY: 파이프라인·SiteRenderer는 렌더 엔진(Playwright/Chromium) 타입을 직접 참조하지 않는다 —
 * 이 인터페이스 뒤에만 존재한다 (ref: plan.md#ADR-11 POLICY, NFR-4).
 * <p>구현: {@link PlaywrightCardImageRenderer}(헤드리스 Chromium). 다운스트림(TΔ4/TΔ5)은 fake로 대체해
 * 경로/링크 구조·배달 흐름을 실제 Chromium 없이 결정론적으로 검증한다.
 */
public interface CardImageRenderer {

    /**
     * 카드 HTML을 헤드리스 브라우저 뷰포트 {@code mocha.card.width}×{@code mocha.card.height}(4:5, 1080×1350)에서
     * JPG로 굽는다.
     * <p><b>base 경로 처리</b>: 카드가 참조하는 상대 자원(로컬 폰트·{@code thumbs/…} 썸네일·마스코트)이 해석되도록,
     * 렌더러는 임시 HTML을 {@code baseDir} 안에 써서 그 {@code file://} 위치가 상대 URL의 기준이 되게 한다.
     * 따라서 HTML의 상대 경로는 {@code baseDir}를 기준으로 작성한다.
     *
     * @param html    카드 HTML 문자열(엔트리 1건 렌더 결과). 빈 문자열/null 불가.
     * @param baseDir HTML의 상대 자원이 해석되는 기준 디렉터리(보통 site 루트). 없으면 생성한다.
     * @param out     출력 JPG 경로({@code cards/<slug>/<date>.jpg}). 부모 디렉터리는 렌더러가 생성한다.
     */
    void render(String html, Path baseDir, Path out);
}
