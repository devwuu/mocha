package com.devwuu.mocha.render;

/**
 * 파이프라인 [7] — 정적 HTML 전체 리렌더 경계 (ref: plan.md §1 [7], §3; ADR-1, ADR-7).
 * <p>JSON(원본) + 사진만으로 note/index/gallery HTML을 전체 재생성한다. HTML은 파생물이며 삭제해도
 * 데이터 손실이 없어야 한다(NFR-3, AC-6). 저장 커밋 직후 트리거되고, 별도 CLI로도 실행된다(T5-1).
 * <p>POLICY: 렌더러는 JSON 외 어떤 상태도 읽지 않는다 — 전체 리렌더 가능성 보장 (ref: plan.md#ADR-1, AC-6).
 * <p>실제 Thymeleaf 구현은 T5-1이 채운다. T3-5 시점 구현체는 {@link LoggingSiteRenderer}(임시)로,
 * 커밋 후 트리거가 배선됐는지만 검증한다.
 */
public interface SiteRenderer {

    /** note/index/gallery 정적 HTML 전체 리렌더. */
    void renderAll();
}
