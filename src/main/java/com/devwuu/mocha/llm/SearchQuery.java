package com.devwuu.mocha.llm;

import java.util.Objects;

/**
 * 웹 검색 보강 요청 (ref: specs/coffee-note-agent/plan.md#ADR-5, §3 search(query); spec FR-3).
 * <p>SDK 중립적인 값객체 — 보강에 필요한 최소 식별 정보만 담는다. 단일/블렌드 fallback 판정과
 * official_notes 로스터리 출처 한정(FR-3/AC-16)은 구현체({@link OpenAiSearchClient})의 검색 지침이
 * 맡고, 이 값객체는 "무엇에 대한 커피인지"만 전달한다.
 *
 * @param coffeeName 표시용 커피 이름(검색 앵커). 필수.
 * @param roastery   로스터리(있으면). 로스터리 공식 출처 우선 탐색의 단서 — 널 허용.
 */
public record SearchQuery(String coffeeName, String roastery) {

    public SearchQuery {
        Objects.requireNonNull(coffeeName, "coffeeName");
    }
}
