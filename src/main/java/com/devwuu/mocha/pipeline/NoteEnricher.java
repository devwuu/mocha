package com.devwuu.mocha.pipeline;

import com.devwuu.mocha.domain.NoteMeta;
import com.devwuu.mocha.domain.Sourced;
import com.devwuu.mocha.llm.SearchClient;
import com.devwuu.mocha.llm.SearchQuery;
import com.devwuu.mocha.llm.SearchResult;

import java.util.ArrayList;
import java.util.List;

/**
 * 파이프라인 [4] — 사용자가 명시하지 않은 고정 필드와 official_notes를 웹 검색으로 보강한다
 * (ref: plan.md §1 [4], §3 enrich(draft): EnrichedDraft; spec FR-3).
 * <p>입력·출력은 "커피 1종의 사실" 묶음인 {@link NoteMeta}다 — slug/entries/타임스탬프가 붙기 전의 draft.
 * 빈 필드만 {@code source=search}로 채우고, 검색 참조 링크를 sources에 병합한다. fallback 규칙(단일 원산지
 * 일반 출처 보강, 블렌드 공란, official_notes 로스터리 출처 한정)은 {@link SearchClient} 구현체의 검색
 * 지침이 이미 적용해 {@link SearchResult}로 넘긴 것을 신뢰한다.
 * <p>POLICY: source=user 필드는 검색 값으로 덮어쓰지 않는다 — 빈 필드만 채운다 (ref: data-model.md#V-6, AC-3).
 * <p>검색 무결과/실패는 {@link SearchClient}가 {@link SearchResult#empty()}로 수렴시키므로, 그 경우
 * draft가 그대로 통과한다(AC-12).
 */
public class NoteEnricher {

    private final SearchClient searchClient;

    public NoteEnricher(SearchClient searchClient) {
        this.searchClient = searchClient;
    }

    /**
     * draft의 빈 고정 필드·official_notes를 검색으로 채운 사본을 돌려준다. 원본 draft는 변경하지 않는다.
     *
     * @param draft 사용자 언급분이 {@code source=user}로 마킹된 상태를 전제. 미언급 필드는 null.
     */
    public NoteMeta enrich(NoteMeta draft) {
        SearchResult result = searchClient.search(new SearchQuery(draft.coffeeName(), valueOf(draft.roastery())));

        return new NoteMeta(
                draft.coffeeName(),
                fill(draft.roastery(), result.roastery()),
                fill(draft.origin(), result.origin()),
                fill(draft.process(), result.process()),
                fill(draft.roastLevel(), result.roastLevel()),
                fillList(draft.officialNotes(), result.officialNotes()),
                mergeSources(draft.sources(), result.sources()));
    }

    // POLICY: 값이 이미 있는 필드(사용자 명시분 포함)는 검색 값으로 덮어쓰지 않는다 — 빈 필드만 채움(V-6/AC-3).
    private Sourced<String> fill(Sourced<String> current, String searched) {
        if (hasText(current)) {
            return current;
        }
        if (searched == null || searched.isBlank()) {
            return current; // 채울 것이 없으면 원래 상태(보통 null) 유지 → draft 그대로 통과(AC-12)
        }
        return Sourced.search(searched);
    }

    // official_notes: 이미 값이 있으면(사용자가 불러준 경우 등) 유지, 없으면 검색 결과로 채움.
    private Sourced<List<String>> fillList(Sourced<List<String>> current, List<String> searched) {
        if (current != null && current.value() != null && !current.value().isEmpty()) {
            return current;
        }
        if (searched == null || searched.isEmpty()) {
            return current;
        }
        return Sourced.search(List.copyOf(searched));
    }

    // 검색 참조 링크를 기존 sources 뒤에 병합하되 순서를 유지하며 중복 제거(FR-12).
    private List<String> mergeSources(List<String> current, List<String> searched) {
        List<String> merged = new ArrayList<>();
        if (current != null) {
            merged.addAll(current);
        }
        if (searched != null) {
            for (String s : searched) {
                if (s != null && !merged.contains(s)) {
                    merged.add(s);
                }
            }
        }
        return List.copyOf(merged);
    }

    private static boolean hasText(Sourced<String> field) {
        return field != null && field.value() != null && !field.value().isBlank();
    }

    private static String valueOf(Sourced<String> field) {
        return field == null ? null : field.value();
    }
}
