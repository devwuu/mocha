package com.devwuu.mocha.llm;

/**
 * 웹 검색 보강 경계 (ref: specs/coffee-note-agent/plan.md#ADR-5, NFR-4).
 * <p>계약: {@code search(query): SearchResult} — 커피 식별 정보로 웹을 검색해 빈 고정 필드·공식
 * 테이스팅 노트 후보를 돌려준다. 검색이 실패하거나 아무것도 못 찾으면 {@link SearchResult#empty()}로
 * 수렴한다 — 예외를 던지지 않고 빈 결과로 표현해, 상위(NoteEnricher)가 사용자 입력만으로 진행하게 한다
 * (AC-12, plan §7 "검색 무결과/실패 → 해당 필드 빈 채로 진행").
 * <p>POLICY: 파이프라인은 이 인터페이스 뒤에만 의존하고 OpenAI SDK 타입을 직접 참조하지 않는다
 * (ref: plan.md#ADR-5 POLICY, NFR-4). 검색 벤더 교체는 구현체 교체로 흡수한다. 구현: {@link OpenAiSearchClient}.
 */
public interface SearchClient {

    /**
     * 커피 정보로 웹 검색 보강. 못 찾은 필드는 결과에서 null/빈 리스트로 온다.
     *
     * @return 검색 후보 값 묶음. 실패·무결과도 예외가 아닌 {@link SearchResult#empty()}로(AC-12).
     */
    SearchResult search(SearchQuery query);
}
