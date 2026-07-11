package com.devwuu.mocha.llm;

import java.util.List;

/**
 * 이미지 속 커피 정보 OCR·구조화 경계 (ref: specs/coffee-note-agent/plan.md#ADR-15, §3; spec NFR-4).
 * <p>공식 상품 페이지 상세 이미지처럼 <b>텍스트로 존재하지 않는</b> 스펙(원산지·가공·로스팅·공식 노트)을
 * vision으로 읽는다. 검색 2단계({@link OpenAiSearchClient})가 이 경계를 쓰고, 미래 "커피샵 제공 노트를
 * 사진으로 찍어 노트 생성" 유스케이스가 <b>같은 인터페이스를 그대로 재사용</b>한다(사용자 확정, NFR-4).
 * <p>구현({@link OpenAiVisionClient})의 vision SDK 타입은 구현체 안에만 존재한다 — 소비자는 이 계약만 본다
 * (plan §4 POLICY). 어떤 호출/형식 실패도 예외로 새지 않고 {@link VisionExtraction#empty()}로 수렴한다
 * (AC-Δ2, 2단계 실패는 1단계 결과로 진행).
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
