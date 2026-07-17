package com.devwuu.mocha.repository;

import com.devwuu.mocha.domain.Aliases;
import com.devwuu.mocha.domain.Entry;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.NoteMeta;

import java.time.LocalDate;
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
    default Note upsertEntry(String slug, NoteMeta meta, Entry entry) {
        return upsertEntry(slug, meta, entry, Aliases.empty());
    }

    /**
     * 별칭을 실어 엔트리를 병합 저장한다 (ref: plan.md#ADR-37, changes/0016).
     * <ul>
     *   <li>신규 노트(slug 부재)면 {@code aliases}를 그 노트의 초기 별칭으로 심는다
     *       — 신규 노트 첫 [저장] 시 {@link com.devwuu.mocha.llm.AliasGenerator}가 생성한 음차·이표기(TΔ2).</li>
     *   <li>기존 노트면 {@code aliases} 인자는 무시하고, {@code meta}의 커피명·로스터리 관측 표기를
     *       기존 별칭에 정규화 중복 제거로 무콜 축적한다(표시값과 같은 표기는 미추가, TΔ3, V-13).</li>
     * </ul>
     * 그 외 병합 규칙은 {@link #upsertEntry(String, NoteMeta, Entry)}와 동일하다.
     *
     * @param aliases 신규 노트에 심을 별칭(내부 전용, V-13). 부재 수렴은 {@link Aliases#empty()}.
     * @return 저장된 최종 노트.
     */
    Note upsertEntry(String slug, NoteMeta meta, Entry entry, Aliases aliases);

    /**
     * 수정 세션([저장]) 커밋 — 대상 엔트리를 draft 내용으로 갱신하고, 필요 시 날짜를 이동한다
     * (ref: plan.md#ADR-27, changes/0012).
     * <ul>
     *   <li>{@code draft}는 대상 엔트리 1건만 실은 Note 사본(data-model §2.3).
     *       노트 단위 필드(로스터리 등)도 draft 값으로 갱신한다 — 커피명 제외 전부가 수정 범위.</li>
     *   <li>coffee_name은 노트 생성 후 불변 — draft 값이 원본과 다르면 커밋을 거부한다 (V-9).</li>
     *   <li>draft 엔트리의 date가 {@code targetDate}와 다르면 날짜 이동 — 이동처 date에 기존 엔트리가
     *       있으면 그 엔트리를 덮어쓰고 원본 {@code targetDate} 엔트리는 제거한다(엔트리 총수 1 감소, V-10).
     *       날짜 오름차순 정렬은 유지된다.</li>
     * </ul>
     *
     * @param slug       수정 대상 노트 slug ({@code PendingNote.target.slug})
     * @param targetDate 수정 대상 원본 엔트리 date ({@code PendingNote.target.date})
     * @param draft      수정 반영이 끝난 draft — 대상 엔트리 1건 포함
     * @return 저장된 최종 노트.
     * @throws IllegalArgumentException coffee_name 변경 시도(V-9), 또는 draft 엔트리가 1건이 아니면.
     * @throws IllegalStateException    대상 노트 또는 {@code targetDate} 엔트리 소실 시
     *                                  (호출부가 만료 안내로 수렴, plan §7).
     */
    Note applyEdit(String slug, LocalDate targetDate, Note draft);
}
