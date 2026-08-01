package com.devwuu.mocha.domain;

import java.util.List;

/**
 * 갤러리 목록 조회의 좁히기 조건 — 검색어 + 필터 4축
 * (ref: changes/0029 tasks.md TΔ5a·TΔ12, open-questions.md §검색, 계약 정본
 * {@code src/test/resources/contract/note-list.contract.json}).
 *
 * <p><b>같은 축 안은 OR, 축 간은 AND</b>다(사용자 확정 2026-08-01) — <i>"프릳츠 아니면 모모스의 워시드"</i>가
 * 표현된다. 그래서 다중 축은 목록이고 단일 축은 스칼라다: 형태가 곧 결합 규칙이다.
 *
 * <p><b>{@code origin}만 자유 텍스트인 것은 스키마 사정이다</b> — ADR-53이 구 {@code origin} 필드를
 * {@code beans[]}로 대체하며 원산지가 {@code note_bean.description}의 자유 텍스트(<i>"에티오피아 예가체프
 * 헤어룸"</i>)에 녹았다. 열거할 값 집합이 애초에 없어 이 축만 부분일치로 근사한다.
 *
 * <p>POLICY: 날짜 범위 축은 두지 않는다 — 필요가 관측되면 확장한다
 * (ref: changes/0029 open-questions.md §검색 <i>"필터 축 4종 … 날짜 범위는 제외"</i>).
 *
 * @param query     갤러리 검색창의 원문. 빈 값이면 검색 축 없음.
 * @param roastery  로스터리 정확 일치(다중, OR). facet이 준 값에서 고른다.
 * @param process   가공방식 정확 일치(다중, OR) — 원두 <b>하나라도</b> 걸리면 그 노트가 남는다.
 * @param origin    원산지 부분일치(단일 자유 텍스트) — 대조 대상은 {@code note_bean.description}.
 * @param rating    평가 4범주(다중, OR) — 회차 <b>하나라도</b> 그 평가면 그 노트가 남는다(V-1).
 */
public record NoteFilter(
        String query,
        List<String> roastery,
        List<String> process,
        String origin,
        List<Rating> rating
) {

    // 축은 null 불가 — 파라미터가 통째로 빠진 요청(전건 조회)에서 빈 목록으로 수렴시킨다. 축마다 null
    // 검사를 다시 하면 "필터 없음"의 표현이 두 가지(null·빈 목록)가 되고 질의 조립부가 그것을 다 안다.
    public NoteFilter {
        roastery = roastery == null ? List.of() : List.copyOf(roastery);
        process = process == null ? List.of() : List.copyOf(process);
        rating = rating == null ? List.of() : List.copyOf(rating);
    }

    /** 좁히지 않는 조회 — 갤러리 첫 진입과 필터를 모두 푼 상태. */
    public static NoteFilter none() {
        return new NoteFilter(null, List.of(), List.of(), null, List.of());
    }

    /**
     * 검색어를 대조 기준으로 정규화한 사본 — 나머지 축은 그대로다.
     *
     * <p>{@code query}가 대조하는 컬럼({@code coffee_name_normalized}·{@code note_alias.normalized})은
     * {@link Aliases#normalize}가 만든 값이므로, 검색어도 <b>같은 함수</b>를 지나야 같은 기준에서 만난다
     * ({@code NoteTxService#findCandidates}와 같은 규율 — 규칙의 소유자를 하나로 둔다).
     *
     * <p>{@code origin}은 정규화하지 않는다 — 대조 대상이 정규화 컬럼이 아니라 표시값
     * ({@code note_bean.description})이라 같은 함수를 걸면 오히려 기준이 갈린다.
     */
    public NoteFilter normalized() {
        return new NoteFilter(Aliases.normalize(query), roastery, process, origin, rating);
    }
}
