package com.devwuu.mocha.agent.tool;

/**
 * {@code send_entry_card} tool 인자 — 기존 시음 엔트리 카드 재전송(FR-20).
 * 카드 파일({@code cards/<slug>/<date>.jpg}) 존재 시 그대로 postImage, 부재 시에만 증분 렌더 후 전송
 * — 검색 응답은 새 파생물을 최소화한다 (ref: specs/coffee-note-agent/data-model.md#3.5).
 *
 * @param slug 대상 노트.
 * @param date 대상 엔트리 날짜(YYYY-MM-DD).
 */
public record SendEntryCardArgs(String slug, String date) {
}
