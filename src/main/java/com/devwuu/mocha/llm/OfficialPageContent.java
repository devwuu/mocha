package com.devwuu.mocha.llm;

import java.util.List;

/**
 * 공식 상품 페이지에서 수집한 2단계 입력 (ref: specs/coffee-note-agent/changes/0006-official-page-image-ocr,
 * plan.md#ADR-15).
 * <p>한국 로스터리 상품 상세의 스펙(원산지·가공·로스팅)은 통짜 상세 이미지 안에만 있어({@link OpenAiVisionClient}가
 * OCR), 페이지에서 두 가지를 함께 들고 나온다. {@code imageUrls}는 vision에 넘길 상세 이미지 URL(절대화·상한 적용),
 * {@code pageText}는 <b>페이지 동일성 가드</b>용 제목+본문 텍스트다 — 잘린 상세 이미지엔 상품명이 없을 수 있어
 * vision 호출 전 이 텍스트에 커피명이 포함되는지로 오상품(URL 환각)을 걸러낸다(ADR-15 동일성 가드, findings-TΔ0).
 * <p>어떤 2단계 실패(fetch 실패 등)도 예외로 새지 않고 {@link #empty()}로 수렴한다 — 1단계 결과로 진행(AC-Δ2).
 *
 * @param imageUrls 본문 상세 영역의 이미지 URL(절대 경로, 상한 적용). 없으면 빈 리스트.
 * @param pageText  동일성 가드용 페이지 텍스트(제목+본문). 없으면 빈 문자열.
 */
public record OfficialPageContent(List<String> imageUrls, String pageText) {

    public OfficialPageContent {
        imageUrls = imageUrls == null ? List.of() : List.copyOf(imageUrls);
        pageText = pageText == null ? "" : pageText;
    }

    /** fetch 실패 등으로 아무것도 수집 못 한 경우(AC-Δ2) — 이미지·텍스트 모두 공란. */
    public static OfficialPageContent empty() {
        return new OfficialPageContent(List.of(), "");
    }
}
