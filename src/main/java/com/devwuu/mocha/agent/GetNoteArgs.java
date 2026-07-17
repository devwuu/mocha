package com.devwuu.mocha.agent;

/**
 * {@code get_note} tool 인자 — 노트 전체 조회(읽기).
 * 응답은 도메인 {@link com.devwuu.mocha.domain.Note} 전체(엔트리 포함)이고, 미존재 slug는 오류 반환이다
 * — 실존하지 않는 노트를 대상으로 제안이 진행되지 않게 하는 환각 필터
 * (ref: specs/coffee-note-agent/data-model.md#3.2).
 */
public record GetNoteArgs(String slug) {
}
