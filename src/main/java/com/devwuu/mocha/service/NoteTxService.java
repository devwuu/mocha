package com.devwuu.mocha.service;

import com.devwuu.mocha.domain.Aliases;
import com.devwuu.mocha.domain.Brew;
import com.devwuu.mocha.domain.Entry;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.NoteCandidate;
import com.devwuu.mocha.domain.NoteMeta;
import com.devwuu.mocha.domain.Sourced;
import com.devwuu.mocha.repository.NoteEntityMapper;
import com.devwuu.mocha.repository.NoteFolderName;
import com.devwuu.mocha.repository.entity.BrewEntity;
import com.devwuu.mocha.repository.entity.EntryEntity;
import com.devwuu.mocha.repository.entity.NoteEntity;
import com.devwuu.mocha.repository.entity.NotePhotoEntity;
import com.devwuu.mocha.repository.entity.RecipeEntity;
import com.devwuu.mocha.repository.entity.TastingEntity;
import com.devwuu.mocha.repository.jpa.NoteEntityRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 노트 유스케이스 중 <b>한 트랜잭션 안이어야 하는 것</b>
 * (ref: 백엔드 CLAUDE.md §2·§3, changes/0029-app-interface/delta.md#D-9, tasks TΔ4c).
 *
 * <p><b>기준이 곧 이름이다.</b> 구 {@code JpaNoteRepository}가 이 자리에 있었고 {@code @Transactional}도
 * 거기 붙어 있었다 — 이미 트랜잭션 경계였는데 이름만 저장소였다. 그 결과 D-8이 그은 축
 * (<i>"판단은 service로, 행 조율은 저장소에"</i>)이 검증 불가능해져 비즈니스 로직이 두 층으로 흩어졌다:
 * V-9는 위에, <b>V-10은 아래에</b> 있었는데 둘 다 {@code data-model.md}의 V 규칙이다. 축을 트랜잭션으로
 * 다시 그으면 그 물음이 <b>예/아니오로 답이 나온다</b>(delta D-9).
 *
 * <p><b>여기 사는 것</b> — 원자적이어야 하는 규칙 전부:
 * <ul>
 *   <li><b>V-9</b> 커피명 불변 — 저장된 값과 대조하고 다르면 거부({@link #updateMeta})</li>
 *   <li><b>V-10</b> 날짜 이동 시 이동처 덮어쓰기 = 엔트리 총수 1 감소({@link #replaceEntry})</li>
 *   <li><b>V-13</b> 관측 표기 축적 — 기존 별칭을 읽어 더할 것만 계산({@link #commit})</li>
 *   <li><b>ADR-4·59</b> 같은 날짜는 갱신만(하루 2엔트리 금지, AC-14), 엔트리 통째 교체</li>
 *   <li>대상 소실 검사 — 쓰기를 시작하기 전에 던져 부분 반영을 남기지 않는다</li>
 *   <li>날짜 이동의 UNIQUE 중간 상태 등 행 순서 조율(FK 부재 — ADR-75)</li>
 * </ul>
 *
 * <p><b>여기 없는 것</b>: ① <b>외부 호출</b>(LLM·검색·파일) — {@link NoteService}가 쓰기 진입 <b>전에</b>
 * 끝내고 결과값만 넘긴다. 이 분리가 백엔드 CLAUDE.md §3(<i>"트랜잭션 안에서 외부 호출 금지"</i>)을 규칙이
 * 아니라 <b>계층으로</b> 지키는 방식이다. ② <b>도메인↔엔티티 변환·3단 조립·로드 경계 위생</b> —
 * 순수 함수라 트랜잭션과 무관해 {@link NoteEntityMapper}가 소유한다(TΔ4c).
 *
 * <p><b>트랜잭션 경계는 이 클래스가 소유한다.</b> 공개 메서드 하나가 한 트랜잭션이고, 노트 행·배열 4종·
 * 엔트리·회차·레시피·감상이 전부 심기거나 전부 안 심긴다. 위({@link NoteService}·컨트롤러)에
 * {@code @Transactional}을 걸어 경계를 늘리지 않는다 — 늘리는 순간 외부 호출이 들어올 수 있는 구간이 생긴다.
 *
 * <p>아래로는 {@link NoteEntityRepository} <b>하나만</b> 의존한다. 엔티티에 연관 매핑이 없고(0028 TΔ3a)
 * DB에 FK도 없으므로(ADR-75) 그 층은 정렬된 평면 행 목록까지만 주고, <b>관계를 아는 곳은 질의 하나</b>다.
 *
 * <p>클래스와 공개 메서드가 {@code final}이 아닌 것은 둘 때문이다 — 트랜잭션 프록시(CGLIB)가 이 타입을
 * 상속하고, 상위 계층 테스트의 손 fake도 같은 자리를 쓴다(Mockito 미도입 방침, TΔ4b).
 */
public class NoteTxService {

    private final NoteEntityRepository notes;

    public NoteTxService(NoteEntityRepository notes) {
        this.notes = notes;
    }

    // ────────────────────────────── 조회 ──────────────────────────────

    /** 저장된 모든 노트. id 오름차순(결정적 순서). */
    @Transactional(readOnly = true)
    public List<Note> findAll() {
        return assemble(notes.findAllByOrderByIdAsc());
    }

    /** id로 노트 조회. 없으면 빈 Optional. */
    @Transactional(readOnly = true)
    public Optional<Note> findById(long id) {
        return notes.findById(id).map(row -> assemble(List.of(row)).getFirst());
    }

    /**
     * 매칭 후보 검색 — 변경 시트가 쓰는 조회 (ref: changes/0029 tasks.md TΔ7·TΔ11).
     *
     * <p><b>검색어를 여기서 정규화하는 것이 이 메서드의 실질이다</b>: 대조 대상인
     * {@code coffee_name_normalized}·{@code note_alias.normalized} 컬럼은 {@link Aliases#normalize}가
     * 만든 값이므로, 검색어도 <b>같은 함수</b>를 지나야 같은 기준에서 만난다. 다른 데서 소문자화·공백
     * 제거를 다시 짜면 그 순간 두 기준이 갈린다 — 규칙의 소유자를 하나로 두는 자리다.
     *
     * <p>정규화가 곧 표기 흔들림 흡수다: {@code "예가체프 G1"}·{@code "예가체프g1"}·{@code "예가체프 g1"}이
     * 전부 같은 키({@code 예가체프g1})로 수렴한다.
     *
     * <p>결과는 {@link Note}가 아니라 납작한 사영이라 자식 8질의를 지나지 않는다 — 근거는
     * {@link NoteCandidate} javadoc이 소유한다. 정렬 규칙과 그 근거는
     * {@code NoteEntityRepositoryCustom#findCandidates}가 소유한다.
     *
     * @param query 사용자가 친 검색어 원문. null·공백이면 전건을 계약 정렬로 돌려준다.
     */
    @Transactional(readOnly = true)
    public List<NoteCandidate> findCandidates(String query) {
        return notes.findCandidates(Aliases.normalize(query));
    }

    // ────────────────────────────── 커밋 ──────────────────────────────

    /**
     * 폼 확정 저장 — 날짜 엔트리 병합 (ref: plan.md#ADR-4·37·59, changes/0016, AC-4).
     * <ul>
     *   <li>{@code noteId}가 {@code null}이면 {@code meta}로 새 노트를 만들고 {@code entry}를 첫 엔트리로 둔다
     *       — id는 INSERT가 발급하므로 저장 전 draft는 식별자를 갖지 않는다(0028 D-1).</li>
     *   <li>있으면 같은 date 엔트리는 갱신(엔트리 통째 교체), 다른 date는 추가 후 날짜 오름차순 정렬한다.</li>
     *   <li>같은 date 갱신에서 회차 append·기존 회차 지칭 병합은 에이전트가 구성한 {@code entry.brews}
     *       배열(V-15 검증 통과분)을 신뢰한다 — 서버는 회차 단위 병합을 하지 않는다(changes/0021 ADR-59).</li>
     * </ul>
     * POLICY: 같은 날짜 엔트리는 갱신만 — 하루 2엔트리 금지, 다회 시도는 brews 회차로
     * (ref: data-model.md#2.2, AC-14, ADR-4·59).
     * <p>POLICY: 노트 단위 메타는 갱신하지 않는다 — 기존 노트면 {@code meta}에서 쓰는 것이 없다. 원두 구성·
     * 로스팅·공식 노트는 보존한다. 재기록은 그날의 엔트리를 쌓는 일이지 커피의 사실을 다시 쓰는 일이
     * 아니다(ADR-4). 사실을 고치는 경로는 {@link #updateMeta}다.
     *
     * <p><b>별칭(V-13)은 두 갈래이고 그 판정이 여기 있다</b>(0029 TΔ4c): {@code generated}가 있으면 그것을
     * 심고, 없으면 <b>기존 노트를 읽어 관측 표기 축적분을 계산</b>한다. 축적을 여기 둔 것이 트랜잭션 이득의
     * 실체다 — 무엇이 이미 별칭에 있는지 읽고 그에 따라 쓰는 read-then-act가 같은 트랜잭션 안에 든다
     * (TΔ4a가 명시적으로 지고 있던 대가가 닫히는 자리).
     *
     * @param noteId    기존 노트 id. {@code null}이면 신규 생성.
     * @param generated 별칭 <b>생성</b> 결과(LLM 1콜, ADR-37). 생성하지 않았으면 {@code null} —
     *                  그 경우 기존 노트에서 축적분을 계산한다. 외부 콜은 {@link NoteService}가 이미 끝냈다.
     * @return 저장된 최종 노트.
     */
    @Transactional
    public Note commit(Long noteId, NoteMeta meta, Entry entry, Aliases generated) {
        if (noteId == null) {
            return insert(newNote(meta, entry, generated));
        }
        // 소실은 조용히 신규 생성으로 흡수하지 않는다 — 그러면 매칭이 지목한 노트와 별개의 중복 노트가
        // 생기고 사진·카드가 두 id로 갈린다. FK가 없어 DB가 막아주지 않는 자리다(ADR-75).
        Note existing = findById(noteId)
                .orElseThrow(() -> new IllegalStateException("병합 대상 노트 소실: " + noteId));

        replaceEntryRows(noteId, entry);
        notes.insertAll(NoteEntityMapper.toAliasEntities(
                noteId, generated != null ? generated : accumulated(existing, meta)));
        return reload(noteId);
    }

    /**
     * 이 저장으로 <b>더할</b> 관측 표기 — 기존 별칭에 없던 것만 (V-13, ADR-37 축적 ①·②).
     *
     * <p>기존 행을 지우고 다시 넣지 않는 이유가 계약이다: 별칭에는 {@code seq}가 없어 순서가 곧 id 순서라
     * 재삽입하면 첫 등장 순서가 무너진다(0028 TΔ3a).
     */
    private static Aliases accumulated(Note existing, NoteMeta meta) {
        Aliases before = existing.aliases();
        Aliases after = before.accumulate(
                Sourced.valueOrNull(meta.coffeeName()), Sourced.valueOrNull(existing.coffeeName()),
                Sourced.valueOrNull(meta.roastery()), Sourced.valueOrNull(existing.roastery()));
        return new Aliases(
                newcomers(before.coffeeName(), after.coffeeName()),
                newcomers(before.roastery(), after.roastery()));
    }

    /** 대조 기준은 정규화 키다 — 표시 형태가 달라도 같은 표기면 더하지 않는다(V-13). */
    private static List<String> newcomers(List<String> before, List<String> after) {
        Set<String> known = before.stream().map(Aliases::normalize).collect(Collectors.toSet());
        return after.stream().filter(alias -> !known.contains(Aliases.normalize(alias))).toList();
    }

    /**
     * 신규 노트 INSERT — 노트 행·배열 4종·엔트리·회차·레시피·감상을 한 트랜잭션 안에서 심는다.
     *
     * <p>인자 {@code note}의 <b>식별자와 타임스탬프는 무시된다</b>: {@code id}는 {@code BIGSERIAL}이
     * 발급하고 {@code created_at}/{@code modified_at}은 Spring Data Auditing이 채운다(0028 Q-5).
     * 그래서 반환값은 인자가 아니라 <b>다시 읽어 조립한</b> 노트다 — 저장이 실제로 무엇을 남겼는지가
     * 호출부가 들고 갈 값이다.
     */
    @Transactional
    public Note insert(Note note) {
        // 자식이 부모 id를 평범한 컬럼으로 들기 때문에(ADR-75) id가 먼저 확정돼야 한다.
        long noteId = notes.saveAndFlush(NoteEntityMapper.toNoteEntity(note)).getId();

        notes.insertAll(NoteEntityMapper.toBeanEntities(noteId, note.beans()));
        notes.insertAll(NoteEntityMapper.toOfficialNoteEntities(noteId, note.officialNotes()));
        notes.insertAll(NoteEntityMapper.toAliasEntities(noteId, note.aliases()));
        notes.insertAll(NoteEntityMapper.toSourceEntities(noteId, note.sources()));
        for (Entry entry : note.entries()) {
            insertEntry(noteId, entry);
        }
        return reload(noteId);
    }

    /** 신규 노트 — 식별자·타임스탬프는 저장이 발급하므로 비워 둔다({@link #insert} 계약). */
    private static Note newNote(NoteMeta meta, Entry entry, Aliases aliases) {
        return new Note(
                null, meta.coffeeName(), meta.roastery(), meta.beans(), meta.roastLevel(),
                meta.officialNotes(), aliases == null ? Aliases.empty() : aliases, meta.sources(),
                List.of(entry), null, null);
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

    // 행 재사용이 아니라 삭제 후 재삽입인 것은 "통째 교체"의 직역이다 — 엔트리에는 필드 단위 갱신 개념이
    // 없어 수정 메서드도 두지 않았다(0028 TΔ3b). 날짜 오름차순 정렬은 조회 질의가 소유한다(재정렬하지 않는다).
    private void replaceEntryRows(long noteId, Entry entry) {
        notes.findEntryId(noteId, entry.date()).ifPresent(notes::deleteEntry);
        insertEntry(noteId, entry);
    }

    // ────────────────────────────── 사진 색인 ──────────────────────────────

    /**
     * 아카이브로 옮겨진 사진을 노트에 잇는다 — {@code note_photo} 행 추가
     * (ref: changes/0029 tasks.md TΔ8b, plan.md#ADR-79).
     *
     * <p><b>이 메서드는 노트 커밋과 같은 트랜잭션에 들 수 없다.</b> 사진의 최종 경로는 노트 id가 발급된
     * 뒤에야 정해지고(폴더 접미가 {@code <id>-…}, ADR-75) 그 사이에 파일 이동이라는 외부 IO가 낀다 —
     * 트랜잭션 안에 넣으려면 §3이 금지하는 것을 해야 한다. 그래서 <b>커밋 다음 트랜잭션</b>이고, 그 사이
     * 실패는 "파일은 아카이브에 있는데 색인이 없다"로 수렴한다(파일이 정본이라 사진 유실이 아니다).
     *
     * <p>{@code seq}는 그 (노트, 날짜)에 이미 붙은 수에서 이어진다 — 같은 날짜 재저장은 엔트리를 통째로
     * 교체하지만(ADR-4·59) <b>사진은 쌓는다</b>. 파일이 아카이브에 그대로 남아 있고 그것을 지우는 것은
     * 재기록의 뜻이 아니다.
     *
     * @param paths {@code photos/}로 시작하는 상대 경로(V-4 어휘), {@code PhotoStore.commit}이 준 순서.
     */
    @Transactional
    public void attachPhotos(long noteId, LocalDate tastedOn, List<String> paths) {
        if (paths == null || paths.isEmpty()) {
            return;
        }
        int seq = (int) notes.countPhotos(noteId, tastedOn);
        List<NotePhotoEntity> rows = new ArrayList<>(paths.size());
        for (String path : paths) {
            rows.add(new NotePhotoEntity(noteId, tastedOn, seq++, path));
        }
        notes.insertAll(rows);
    }

    /**
     * 그 노트에 딸린 사진 경로 전부 — {@code (날짜, seq)} 오름차순.
     * <p>지금 읽는 곳은 둘이다: 삭제 시 지울 파일 목록, 그리고 <b>덧붙일 때 폴더 접미를 되읽는 자리</b>
     * ({@link NoteFolderName#from}). 화면이 사진을 읽는 모양은 TΔ5a·TΔ12가 정한다.
     */
    @Transactional(readOnly = true)
    public List<String> photoPaths(long noteId) {
        return notes.findPhotoPaths(noteId);
    }

    // ────────────────────────────── 수정 ──────────────────────────────

    /**
     * 노트 단위 메타 갱신 — <b>커피명을 제외한 전부</b>가 대상이다(FR-21, AC-5). 배열 3종(원두·공식 노트·
     * 참조 링크)은 통째 교체하고, <b>별칭과 엔트리는 건드리지 않는다</b>.
     *
     * <p>POLICY: coffee_name은 노트 생성 후 불변 — 다른 값을 실은 요청은 거부한다
     * (ref: data-model.md#V-9). <b>이 검사가 이 델타에서 V-9의 유일한 자동 가드</b>다: 구 가드는
     * {@code propose_edit} patch 스키마의 구조 차단이었고 TΔ1에서 tool과 함께 사라졌다. 저장된 값을 읽어
     * 대조하고 그에 따라 쓰는 read-then-act이고, <b>같은 트랜잭션 안</b>이라 검사와 쓰기 사이가 벌어지지
     * 않는다(TΔ4c — TΔ4a가 이 대가를 명시적으로 지고 있었다).
     *
     * <p>아래 층에는 커피명을 덮어쓸 수단이 아예 없다({@code NoteEntity}에 setter 부재) — 구조 차단 한 겹은
     * 그대로 남아 이 검사와 이중이다.
     *
     * <p>0029 TΔ4a: 구 {@code applyEdit}이 "노트 메타 + 대상 엔트리 1건"을 한 번에 받던 것을 둘로 갈랐다
     * — 수정 세션(pending mode=edit)이 폐기되며 "엔트리를 반드시 1건 실어야 한다"는 전제가 근거를 잃었고,
     * 로스터리만 고치는 데 엔트리를 딸려 보내면 그 회차 행이 이유 없이 재발급됐다.
     *
     * <p><b>수정은 지우고 다시 넣는 것이 아니라 고치는 것이다.</b> 노트 본문을 관리되는 엔티티로 꺼내
     * 필드를 바꾸면 flush가 UPDATE로 내보낸다 — 그래서 {@code created_at}이 보존되고 {@code modified_at}이
     * 실제 수정 시각으로 움직인다. 배열 자식만 별도 테이블이라 통째 교체된다.
     *
     * @throws IllegalArgumentException 커피명 변경 시도(V-9).
     * @throws IllegalStateException    대상 노트 소실 시(호출부가 안내로 수렴, plan §7).
     */
    @Transactional
    public Note updateMeta(long noteId, NoteMeta meta) {
        NoteEntity row = notes.findById(noteId)
                .orElseThrow(() -> new IllegalStateException("수정 대상 노트 소실: " + noteId));
        Sourced<String> stored = NoteEntityMapper.toSourced(row.getCoffeeName());
        if (!Objects.equals(Sourced.valueOrNull(stored), Sourced.valueOrNull(meta.coffeeName()))) {
            throw new IllegalArgumentException("coffee_name은 노트 생성 후 불변(V-9): " + noteId);
        }
        updateNoteFields(noteId, row, meta);
        return reload(noteId);
    }

    /**
     * 노트 단위 필드 갱신 — 배열 3종은 통째 교체하고 <b>별칭은 건드리지 않는다</b>(V-13, 원본 존치 —
     * 메타 수정에 별칭 갱신이라는 개념이 없다).
     *
     * <p>노트 본문은 관리되는 엔티티의 필드 갱신이라 dirty checking이 UPDATE를 내보내고, 그때
     * {@code modified_at}을 감사 리스너가 채운다(0028 TΔ4). <b>flush를 손으로 걸지 않는다</b> — 뒤따르는
     * 조립 질의가 이 트랜잭션의 미반영 쓰기와 겹쳐 auto-flush를 유발하고, Hibernate의 flush는 컨텍스트
     * 전체를 대상으로 하므로 노트 UPDATE도 함께 나간다.
     */
    private void updateNoteFields(long noteId, NoteEntity row, NoteMeta meta) {
        NoteEntityMapper.updateNoteEntity(row, meta);
        notes.deleteNoteArraysExceptAliases(noteId);
        notes.insertAll(NoteEntityMapper.toBeanEntities(noteId, meta.beans()));
        notes.insertAll(NoteEntityMapper.toOfficialNoteEntities(noteId, meta.officialNotes()));
        notes.insertAll(NoteEntityMapper.toSourceEntities(noteId, meta.sources()));
    }

    /**
     * 엔트리 교체 — {@code targetDate} 엔트리의 회차를 {@code entry}의 것으로 갈아끼우고, 필요하면 날짜를
     * 옮긴다 (ref: plan.md#ADR-27·ADR-59, V-10, changes/0012).
     * <ul>
     *   <li>{@code entry.date()}가 {@code targetDate}와 다르면 날짜 이동 — 이동처 date에 기존 엔트리가
     *       있으면 그 엔트리를 덮어쓰고 원본 {@code targetDate} 엔트리는 제거한다(엔트리 총수 1 감소, V-10).
     *       날짜 오름차순 정렬은 유지된다.</li>
     *   <li>엔트리 <b>행은 살아남는다</b> — 이동은 삭제 후 재삽입이 아니라 {@code tasted_on} 갱신이라
     *       {@code created_at}이 보존된다(재기록 경로 {@link #commit}과 갈리는 지점).</li>
     * </ul>
     *
     * <p><b>검증 셋을 먼저 통과시킨다</b>(노트 존재 → 대상 엔트리 존재). 어느 하나라도 걸리면 <b>쓰기를
     * 시작하기 전에</b> 던지므로 부분 반영이 남지 않는다.
     *
     * <p>대상 엔트리 존재는 조회한 도메인이 아니라 <b>행으로</b> 확인한다({@code findEntry}). 로드 경계
     * 위생(ADR-66)이 회차 0개 엔트리를 드롭할 수 있어(V-15) 도메인으로 판정하면 <b>행은 있는데 소실로
     * 보이는</b> 갈래가 생긴다 — 고칠 대상이 무엇인지는 행이 답한다.
     *
     * @throws IllegalStateException 대상 노트 또는 {@code targetDate} 엔트리 소실 시
     *                               (호출부가 안내로 수렴, plan §7).
     */
    @Transactional
    public Note replaceEntry(long noteId, LocalDate targetDate, Entry entry) {
        if (notes.findById(noteId).isEmpty()) {
            throw new IllegalStateException("수정 대상 노트 소실: " + noteId);
        }
        EntryEntity target = notes.findEntry(noteId, targetDate)
                .orElseThrow(() -> new IllegalStateException("수정 대상 엔트리 소실: " + noteId + " " + targetDate));

        moveEntry(noteId, target, targetDate, entry.date());
        replaceBrews(target.getId(), entry.brews());
        return reload(noteId);
    }

    // POLICY: 날짜 이동으로 이동처 date에 기존 엔트리가 있으면 그 엔트리를 덮어쓰고 원본 date는 제거한다 —
    //         엔트리 총수 1 감소. 경고 표기는 미리보기(V-10 전반부)의 몫
    //         (ref: specs/coffee-note-agent/data-model.md#V-10).
    // 이동은 행 교체가 아니라 tasted_on UPDATE다 — 같은 기록이 다른 날짜에 놓이는 것이므로 행이 살아남아야
    // created_at이 보존된다(교체였다면 매번 새로 발급된다 — 재기록 경로가 치르는 대가).
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

    // ────────────────────────────── 삭제 ──────────────────────────────

    /**
     * 노트 한 건 삭제 — 하위 행(엔트리·회차·레시피·감상·배열 4종)을 <b>남기지 않는다</b>
     * (ref: changes/0028-rdb-storage/delta.md#삭제-정책, AC-Δ8).
     *
     * <p>POLICY: hard delete — {@code deleted_at} 계열 컬럼을 두지 않는다. 1인용 개인 기록이라 복구 요구가
     * 관측된 적이 없고, soft delete는 <b>모든 조회에 조건을 얹는다</b>(ref: 0028 delta#삭제-정책, Q-3·Q-12).
     *
     * <p>없는 {@code id}는 무해하게 지나간다(멱등) — 지울 것이 없다는 것과 지웠다는 것을 여기서 가리지
     * 않는다. 호출부의 실패 응답이 필요해지는 것은 삭제를 노출하는 TΔ5다.
     *
     * <p>사진({@code data/photos/})·카드({@code artifact/cards/})는 대상이 아니다 — 파일시스템에 남고,
     * 노트↔사진 연결({@code note_photo})은 TΔ8b가 들인다(0028 Q-13).
     *
     * <p><b>더할 규칙이 없다</b>: hard delete라 표식을 남길 것이 없고 남는 것은 순서뿐이라 행 층
     * ({@code deleteNote})이 통째로 진다 — 쓰기 3종 중 유일하게 도메인을 지나지 않는 경로다.
     *
     * <p><b>지울 노트를 읽지 않는다.</b> 존재를 먼저 확인하면 그 노트가 영속성 컨텍스트에 실리고, 뒤이은
     * 벌크 삭제는 컨텍스트를 모르므로 <b>이미 지운 행의 살아 있는 사본</b>이 남는다({@code findEntryId}가
     * 엔티티가 아니라 id만 돌려주는 것과 같은 이유). 없는 id는 아무 행도 지우지 못할 뿐이다.
     */
    @Transactional
    public void delete(long id) {
        notes.deleteNote(id);
    }

    // ────────────────────────────── 조립 진입 ──────────────────────────────

    /**
     * 행 질의 + 도메인 재구성. <b>질의만 여기 있고</b> 재구성은 {@link NoteEntityMapper#assemble}이 한다
     * — 조립은 순수 함수라 "트랜잭션 단위인가"라는 이 클래스의 기준에 걸리지 않는다(TΔ4c).
     */
    private List<Note> assemble(List<NoteEntity> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        List<Long> noteIds = rows.stream().map(NoteEntity::getId).toList();
        return NoteEntityMapper.assemble(rows, notes.findChildRows(noteIds));
    }

    /** 쓰기 직후의 최종 상태 — 저장이 실제로 무엇을 남겼는지가 호출부가 들고 갈 값이다. */
    private Note reload(long noteId) {
        return notes.findById(noteId).map(row -> assemble(List.of(row)).getFirst())
                .orElseThrow(() -> new IllegalStateException("방금 저장한 노트 조회 실패: " + noteId));
    }
}
