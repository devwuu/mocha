package com.devwuu.mocha.llm;

import java.util.List;

/**
 * 웹 검색 보강 결과 (ref: specs/coffee-note-agent/plan.md#ADR-5, §3 search(query): SearchResult; spec FR-3).
 * <p>검색이 찾아낸 후보 값 묶음 — 아직 출처 마킹·병합 전이다. 어느 필드를 draft에 실제로 반영할지는
 * {@link com.devwuu.mocha.pipeline.NoteEnricher}가 정한다(source=user 불변, V-6). 여기 담긴 값은 전부
 * "검색이 말하는 것"이며, 못 찾은 필드는 null(문자열)·빈 리스트로 온다.
 * <p>fallback 규칙(단일 원산지→일반 출처 보강, 블렌드→공란, official_notes 로스터리 출처 한정,
 * FR-3/AC-16)은 구현체의 검색 지침이 이미 적용한 뒤의 결과다 — 즉 블렌드의 origin 등은 여기서 null.
 *
 * @param roastery      로스터리(검색이 확인/보강한 값, 보통 null — 이미 사용자 언급).
 * @param origin        원산지(단일 원산지 fallback 포함, 블렌드는 null).
 * @param process       가공 방식.
 * @param roastLevel    로스팅 정도.
 * @param officialNotes 로스터리 전시 테이스팅 노트 — 로스터리 출처 없으면 빈 리스트(FR-3).
 * @param sources       참조 링크(FR-12).
 */
public record SearchResult(
        String roastery,
        String origin,
        String process,
        String roastLevel,
        List<String> officialNotes,
        List<String> sources) {

    public SearchResult {
        officialNotes = officialNotes == null ? List.of() : List.copyOf(officialNotes);
        sources = sources == null ? List.of() : List.copyOf(sources);
    }

    /** 검색이 아무것도 못 찾은 경우(AC-12) — 모든 필드 공란. */
    public static SearchResult empty() {
        return new SearchResult(null, null, null, null, List.of(), List.of());
    }
}
