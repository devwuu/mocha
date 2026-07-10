package com.devwuu.mocha.domain;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 노트 = 커피 1종 — {@code data/notes/<slug>.json} (ref: data-model.md#2.1, ADR-4).
 * <p>파생 값(엔트리 수 등)은 저장하지 않고 렌더 시 계산(POLICY, ADR-1).
 *
 * @param slug          kebab-case 식별자이자 파일명. 유일성 보장 (V-2).
 * @param coffeeName    표시용 커피 이름.
 * @param roastery      로스터리 — 출처 표시 필드.
 * @param origin        원산지 — 출처 표시 필드(단일 원산지 fallback은 source=search, FR-3/AC-16).
 * @param process       가공 방식 — 출처 표시 필드.
 * @param roastLevel    로스팅 정도 — 출처 표시 필드.
 * @param officialNotes 로스터리 전시 테이스팅 노트 — 로스터리 출처 한정(fallback 없음, FR-3/FR-7).
 * @param sources       검색 참조 링크 (FR-12).
 * @param entries       날짜별 시음 기록. 날짜 오름차순 유지.
 * @param createdAt     ISO-8601.
 * @param updatedAt     ISO-8601.
 */
public record Note(
        String slug,
        String coffeeName,
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
}
