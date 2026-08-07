package com.devwuu.mocha.agent.tool;

/**
 * {@code get_note} tool 인자 — 노트 전체 조회(읽기).
 * 응답은 도메인 {@link com.devwuu.mocha.domain.Note} 전체(시음일 포함)이고, 미존재 노트는 오류 반환이다
 * — 실존하지 않는 노트를 대상으로 제안이 진행되지 않게 하는 환각 필터
 * (ref: specs/coffee-note-agent/data-model.md#3.2).
 *
 * @param noteId 대상 노트 id — <b>원시 String</b>이다. 스키마는 정수를 요구하지만 위반 값(비숫자 문자열)이
 *               역직렬화 예외로 새면 사유 있는 tool 오류 결과를 돌려줄 수 없다 — 파싱·거부는
 *               {@link ToolSupport#resolveNote}가 미존재 id와 같은 부류로 수렴시킨다
 *               (changes/0028 TΔ0b §4 E-6, ADR-45).
 */
public record GetNoteArgs(String noteId) {
}
