package com.devwuu.mocha.pipeline;

/**
 * 선행 사진 OCR(FR-19, changes/0016 ADR-36)이 읽은 식별 정보 — 추출 요청의 {@code photo_hint}로 주입된다
 * (ref: data-model.md#3). 텍스트에 커피명이 없어 온 발화라도 사진이 채운 식별 재료를 LLM이 {@code matched_slug}
 * 판정에 쓰게 한다(사진-only 정체성 매칭, AC-Δ3). coffee_name·roastery 응답 필드에 그대로 실리지는 않는다 —
 * 사진 값의 draft 병합은 서버 오버레이(V-6)가 맡고, 이 힌트는 매칭 단서일 뿐이다.
 * <p>사진 없음·OCR 실패·무정보면 상위({@link com.devwuu.mocha.slack.SlackRecordFlow})가 {@code null}을
 * 넘긴다 — 힌트 없이 종전 추출 흐름 그대로 진행한다(AC-28 불변).
 *
 * @param coffeeName 사진에서 읽은 커피 이름(없으면 null).
 * @param roastery   사진에서 읽은 로스터리(없으면 null).
 */
public record PhotoHint(String coffeeName, String roastery) {
}
