package com.devwuu.mocha.domain;

import java.util.List;

/**
 * 노트 단위 메타 — Note에서 slug/entries/타임스탬프를 뺀 "커피 1종의 사실" 묶음.
 * <p>{@link com.devwuu.mocha.repository.NoteRepository#upsertEntry}가 신규 노트를 만들 때 쓴다
 * (ref: plan.md §3 upsertEntry(slug, noteMeta, entry)).
 *
 * @param coffeeName    표시용 커피 이름 — 출처 표시 필드(source ∈ {user, photo}, 검색 미채움, V-5).
 * @param roastery      로스터리 — 출처 표시 필드.
 * @param beans         원두 구성 배열(원두별 설명·가공방식, 구 origin/process 대체 — changes/0021 ADR-53, V-14).
 * @param roastLevel    로스팅 정도 — 출처 표시 필드.
 * @param officialNotes 로스터리 전시 테이스팅 노트 — 로스터리 출처 한정(FR-3/FR-7).
 * @param sources       검색 참조 링크 (FR-12).
 */
public record NoteMeta(
        Sourced<String> coffeeName,
        Sourced<String> roastery,
        List<Bean> beans,
        Sourced<String> roastLevel,
        Sourced<List<String>> officialNotes,
        List<String> sources
) {
    // V-14 준용: beans는 null 불가 — 정보 전무면 빈 배열.
    public NoteMeta {
        beans = beans == null ? List.of() : List.copyOf(beans);
    }
}
