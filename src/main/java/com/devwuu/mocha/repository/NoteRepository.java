package com.devwuu.mocha.repository;

import com.devwuu.mocha.domain.Entry;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.NoteMeta;

import java.util.List;
import java.util.Optional;

/**
 * 노트(커피 1종) 저장소 — 파일시스템 접근을 파이프라인에서 격리 (ref: plan.md#ADR-8).
 * <p>구현: {@link JsonFileNoteRepository}. 미래 호스팅형 전환 시 구현체 교체로 대응(NFR-4).
 */
public interface NoteRepository {

    /** 저장된 모든 노트. slug 오름차순(결정적 순서). */
    List<Note> findAll();

    /** slug로 노트 조회. 없으면 빈 Optional. */
    Optional<Note> findBySlug(String slug);

    /**
     * 신규 노트 생성 시 충돌하지 않는 slug 반환 (ref: data-model.md#V-2).
     * <p>{@code base}가 비어 있으면 그대로, 이미 존재하면 {@code base-2}, {@code base-3} … 로 증가.
     * 신규 노트에만 쓴다 — 기존 노트 매칭은 {@link #upsertEntry}에 정확한 slug를 넘긴다.
     *
     * @throws IllegalArgumentException base가 slug 형식({@code [a-z0-9-]+})이 아니면.
     */
    String nextAvailableSlug(String base);

    /**
     * 날짜 엔트리 병합 저장 (ref: plan.md#ADR-4, [6]).
     * <ul>
     *   <li>slug 노트가 없으면 {@code meta}로 새 노트를 만들고 {@code entry}를 첫 엔트리로 둔다.</li>
     *   <li>있으면 같은 date 엔트리는 갱신(덮어쓰기), 다른 date는 추가 후 날짜 오름차순 정렬한다.</li>
     * </ul>
     * POLICY: 같은 날짜 엔트리는 갱신만 — 하루 2엔트리 금지 (ref: data-model.md#2.2, AC-14).
     *
     * @return 저장된 최종 노트.
     */
    Note upsertEntry(String slug, NoteMeta meta, Entry entry);
}
