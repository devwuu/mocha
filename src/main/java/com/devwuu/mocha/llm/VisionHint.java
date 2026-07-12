package com.devwuu.mocha.llm;

/**
 * vision OCR 문맥 힌트 (ref: specs/coffee-note-agent/plan.md#ADR-15, §3 read(imageUrls, hint)).
 * <p>SDK 중립적인 값객체 — 어떤 로스터리·커피의 상세 이미지인지 모델에 문맥으로 알려 오독을 줄인다.
 * 잘린 상세 이미지에는 상품명·로스터리명이 없을 수 있어 이미지만으로는 대상을 특정하기 어렵기 때문이다
 * (findings-TΔ0). 페이지 동일성 가드(커피명 포함 확인)는 이 힌트가 아니라 2단계 오케스트레이션이 맡는다.
 * <p>검색 2단계는 커피명을 알고 호출하지만, 수신 사진 OCR(FR-19, changes/0010)은 <b>사진-only 흐름</b>에서
 * 커피명을 모른 채(사진으로 읽어야) 호출한다 — 그래서 {@code coffeeName}은 nullable이다(ADR-23).
 *
 * @param coffeeName 커피 이름 — 알면 문맥으로 준다. 널 허용(사진-only 흐름).
 * @param roastery   로스터리(있으면). 널 허용.
 */
public record VisionHint(String coffeeName, String roastery) {
}
