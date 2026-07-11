package com.devwuu.mocha.llm;

import java.util.List;

/**
 * 공식 페이지 상세 이미지 OCR·구조화 추출 결과 (ref: specs/coffee-note-agent/plan.md#ADR-15, §3 read(imageUrls, hint)).
 * <p>한국 로스터리 상품 상세 페이지의 스펙(원산지·가공·로스팅)은 통짜 상세 이미지 안에만 존재해 web_search
 * 텍스트 컨텍스트로는 읽히지 않는다 — 이 값객체는 그 이미지에서 vision(OCR)으로 읽어낸 후보 값 묶음이다.
 * 검색 2단계에서 {@link OpenAiSearchClient}가 1단계 fallback 값보다 우선 병합하고, 미래 "사진 기반 노트
 * 생성"도 같은 경계를 재사용한다(NFR-4). "추측 금지" — 이미지에서 확인 안 되는 값은 null(문자열)·빈 리스트로 온다.
 * <p>{@link SearchResult}와 달리 {@code sources}가 없다 — vision 입력은 공식 상품 페이지 이미지로 출처가
 * 고정이라 출처 URL은 상위(2단계 오케스트레이션)가 {@code official_page_url}로 넣는다.
 *
 * @param roastery      로스터리(이미지에서 확인된 값, 보통 null — 이미 문맥으로 주어짐).
 * @param origin        원산지(블렌드는 여러 원산지를 쉼표 문자열로 나열, ADR-14와 일관).
 * @param process       가공 방식.
 * @param roastLevel    로스팅 정도.
 * @param officialNotes 로스터리 공식 테이스팅 노트 — 이미지에서 확인 안 되면 빈 리스트.
 */
public record VisionExtraction(
        String roastery,
        String origin,
        String process,
        String roastLevel,
        List<String> officialNotes) {

    public VisionExtraction {
        officialNotes = officialNotes == null ? List.of() : List.copyOf(officialNotes);
    }

    /** 이미지에서 아무것도 못 읽은 경우(호출/형식 실패 포함, AC-Δ2) — 모든 필드 공란. */
    public static VisionExtraction empty() {
        return new VisionExtraction(null, null, null, null, List.of());
    }
}
