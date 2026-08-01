package com.devwuu.mocha.llm;

/**
 * 웹 검색 보강 경계 (ref: specs/coffee-note-agent/plan.md#ADR-5, NFR-4;
 * changes/0029-app-interface delta.md#D-16, tasks TΔ24b).
 * <p>계약: {@code search(query): SearchResult} — 커피 식별 정보로 웹을 검색해 빈 고정 필드·공식
 * 테이스팅 노트 후보를 돌려준다. 검색이 실패하거나 아무것도 못 찾으면 {@link SearchResult#empty()}로
 * 수렴한다 — 예외를 던지지 않고 빈 결과로 표현해, 상위({@code TurnProposalEnricher})가 사용자 입력만으로
 * 진행하게 한다(AC-12, plan §7 "검색 무결과/실패 → 해당 필드 빈 채로 진행").
 *
 * <p><b>왜 되살아났는가</b>: changes/0018이 이 경계를 폐기하고 보강을 <i>에이전트 루프 안의 모델 재량
 * tool</i>로 옮겼는데(ADR-49), 빠진 것이 «반드시 실행된다»는 성질 하나였고 그 결과 모델이 부르지 않는
 * 쪽을 택해 보강이 조용히 죽었다 — 실사용 11턴 {@code web_search} 0회(delta 0029 D-16 ①·②). 보강을
 * 다시 <b>결정론 단계</b>로 되돌리며 이 인터페이스가 그 자리를 받는다.
 *
 * <p>구 판본과 달라진 것: (a) 2단계(공식 페이지 fetch → 동일성 가드 → 이미지 OCR, 구 ADR-15)는
 * <b>되살리지 않는다</b> — 0018이 폐기한 복잡도라 내장 {@code web_search} 콜 1회로 대체한다(D-16 ④).
 * (b) 결과의 원두 축이 {@code origin}/{@code process} 두 문자열에서 <b>원두별 요소 배열</b>로 바뀌었다
 * (changes/0021 ADR-53 — "origin 쉼표 나열" 폐기).
 *
 * <p>POLICY: 상위 계층은 이 인터페이스 뒤에만 의존하고 OpenAI SDK 타입을 직접 참조하지 않는다
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
