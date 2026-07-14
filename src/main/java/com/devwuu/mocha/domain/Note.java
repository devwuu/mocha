package com.devwuu.mocha.domain;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 노트 = 커피 1종 — {@code data/notes/<slug>.json} (ref: data-model.md#2.1, ADR-4).
 * <p>파생 값(엔트리 수 등)은 저장하지 않고 렌더 시 계산(POLICY, ADR-1).
 *
 * @param slug          kebab-case 식별자이자 파일명. 유일성 보장 (V-2).
 * @param coffeeName    표시용 커피 이름 — 출처 표시 필드(source ∈ {user, photo}, 검색 미채움, V-5).
 * @param roastery      로스터리 — 출처 표시 필드.
 * @param origin        원산지 — 출처 표시 필드(단일 원산지 fallback은 source=search, FR-3/AC-16).
 * @param process       가공 방식 — 출처 표시 필드.
 * @param roastLevel    로스팅 정도 — 출처 표시 필드.
 * @param officialNotes 로스터리 전시 테이스팅 노트 — 로스터리 출처 한정(fallback 없음, FR-3/FR-7).
 * @param aliases       내부 매칭·검색 전용 별칭 — 렌더·미리보기 미표시(V-13, changes/0016, ADR-37).
 * @param sources       검색 참조 링크 (FR-12).
 * @param entries       날짜별 시음 기록. 날짜 오름차순 유지.
 * @param createdAt     ISO-8601.
 * @param updatedAt     ISO-8601.
 */
public record Note(
        String slug,
        Sourced<String> coffeeName,
        Sourced<String> roastery,
        Sourced<String> origin,
        Sourced<String> process,
        Sourced<String> roastLevel,
        Sourced<List<String>> officialNotes,
        Aliases aliases,
        List<String> sources,
        List<Entry> entries,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {
    /**
     * aliases 없이 노트를 만드는 생성자 — 별칭은 빈 값으로 채운다.
     * <p>별칭은 커밋 경로(AliasGenerator·관측 축적, changes/0016)에서만 세팅되므로,
     * 신규 노트 생성 등 대다수 생성부는 이 생성자로 노트를 만든다.
     * (기존 노트 JSON은 삭제·재생성하므로 aliases 부재 허용은 두지 않는다 — ADR-28 관례.)
     */
    public Note(
            String slug,
            Sourced<String> coffeeName,
            Sourced<String> roastery,
            Sourced<String> origin,
            Sourced<String> process,
            Sourced<String> roastLevel,
            Sourced<List<String>> officialNotes,
            List<String> sources,
            List<Entry> entries,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
        this(slug, coffeeName, roastery, origin, process, roastLevel, officialNotes,
                Aliases.empty(), sources, entries, createdAt, updatedAt);
    }
}
