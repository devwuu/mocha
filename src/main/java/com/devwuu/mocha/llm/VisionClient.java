package com.devwuu.mocha.llm;

import java.util.List;

/**
 * 이미지 속 커피 정보 OCR·구조화 경계 (ref: specs/coffee-note-agent/plan.md#ADR-15, §3; spec NFR-4).
 * <p>원두 봉투·커피 노트 카드·상품 상세 이미지처럼 <b>텍스트로 존재하지 않는</b> 스펙(원산지·가공·로스팅·
 * 공식 노트)을 vision으로 읽는다. 수신 사진 OCR 전처리({@code PhotoInfoExtractor}, ADR-23)가 이 경계를 쓰고,
 * 예비 {@code read_page_images} tool(ADR-49, 비활성)이 <b>같은 인터페이스를 그대로 재사용</b>한다(NFR-4).
 * <p>구현({@link OpenAiVisionClient})의 vision SDK 타입은 구현체 안에만 존재한다 — 소비자는 이 계약만 본다
 * (plan §4 POLICY). 어떤 호출/형식 실패도 예외로 새지 않고 {@link VisionExtraction#empty()}로 수렴한다
 * (OCR 실패는 첨부로만, 흐름 불변 — AC-28).
 */
public interface VisionClient {

    /**
     * 이미지에서 커피 정보를 읽어 구조화한다.
     *
     * @param imageUrls 상세 이미지 URL 목록(공식 페이지에서 수집). 빈 목록이면 {@link VisionExtraction#empty()}.
     * @param hint      어떤 로스터리·커피인지의 문맥 힌트(오독 감소).
     * @return 이미지에서 확인된 값 묶음 — 못 읽거나 실패하면 {@link VisionExtraction#empty()}.
     */
    VisionExtraction read(List<String> imageUrls, VisionHint hint);
}
