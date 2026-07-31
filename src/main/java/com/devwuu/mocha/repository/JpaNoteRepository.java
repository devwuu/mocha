package com.devwuu.mocha.repository;

import com.devwuu.mocha.domain.Aliases;
import com.devwuu.mocha.domain.Brew;
import com.devwuu.mocha.domain.Entry;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.NoteMeta;
import com.devwuu.mocha.domain.Sourced;
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
import java.util.Set;
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
        insertBrews(entryId, entry.brews());
    }

    private void insertBrews(long entryId, List<Brew> brews) {
        for (int seq = 0; seq < brews.size(); seq++) {
            long brewId = notes.insertAndFlush(new BrewEntity(entryId, seq)).getId();
            Brew brew = brews.get(seq);
            // 레시피·감상이 없는 회차는 행을 만들지 않는다 — 1:1 짝은 brew_id PK 공유로만 표현된다(V-15).
            RecipeEntity recipe = NoteEntityMapper.toRecipeEntity(brewId, brew.recipe());
            TastingEntity tasting = NoteEntityMapper.toTastingEntity(brewId, brew.tasting());
            notes.insertAll(Stream.of(recipe, tasting).filter(Objects::nonNull).toList());
        }
    }

    /**
     * 날짜 엔트리 병합 저장 — 계약은 {@link NoteRepository#upsertEntry}가 소유하고, 여기서는 그 정책이
     * 행으로 어떻게 떨어지는지만 다룬다.
     *
     * <p><b>신규/기존 분기는 {@code noteId == null} 하나</b>다(D-1). id를 발급하는 것은 {@code BIGSERIAL}
     * 이므로 저장 전 draft에는 식별자가 없고, 그 부재가 그대로 "아직 저장되지 않음"을 뜻한다 — 구
     * {@code nextAvailableSlug}가 하던 "신규 대체키 발급"이라는 개념 자체가 사라졌다(ADR-74).
     *
     * <p><b>노트 단위 메타는 갱신하지 않는다.</b> 기존 노트면 {@code meta}에서 쓰는 것은 관측 표기(커피명·
     * 로스터리)뿐이고 원두 구성·로스팅·공식 노트는 보존한다 — 재기록은 그날의 엔트리를 쌓는 일이지 커피의
     * 사실을 다시 쓰는 일이 아니다(ADR-4, V-13).
     */
    @Override
    @Transactional
    public Note upsertEntry(Long noteId, NoteMeta meta, Entry entry, Aliases aliases) {
        if (noteId == null) {
            return insert(newNote(meta, entry, aliases));
        }
        // 소실은 조용히 신규 생성으로 흡수하지 않는다 — 그러면 매칭이 지목한 노트와 별개의 중복 노트가
        // 생기고 사진·카드가 두 id로 갈린다. FK가 없어 DB가 막아주지 않는 자리다(ADR-74).
        Note existing = findById(noteId)
                .orElseThrow(() -> new IllegalStateException("병합 대상 노트 소실: " + noteId));
        replaceEntry(noteId, entry);
        accumulateAliases(noteId, existing, meta);
        return findById(noteId).orElseThrow(() -> new IllegalStateException("방금 저장한 노트 조회 실패: " + noteId));
    }

    /** 신규 노트 — 식별자·타임스탬프는 저장이 발급하므로 비워 둔다({@link #insert} 계약). */
    private static Note newNote(NoteMeta meta, Entry entry, Aliases aliases) {
        return new Note(
                null, meta.coffeeName(), meta.roastery(), meta.beans(), meta.roastLevel(),
                meta.officialNotes(), aliases == null ? Aliases.empty() : aliases, meta.sources(),
                List.of(entry), null, null);
    }

    // POLICY: 같은 date 엔트리는 갱신만 — 하루 2엔트리 금지, 다회 시도는 brews 회차로. 갱신은 엔트리 통째
    //         교체이며 서버는 회차 단위 병합을 하지 않는다 — 에이전트가 구성한 배열(V-15 검증 통과분)을
    //         그대로 신뢰한다 (ref: data-model.md#2.2, AC-14, plan.md#ADR-4·59).
    // 행 재사용이 아니라 삭제 후 재삽입인 것은 "통째 교체"의 직역이다 — 엔트리에는 필드 단위 갱신 개념이
    // 없어 수정 메서드도 두지 않았다(TΔ3b). 날짜 오름차순 정렬은 조회 질의가 소유한다(재정렬하지 않는다).
    private void replaceEntry(long noteId, Entry entry) {
        notes.findEntryId(noteId, entry.date()).ifPresent(notes::deleteEntry);
        insertEntry(noteId, entry);
    }

    /**
     * 관측 표기 무콜 축적 (ref: plan.md#ADR-37 축적 ②, V-13, changes/0016).
     *
     * <p>이번 기록의 커피명·로스터리 표기가 노트 표시값과 다르면 별칭으로 더한다 — LLM 콜 없이 서버가
     * 쌓는 갈래다. 판정({@code Aliases.accumulate})은 도메인이 소유하고 이 메서드는 <b>늘어난 표기만</b>
     * 행으로 옮긴다: 기존 행을 지우고 다시 넣으면 id가 밀려 첫 등장 순서가 무너진다(별칭에는 seq가 없고
     * 순서를 id가 진다, TΔ3a).
     */
    private void accumulateAliases(long noteId, Note existing, NoteMeta meta) {
        Aliases before = existing.aliases();
        Aliases accumulated = before.accumulate(
                Sourced.valueOrNull(meta.coffeeName()), Sourced.valueOrNull(existing.coffeeName()),
                Sourced.valueOrNull(meta.roastery()), Sourced.valueOrNull(existing.roastery()));
        Aliases added = new Aliases(
                newcomers(before.coffeeName(), accumulated.coffeeName()),
                newcomers(before.roastery(), accumulated.roastery()));
        notes.insertAll(NoteEntityMapper.toAliasEntities(noteId, added));
    }

    /** 축적 결과에서 기존에 없던 표기만 — 대조 기준은 정규화 키다(표시 형태가 아니라, V-13). */
    private static List<String> newcomers(List<String> before, List<String> after) {
        Set<String> known = before.stream().map(Aliases::normalize).collect(Collectors.toSet());
        return after.stream().filter(alias -> !known.contains(Aliases.normalize(alias))).toList();
    }

    /**
     * 수정 세션 커밋 — 계약은 {@link NoteRepository#applyEdit}가 소유하고, 여기서는 그 정책이 행으로 어떻게
     * 떨어지는지만 다룬다.
     *
     * <p><b>검증 셋을 먼저 통과시킨다</b>(노트 존재 → V-9 → draft 엔트리 1건 → 대상 엔트리 존재). 어느
     * 하나라도 걸리면 <b>쓰기를 시작하기 전에</b> 던지므로 부분 반영이 남지 않는다 — 트랜잭션 롤백에
     * 기대지 않아도 되는 형태다.
     *
     * <p>대상 엔트리 존재는 조회한 도메인이 아니라 <b>행으로</b> 확인한다({@code findEntry}). 로드 경계
     * 위생(ADR-66)이 회차 0개 엔트리를 드롭할 수 있어(V-15) 도메인으로 판정하면 <b>행은 있는데 소실로
     * 보이는</b> 갈래가 생긴다 — 고칠 대상이 무엇인지는 행이 답한다.
     *
     * <p><b>수정은 지우고 다시 넣는 것이 아니라 고치는 것이다.</b> 노트 본문과 엔트리를 관리되는 엔티티로
     * 꺼내 필드를 바꾸면 flush가 UPDATE로 내보낸다 — 재기록({@code upsertEntry})이 엔트리를 통째 교체하는
     * 것과 갈리는 지점이고, 그래서 {@code entry.created_at}이 보존되고 {@code modified_at}이 실제 수정
     * 시각으로 움직인다. 논리 FK({@code note_id})로 찾을 뿐 연관 매핑은 여전히 없다(TΔ3a).
     */
    @Override
    @Transactional
    public Note applyEdit(long noteId, LocalDate targetDate, Note draft) {
        NoteEntity row = notes.findById(noteId)
                .orElseThrow(() -> new IllegalStateException("수정 대상 노트 소실: " + noteId));
        // POLICY: coffee_name은 노트 생성 후 불변 — 수정 세션에서도 변경 불가, 다른 값이면 커밋 거부
        //         (ref: specs/coffee-note-agent/data-model.md#V-9).
        if (!Objects.equals(row.getCoffeeName().value(), Sourced.valueOrNull(draft.coffeeName()))) {
            throw new IllegalArgumentException("coffee_name은 노트 생성 후 불변(V-9): " + noteId);
        }
        if (draft.entries().size() != 1) {
            throw new IllegalArgumentException(
                    "edit draft는 대상 엔트리 1건만 담는다(data-model §2.3): " + draft.entries().size() + "건");
        }
        EntryEntity target = notes.findEntry(noteId, targetDate)
                .orElseThrow(() -> new IllegalStateException("수정 대상 엔트리 소실: " + noteId + " " + targetDate));

        Entry edited = draft.entries().getFirst();
        moveEntry(noteId, target, targetDate, edited.date());
        replaceBrews(target.getId(), edited.brews());
        updateNoteFields(noteId, row, draft);
        return findById(noteId).orElseThrow(() -> new IllegalStateException("방금 저장한 노트 조회 실패: " + noteId));
    }

    // POLICY: 날짜 이동으로 이동처 date에 기존 엔트리가 있으면 그 엔트리를 덮어쓰고 원본 date는 제거한다 —
    //         엔트리 총수 1 감소. 경고 표기는 미리보기(V-10 전반부)의 몫
    //         (ref: specs/coffee-note-agent/data-model.md#V-10).
    // 이동은 행 교체가 아니라 tasted_on UPDATE다 — 같은 기록이 다른 날짜에 놓이는 것이므로 행이 살아남아야
    // created_at이 보존된다(교체였다면 매번 새로 발급된다 — 재기록 경로가 치르는 대가, TΔ5b).
    // 이동처를 먼저 비우는 순서는 파일 구현과 갈리는 지점이다: 파일은 목록을 메모리에서 재조립해 한 번에
    // 썼지만 DB는 중간 상태에서 UNIQUE(note_id, tasted_on)를 검사하고, Hibernate의 flush 순서가
    // INSERT → UPDATE → DELETE라 em.remove()에 맡기면 이동 UPDATE가 아직 살아 있는 행과 부딪힌다.
    private void moveEntry(long noteId, EntryEntity target, LocalDate targetDate, LocalDate movedTo) {
        if (movedTo.equals(targetDate)) {
            return;
        }
        notes.findEntryId(noteId, movedTo).ifPresent(notes::deleteEntry);
        target.updateTastedOn(movedTo);
    }

    /**
     * 회차 통째 교체 — 엔트리 행은 살려 두고 그 아래만 갈아끼운다.
     *
     * <p>회차에는 필드 단위 갱신 개념이 없다(ADR-59 — 서버는 회차 단위 병합을 하지 않고 에이전트가 구성한
     * 배열을 신뢰한다). 삭제가 벌크라 즉시 나가므로 {@code UNIQUE(entry_id, seq)}가 새 회차보다 먼저 풀린다.
     */
    private void replaceBrews(long entryId, List<Brew> brews) {
        notes.deleteBrews(entryId);
        insertBrews(entryId, brews);
    }

    /**
     * 노트 단위 필드 갱신 — <b>커피명을 제외한 전부</b>가 수정 범위다(FR-21). 배열 3종은 통째 교체하고,
     * <b>별칭은 건드리지 않는다</b> — 수정 세션에 별칭 갱신이라는 개념이 없다(V-13, 원본 존치).
     *
     * <p>노트 본문은 관리되는 엔티티의 필드 갱신이라 dirty checking이 UPDATE를 내보내고, 그때
     * {@code modified_at}을 감사 리스너가 채운다(TΔ4). <b>flush를 손으로 걸지 않는다</b> — 뒤따르는 조립
     * 질의가 이 트랜잭션의 미반영 쓰기와 겹쳐 auto-flush를 유발하고, Hibernate의 flush는 컨텍스트 전체를
     * 대상으로 하므로 노트 UPDATE도 함께 나간다. 영속성 관리는 JPA에 맡기고 이 클래스는 <b>무엇을
     * 고칠지</b>만 정한다.
     */
    private void updateNoteFields(long noteId, NoteEntity row, Note draft) {
        NoteEntityMapper.updateNoteEntity(row, draft);
        notes.deleteNoteArraysExceptAliases(noteId);
        notes.insertAll(NoteEntityMapper.toBeanEntities(noteId, draft.beans()));
        notes.insertAll(NoteEntityMapper.toOfficialNoteEntities(noteId, draft.officialNotes()));
        notes.insertAll(NoteEntityMapper.toSourceEntities(noteId, draft.sources()));
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
     * <p><b>범위 결론(OQ-1, TΔ5b 실측)</b>: 정규화는 <b>축소하지 않는다</b>. 스키마와 겹치는 것은 V-8 수치
     * 5종뿐이고 그마저 판정 방향이 반대다 — CHECK는 저장 <b>전체를 거부</b>하고 정규화는 <b>그 항목만
     * 드롭</b>한다. 나머지(공백 문자열 접기, 빈 구조체 드롭, 자식 없는 회차 드롭)는 {@code TEXT NOT NULL}이
     * {@code ''}를 통과시키는 한 CHECK로 표현되지 않는다. 근거 표는 delta.md 열린 질문 OQ-1이 소유한다.
     *
     * <p>그래서 <b>쓰기 경로에는 걸지 않는다</b> — 저장소가 정규화를 걸면 V-8 위반이 조용히 드롭돼
     * AC-Δ3("위반 삽입 실패")이 관측 불가가 된다. 정규화 진입점은 종전대로 검증기다.
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
