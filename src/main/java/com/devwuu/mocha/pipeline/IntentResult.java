package com.devwuu.mocha.pipeline;

/**
 * 입구 의도 게이트 응답 (ref: data-model.md#4.1, plan.md#ADR-18, changes/0007).
 * <p>structured output 스키마가 강제하는 계약을 담는 값객체 — 원문의 의도가
 * 기록 요청({@code record})인지 그 외({@code other})인지 2분류 결과만 들어 있다.
 *
 * @param intent record(파이프라인 진입) / other(미진입 + 안내). 정의 외 값은 역직렬화 거부(V-1 정신).
 */
public record IntentResult(MessageIntent intent) {
}
