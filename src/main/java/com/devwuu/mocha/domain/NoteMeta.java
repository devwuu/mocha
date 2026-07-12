package com.devwuu.mocha.domain;

import java.util.List;

/**
 * 노트 단위 메타 — Note에서 slug/entries/타임스탬프를 뺀 "커피 1종의 사실" 묶음.
 * <p>{@link com.devwuu.mocha.repository.NoteRepository#upsertEntry}가 신규 노트를 만들 때 쓴다
 * (ref: plan.md §3 upsertEntry(slug, noteMeta, entry)).
 *
 * @param coffeeName    표시용 커피 이름 — 출처 표시 필드(source ∈ {user, photo}, 검색 미채움, V-5).
 * @param roastery      로스터리 — 출처 표시 필드.
 * @param origin        원산지 — 출처 표시 필드.
 * @param process       가공 방식 — 출처 표시 필드.
 * @param roastLevel    로스팅 정도 — 출처 표시 필드.
 * @param officialNotes 로스터리 전시 테이스팅 노트 — 로스터리 출처 한정(FR-3/FR-7).
 * @param sources       검색 참조 링크 (FR-12).
 */
public record NoteMeta(
        Sourced<String> coffeeName,
        Sourced<String> roastery,
        Sourced<String> origin,
        Sourced<String> process,
        Sourced<String> roastLevel,
        Sourced<List<String>> officialNotes,
        List<String> sources
) {
}
