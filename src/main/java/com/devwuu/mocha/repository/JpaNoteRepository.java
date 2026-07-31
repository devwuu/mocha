package com.devwuu.mocha.repository;

import com.devwuu.mocha.domain.Aliases;
import com.devwuu.mocha.domain.Brew;
import com.devwuu.mocha.domain.Entry;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.NoteMeta;
import com.devwuu.mocha.repository.entity.BrewEntity;
import com.devwuu.mocha.repository.entity.EntryEntity;
import com.devwuu.mocha.repository.entity.NoteAliasEntity;
import com.devwuu.mocha.repository.entity.NoteBeanEntity;
import com.devwuu.mocha.repository.entity.NoteEntity;
import com.devwuu.mocha.repository.entity.NoteOfficialNoteEntity;
import com.devwuu.mocha.repository.entity.NoteSourceEntity;
import com.devwuu.mocha.repository.entity.RecipeEntity;
import com.devwuu.mocha.repository.entity.TastingEntity;
import com.devwuu.mocha.repository.jpa.NoteChildRows;
import com.devwuu.mocha.repository.jpa.NoteEntityRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Postgres 기반 NoteRepository (ref: changes/0028-rdb-storage/delta.md#ADR-72, tasks.md TΔ5a).
 *
 * <p>구 {@code JsonFileNoteRepository}를 대체한다(TΔ6a에서 폐기) — DB가 source of truth이고 카드·HTML은
 * 여전히 파생물이다(ADR-1의 "파생물은 언제든 전체 재생성 가능" 규칙은 불변).
 *
 * <p><b>저장소 3분할의 가운데</b>다(delta §ADR-72). 위로는 {@link NoteRepository} 포트를 구현해 상위
 * 계층에 도메인 record만 보이고(NFR-4, AC-Δ1), 아래로는 {@link NoteEntityRepository} <b>하나만</b>
 * 의존해 행을 넣고 뺀다. 이 클래스가 양쪽 타입을 아는 유일한 자리이며 소유하는 것은 셋이다 —
 * <b>정책</b>(ADR-4·59, V-9·V-13) · <b>도메인↔엔티티 변환</b>({@link NoteEntityMapper} 경유) ·
 * <b>3단 중첩 조립</b>.
 *
 * <p><b>관계는 여기서만 안다.</b> 엔티티에 연관 매핑이 없고(TΔ3a) DB에 FK도 없으므로(ADR-74) 아래 층은
 * 정렬된 평면 행 목록({@link NoteChildRows})까지만 준다. 부모별 그룹핑과
 * {@code note → entry → brew → recipe/tasting} 재구성이 이 클래스의 몫이고, 순서는 <b>재정렬하지 않고
 * 질의가 준 대로 보존</b>한다.
 *
 * <p><b>트랜잭션 경계는 이 클래스가 소유한다</b>(백엔드 CLAUDE.md §3의 파일 I/O 경계를 승계 — Q-11).
 * 외부 호출(LLM·검색·Slack)은 쓰기 진입 전에 끝나 있어야 한다. TΔ6a에서 {@code RepositoryConfig}가
 * 이 구현체를 {@link NoteRepository} 빈으로 올리면서 {@code @Transactional} 선언이 실제 프록시가 됐다.
 */
public class JpaNoteRepository implements NoteRepository {

    private static final Logger log = LoggerFactory.getLogger(JpaNoteRepository.class);

    private final NoteEntityRepository notes;

    public JpaNoteRepository(NoteEntityRepository notes) {
        this.notes = notes;
    }

    @Override
    @Transactional(readOnly = true)
    public List<Note> findAll() {
        return assemble(notes.findAllByOrderByIdAsc());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Note> findById(long id) {
        return notes.findById(id).map(row -> assemble(List.of(row)).getFirst());
    }

    /**
     * 신규 노트 INSERT — 노트 행·배열 4종·엔트리·회차·레시피·감상을 한 트랜잭션 안에서 심는다.
     *
     * <p>인자 {@code note}의 <b>식별자와 타임스탬프는 무시된다</b>: {@code id}는 {@code BIGSERIAL}이
     * 발급하고 {@code created_at}/{@code modified_at}은 Spring Data Auditing이 채운다(Q-5, TΔ4).
     * 그래서 반환값은 인자가 아니라 <b>다시 읽어 조립한</b> 노트다 — 저장이 실제로 무엇을 남겼는지가
     * 호출부가 들고 갈 값이다.
     *
     * <p>TΔ5b의 {@code upsertEntry}가 "노트 부재" 갈래에서 이 메서드를 그대로 쓴다.
     */
    @Transactional
    public Note insert(Note note) {
        // 자식이 부모 id를 평범한 컬럼으로 들기 때문에(ADR-74) id가 먼저 확정돼야 한다.
        long noteId = notes.saveAndFlush(NoteEntityMapper.toNoteEntity(note)).getId();

        notes.insertAll(NoteEntityMapper.toBeanEntities(noteId, note.beans()));
        notes.insertAll(NoteEntityMapper.toOfficialNoteEntities(noteId, note.officialNotes()));
        notes.insertAll(NoteEntityMapper.toAliasEntities(noteId, note.aliases()));
        notes.insertAll(NoteEntityMapper.toSourceEntities(noteId, note.sources()));
        for (Entry entry : note.entries()) {
            insertEntry(noteId, entry);
        }
        return findById(noteId).orElseThrow(() -> new IllegalStateException("방금 저장한 노트 조회 실패: " + noteId));
    }

    // POLICY: seq = 회차 번호. 구 스키마의 "배열 순서 = 회차 번호(별도 필드 없음)"를 컬럼으로 명시한다 —
    //         직렬화가 어긋나면 회차가 뒤섞이고 탐지할 방법이 없던 암묵 순서 의존이 여기서 사라진다
    //         (ref: data-model.md#2.2, changes/0028-rdb-storage/delta.md#동기, AC-Δ4).
    private void insertEntry(long noteId, Entry entry) {
        long entryId = notes.insertAndFlush(NoteEntityMapper.toEntryEntity(noteId, entry)).getId();
        List<Brew> brews = entry.brews();
        for (int seq = 0; seq < brews.size(); seq++) {
            long brewId = notes.insertAndFlush(new BrewEntity(entryId, seq)).getId();
            Brew brew = brews.get(seq);
            // 레시피·감상이 없는 회차는 행을 만들지 않는다 — 1:1 짝은 brew_id PK 공유로만 표현된다(V-15).
            RecipeEntity recipe = NoteEntityMapper.toRecipeEntity(brewId, brew.recipe());
            TastingEntity tasting = NoteEntityMapper.toTastingEntity(brewId, brew.tasting());
            notes.insertAll(Stream.of(recipe, tasting).filter(Objects::nonNull).toList());
        }
    }

    @Override
    public Note upsertEntry(Long noteId, NoteMeta meta, Entry entry, Aliases aliases) {
        throw new UnsupportedOperationException("TΔ5b에서 구현");
    }

    @Override
    public Note applyEdit(long noteId, LocalDate targetDate, Note draft) {
        throw new UnsupportedOperationException("TΔ5c에서 구현");
    }

    // ────────────────────────────── 조립 ──────────────────────────────

    /**
     * 노트 행 목록 → 도메인 노트 목록. 자식은 부모 id 목록으로 <b>한 번에</b> 받아 메모리에서 그룹핑한다 —
     * 노트가 몇 건이든 질의 수가 고정된다.
     */
    private List<Note> assemble(List<NoteEntity> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> noteIds = rows.stream().map(NoteEntity::getId).toList();
        NoteChildRows children = notes.findChildRows(noteIds);

        Map<Long, List<NoteBeanEntity>> beansByNote = groupBy(children.beans(), NoteBeanEntity::getNoteId);
        Map<Long, List<NoteOfficialNoteEntity>> officialNotesByNote =
                groupBy(children.officialNotes(), NoteOfficialNoteEntity::getNoteId);
        Map<Long, List<NoteAliasEntity>> aliasesByNote = groupBy(children.aliases(), NoteAliasEntity::getNoteId);
        Map<Long, List<NoteSourceEntity>> sourcesByNote = groupBy(children.sources(), NoteSourceEntity::getNoteId);
        Map<Long, List<Entry>> entriesByNote = assembleEntries(children);

        List<Note> assembled = new ArrayList<>(rows.size());
        for (NoteEntity row : rows) {
            long id = row.getId();
            Note domain = NoteEntityMapper.toNote(
                    row,
                    beansByNote.getOrDefault(id, List.of()),
                    officialNotesByNote.getOrDefault(id, List.of()),
                    aliasesByNote.getOrDefault(id, List.of()),
                    sourcesByNote.getOrDefault(id, List.of()),
                    entriesByNote.getOrDefault(id, List.of()));
            assembled.add(sanitized(domain, id));
        }
        return List.copyOf(assembled);
    }

    /** 엔트리 → 회차 → 레시피/감상 세 단을 노트별로 되묶는다. 짝짓기는 전부 부모 id 조회다(연관 없음). */
    private Map<Long, List<Entry>> assembleEntries(NoteChildRows children) {
        Map<Long, List<BrewEntity>> brewsByEntry = groupBy(children.brews(), BrewEntity::getEntryId);
        Map<Long, RecipeEntity> recipeByBrew = children.recipes().stream()
                .collect(Collectors.toMap(RecipeEntity::getBrewId, Function.identity()));
        Map<Long, TastingEntity> tastingByBrew = children.tastings().stream()
                .collect(Collectors.toMap(TastingEntity::getBrewId, Function.identity()));

        Map<Long, List<Entry>> byNote = new LinkedHashMap<>();
        for (EntryEntity entry : children.entries()) {
            List<Brew> brews = brewsByEntry.getOrDefault(entry.getId(), List.<BrewEntity>of()).stream()
                    .map(brew -> NoteEntityMapper.toBrew(recipeByBrew.get(brew.getId()), tastingByBrew.get(brew.getId())))
                    .toList();
            byNote.computeIfAbsent(entry.getNoteId(), key -> new ArrayList<>())
                    .add(NoteEntityMapper.toEntry(entry, brews));
        }
        return byNote;
    }

    /** 자식 행을 부모별로 묶는다 — 그룹 안의 순서는 질의가 준 순서 그대로다(재정렬하지 않는다). */
    private static <T> Map<Long, List<T>> groupBy(List<T> rows, Function<T, Long> parentOf) {
        return rows.stream().collect(Collectors.groupingBy(parentOf, LinkedHashMap::new, Collectors.toList()));
    }

    /**
     * 로드 경계 위생 (ref: plan.md#ADR-66, changes/0025).
     *
     * <p>POLICY: 저장소 <b>읽기 경계가 유일한 관문</b>이고, 그 관문은 이 한 곳이다 —
     * {@link #findAll}·{@link #findById} 모두 {@link #assemble}을 지나므로 조회 경로마다 정책이 갈라지지
     * 않는다. 스키마가 강제하지 못하는 규칙(V-14 빈 원두·V-15 빈 회차)은 psql 직접 편집으로 들어올 수
     * 있어 파일 시절과 같은 방어가 남는다. DB는 다시 쓰지 않는다 — 읽기 메모리 정규화만이며, 드롭분은
     * 그 노트의 다음 저장에서 반영되므로 경고 로그로 관측을 남긴다.
     *
     * <p>어디까지가 스키마 소관이고 어디부터 {@code Note.normalized()}에 남는지의 <b>범위 결론</b>은
     * TΔ5b가 소유한다(OQ-1) — 이 task는 적용 지점만 확정한다.
     */
    private Note sanitized(Note note, long id) {
        Note sanitized = note.normalized();
        if (sanitized != note) {
            log.warn("노트 로드 위생(ADR-66): 무효 요소 드롭 — note id={} (DB 직접 편집 위반 추정, DB는 다시 쓰지 않음. 드롭분은 다음 저장 시 반영됨)",
                    id);
        }
        return sanitized;
    }
}
