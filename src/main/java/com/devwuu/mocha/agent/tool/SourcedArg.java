package com.devwuu.mocha.agent.tool;

/**
 * 제안 tool 인자의 출처 표시 필드 — {@code { "value": ..., "source": "user"|"photo"|"search"|null }}의
 * <b>미검증 원시 형태</b>. 도메인 {@link com.devwuu.mocha.domain.Sourced}와 달리 source를 String으로 들고,
 * enum 위반(V-5)을 역직렬화 예외가 아니라 {@link ProposalValidator}의 <b>사유 있는 거부</b>로 다루기 위한
 * 계약 타입이다 (ref: specs/coffee-note-agent/data-model.md#3.3, plan#ADR-45).
 *
 * @param value  필드 값 — 미언급이면 null(추측 금지, FR-2).
 * @param source 에이전트 자기 보고 출처(ADR-45). value가 null이면 함께 null.
 */
public record SourcedArg<T>(T value, String source) {
}
