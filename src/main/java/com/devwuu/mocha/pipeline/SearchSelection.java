package com.devwuu.mocha.pipeline;

import java.util.List;

/**
 * 검색 후보 선정 LLM 응답 — data-model §4.2 (ref: spec FR-20, plan.md#ADR-25, changes/0011).
 * <p>{@code candidate_slugs}는 관련도순 — 확신 단일 매치면 1개, 무후보면 빈 배열. 실존 slug 재검증(환각 필터)은
 * 서버({@link NoteSearchService}) 몫이다.
 *
 * @param candidateSlugs 후보 노트 slug 목록(snake_case {@code candidate_slugs}로 역직렬화).
 */
public record SearchSelection(List<String> candidateSlugs) {
}
