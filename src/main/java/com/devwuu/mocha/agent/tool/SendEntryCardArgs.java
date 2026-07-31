package com.devwuu.mocha.agent.tool;

/**
 * {@code send_entry_card} tool 인자 — 기존 시음 엔트리 카드 재전송(FR-20).
 * 그 엔트리의 회차 카드 파일({@code cards/<노트 폴더>/<date>-*.jpg} — 감상·레시피 전부, changes/0021) 존재 시
 * 그대로 postImage, 부재 시에만 증분 렌더 후 전송 — 검색 응답은 새 파생물을 최소화한다
 * (ref: specs/coffee-note-agent/data-model.md#3.5).
 *
 * @param noteId 대상 노트 id — 원시 String인 이유는 {@link GetNoteArgs#noteId()}와 같다(E-6).
 * @param date   대상 엔트리 날짜(YYYY-MM-DD).
 */
public record SendEntryCardArgs(String noteId, String date) {
}
