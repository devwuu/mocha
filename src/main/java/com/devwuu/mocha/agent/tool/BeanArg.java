package com.devwuu.mocha.agent.tool;

/**
 * 제안 tool 인자의 {@code beans} 배열 요소 — 원두 1종의 <b>미검증 원시 형태</b>
 * (ref: specs/coffee-note-agent/data-model.md#3.3, changes/0021 ADR-53).
 * <p>도메인 {@link com.devwuu.mocha.domain.Bean}과 달리 서브필드를 {@link SourcedArg}로 들어,
 * source enum 위반(V-5)을 역직렬화 예외가 아니라 검증 진입점({@code RecordProposalValidator}·
 * {@code EditProposalValidator})의 사유 있는 거부로 다룬다.
 * 빈 description 요소 드롭 등 V-14 정규화도 검증 단계의 몫이다.
 *
 * @param description 원산지·품종 등을 묶은 자유 텍스트(한국어 표기) — 비어 있으면 요소가 드롭된다(V-14).
 * @param process     그 원두의 가공방식(한국어 관용 표기) — 모르면 null.
 */
public record BeanArg(SourcedArg<String> description, SourcedArg<String> process) {
}
