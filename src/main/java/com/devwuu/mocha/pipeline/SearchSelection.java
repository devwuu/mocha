package com.devwuu.mocha.pipeline;

import java.util.List;

/**
 * 검색 후보 선정 LLM 응답 — data-model §4.2 (ref: spec FR-20, plan.md#ADR-25, changes/0011).
 * <p>{@code candidate_slugs}는 관련도순 — 확신 단일 매치면 1개, 무후보면 빈 배열. 실존 slug 재검증(환각 필터)은
 * 서버({@link NoteSearchService}) 몫이다. 수정 의도 신호(FR-21, changes/0012)도 같은 턴 1콜에 실린다 —
 * 별도 감지 콜을 만들지 않는다(right-sizing, findings-TΔ0 Q1).
 *
 * @param candidateSlugs 후보 노트 slug 목록(snake_case {@code candidate_slugs}로 역직렬화).
 * @param editRequested  사용자가 찾은 기록을 수정하고 싶다는 의도 감지(FR-21). 대상은 candidateSlugs가 가리킨다.
 * @param editTargetDate 날짜 목록 선택 대기 상태에서 사용자가 고른 엔트리 날짜(YYYY-MM-DD, AC-42).
 *                       선택 답변이 아니면 null. 제시 목록 내 실존 검증은 서버 몫(환각 필터와 동일 정신).
 */
public record SearchSelection(List<String> candidateSlugs, boolean editRequested, String editTargetDate) {

    /** 수정 신호 없는 순수 후보 선정 — 0012 이전 호출부 형태 유지. */
    public SearchSelection(List<String> candidateSlugs) {
        this(candidateSlugs, false, null);
    }
}
