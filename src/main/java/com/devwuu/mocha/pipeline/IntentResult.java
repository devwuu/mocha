package com.devwuu.mocha.pipeline;

/**
 * 입구 의도 게이트 응답 (ref: data-model.md#4.1, plan.md#ADR-24, changes/0011).
 * <p>structured output 스키마가 강제하는 계약을 담는 값객체 — 원문의 의도
 * 5분류({@code record}/{@code revise}/{@code search}/{@code end}/{@code other}) 결과만 들어 있다.
 *
 * @param intent 5값 의도 판정(ADR-24). 정의 외 값은 역직렬화 거부(V-1 정신).
 */
public record IntentResult(MessageIntent intent) {
}
