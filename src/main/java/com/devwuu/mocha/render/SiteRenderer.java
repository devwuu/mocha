package com.devwuu.mocha.render;

/**
 * 파이프라인 [7] — 정적 HTML 전체 리렌더 경계 (ref: plan.md §1 [7], §3; ADR-1, ADR-7).
 * <p>JSON(원본) + 사진만으로 note/index HTML을 전체 재생성한다. HTML은 파생물이며 삭제해도
 * 데이터 손실이 없어야 한다(NFR-3, AC-6). 저장 커밋 직후 트리거되고, 별도 CLI로도 실행된다(T5-1).
 * <p>갤러리 페이지는 폐기됐다 — 사진은 노트 상세에서만 열람(ref: plan.md#ADR-9).
 * <p>POLICY: 렌더러는 JSON 외 어떤 상태도 읽지 않는다 — 전체 리렌더 가능성 보장 (ref: plan.md#ADR-1, AC-6).
 * <p>구현: {@link ThymeleafSiteRenderer}(T5-1). CLI {@code --rerender}로도 실행된다({@code RerenderRunner}).
 */
public interface SiteRenderer {

    /** note/index 정적 HTML 전체 리렌더. */
    void renderAll();
}
