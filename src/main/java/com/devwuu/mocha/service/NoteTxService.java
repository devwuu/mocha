package com.devwuu.mocha.service;

import com.devwuu.mocha.domain.Aliases;
import com.devwuu.mocha.domain.Cup;
import com.devwuu.mocha.domain.TastingDay;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.NoteCandidate;
import com.devwuu.mocha.domain.NoteCursor;
import com.devwuu.mocha.domain.NoteDetail;
import com.devwuu.mocha.domain.NoteFilter;
import com.devwuu.mocha.domain.NoteListItem;
import com.devwuu.mocha.domain.NoteMeta;
import com.devwuu.mocha.domain.NotePage;
import com.devwuu.mocha.domain.Sourced;
import com.devwuu.mocha.repository.NoteEntityMapper;
import com.devwuu.mocha.repository.NoteFolderName;
import com.devwuu.mocha.repository.entity.CupEntity;
import com.devwuu.mocha.repository.entity.TastingDayEntity;
import com.devwuu.mocha.repository.entity.NoteEntity;
import com.devwuu.mocha.repository.entity.NotePhotoEntity;
import com.devwuu.mocha.repository.entity.RecipeEntity;
import com.devwuu.mocha.repository.entity.ReviewEntity;
import com.devwuu.mocha.repository.jpa.NoteEntityRepository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
 *   <li><b>V-10</b> 개정본(D-12) 날짜 이동 시 이동처와 <b>회차 병합</b> = 엔트리 총수 1 감소, 사진 색인
 *       동반 이동({@link #replaceTastingDay})</li>
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

    /**
     * 갤러리 목록 한 페이지 — 필터·정렬·페이징·facet·총수를 한 트랜잭션에서
     * (ref: changes/0029 tasks.md TΔ5a·TΔ12, 계약 {@code contract/note-list.contract.json}).
     *
     * <p><b>질의 넷이 한 트랜잭션인 것이 이 메서드의 실질이다</b>: 목록·총수·facet이 갈라진 시점에서 읽히면
     * <i>"6편의 기록"</i>이라 적힌 헤더 아래에 7칸이 있거나, <b>목록에 보이는 로스터리를 필터에서 고를 수
     * 없는</b> 상태가 나온다. 무한 스크롤이라 사용자는 그 어긋남을 한 화면에서 본다.
     *
     * <p><b>다음 페이지 유무는 한 건 더 읽어 판정한다</b>({@code pageSize + 1}). 총수와 견주는 방법도
     * 있지만 그쪽은 커서 이후 남은 건수를 알려주지 못한다 — 필터가 걸리면 총수는 전체이고 커서는 위치라
     * 뺄셈이 성립하지 않는다.
     *
     * <p>검색어 정규화는 {@link NoteFilter#normalized()}가 소유한다 — {@link #findCandidates}가
     * {@link Aliases#normalize}를 직접 부르는 것과 같은 규율이고, 대조 컬럼을 만든 함수와 같은 함수를
     * 지나야 같은 기준에서 만난다.
     *
     * @param filter   좁히기 조건. {@code null}이면 전건.
     * @param cursor   이전 페이지의 마지막 지점. {@code null}이면 첫 페이지.
     * @param pageSize 한 페이지 건수.
     */
    @Transactional(readOnly = true)
    public NotePage findNotes(NoteFilter filter, NoteCursor cursor, int pageSize) {
        NoteFilter normalized = (filter == null ? NoteFilter.none() : filter).normalized();

        List<NoteListItem> rows = notes.findNoteItems(normalized, cursor, pageSize + 1);
        boolean hasMore = rows.size() > pageSize;
        List<NoteListItem> page = hasMore ? List.copyOf(rows.subList(0, pageSize)) : rows;
        NoteCursor next = null;
        if (hasMore) {
            NoteListItem last = page.getLast();
            next = new NoteCursor(last.latestDate(), last.noteId());
        }
        return new NotePage(page, next, notes.countNotes(normalized), notes.findFacets());
    }

    /**
     * 상세 화면이 읽는 노트 전문 + 그 노트의 사진 — 없으면 빈 Optional
     * (ref: changes/0029 tasks.md TΔ5a·TΔ13a, 계약 {@code contract/note-detail.contract.json}).
     *
     * <p>{@link #findById}와 갈라 두는 이유는 <b>사진이 딸려 오는가</b>다. 노트만 필요한 자리(커밋 직후
     * 재조회·삭제 전 폴더 접미 읽기)가 사진 색인 질의를 이유 없이 지불하지 않게 한다.
     *
     * <p>둘을 한 트랜잭션에서 읽는 것이 이 자리의 값이다 — 나눠 부르면 그 사이의 저장이 "엔트리는 새
     * 날짜인데 사진은 옛 목록"인 조합을 만든다.
     */
    @Transactional(readOnly = true)
    public Optional<NoteDetail> findDetail(long id) {
        return findById(id).map(note -> new NoteDetail(note, notes.findPhotos(id)));
    }

    // ────────────────────────────── 커밋 ──────────────────────────────

    /**
     * 폼 확정 저장 — 날짜 엔트리 병합 (ref: plan.md#ADR-4·37·59, changes/0016, AC-4).
     * <ul>
     *   <li>{@code noteId}가 {@code null}이면 {@code meta}로 새 노트를 만들고 {@code tasting_day}를 첫 엔트리로 둔다
     *       — id는 INSERT가 발급하므로 저장 전 draft는 식별자를 갖지 않는다(0028 D-1).</li>
     *   <li>있으면 같은 date 엔트리는 갱신(엔트리 통째 교체), 다른 date는 추가 후 날짜 오름차순 정렬한다.</li>
     *   <li>같은 date 갱신에서 회차 append·기존 회차 지칭 병합은 에이전트가 구성한 {@code tastingDay.cups}
     *       배열(V-15 검증 통과분)을 신뢰한다 — 서버는 회차 단위 병합을 하지 않는다(changes/0021 ADR-59).</li>
     * </ul>
     * POLICY: 같은 날짜 엔트리는 갱신만 — 하루 2엔트리 금지, 다회 시도는 cups 회차로
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
    public Note commit(Long noteId, NoteMeta meta, TastingDay tastingDay, Aliases generated) {
        if (noteId == null) {
            return insert(newNote(meta, tastingDay, generated));
        }
        // 소실은 조용히 신규 생성으로 흡수하지 않는다 — 그러면 매칭이 지목한 노트와 별개의 중복 노트가
        // 생기고 사진·카드가 두 id로 갈린다. FK가 없어 DB가 막아주지 않는 자리다(ADR-75).
        Note existing = findById(noteId)
                .orElseThrow(() -> new IllegalStateException("병합 대상 노트 소실: " + noteId));

        replaceTastingDayRows(noteId, tastingDay);
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
        for (TastingDay tastingDay : note.tastingDays()) {
            insertTastingDay(noteId, tastingDay);
        }
        return reload(noteId);
    }

    /** 신규 노트 — 식별자·타임스탬프는 저장이 발급하므로 비워 둔다({@link #insert} 계약). */
    private static Note newNote(NoteMeta meta, TastingDay tastingDay, Aliases aliases) {
        return new Note(
                null, meta.coffeeName(), meta.roastery(), meta.beans(), meta.roastLevel(),
                meta.officialNotes(), aliases == null ? Aliases.empty() : aliases, meta.sources(),
                List.of(tastingDay), null, null);
    }

    // POLICY: seq = 회차 번호. 구 스키마의 "배열 순서 = 회차 번호(별도 필드 없음)"를 컬럼으로 명시한다 —
    //         직렬화가 어긋나면 회차가 뒤섞이고 탐지할 방법이 없던 암묵 순서 의존이 여기서 사라진다
    //         (ref: data-model.md#2.2, changes/0028-rdb-storage/delta.md#동기, AC-Δ4).
    private void insertTastingDay(long noteId, TastingDay tastingDay) {
        long tastingDayId = notes.insertAndFlush(NoteEntityMapper.toTastingDayEntity(noteId, tastingDay)).getId();
        insertCups(tastingDayId, tastingDay.cups());
    }

    private void insertCups(long tastingDayId, List<Cup> cups) {
        appendCups(tastingDayId, cups, 0);
    }

    // 시작 seq를 받는 것은 날짜 이동 병합 하나 때문이다 — 그때만 이동처의 기존 회차 뒤로 이어 붙는다(D-12).
    private void appendCups(long tastingDayId, List<Cup> cups, int startSeq) {
        for (int i = 0; i < cups.size(); i++) {
            int seq = startSeq + i;
            long cupId = notes.insertAndFlush(new CupEntity(tastingDayId, seq)).getId();
            Cup cup = cups.get(i);
            // 레시피·감상이 없는 회차는 행을 만들지 않는다 — 1:1 짝은 cup_id PK 공유로만 표현된다(V-15).
            RecipeEntity recipe = NoteEntityMapper.toRecipeEntity(cupId, cup.recipe());
            ReviewEntity review = NoteEntityMapper.toReviewEntity(cupId, cup.review());
            notes.insertAll(Stream.of(recipe, review).filter(Objects::nonNull).toList());
        }
    }

    // 행 재사용이 아니라 삭제 후 재삽입인 것은 "통째 교체"의 직역이다 — 엔트리에는 필드 단위 갱신 개념이
    // 없어 수정 메서드도 두지 않았다(0028 TΔ3b). 날짜 오름차순 정렬은 조회 질의가 소유한다(재정렬하지 않는다).
    private void replaceTastingDayRows(long noteId, TastingDay tastingDay) {
        notes.findTastingDayId(noteId, tastingDay.date()).ifPresent(notes::deleteTastingDay);
        insertTastingDay(noteId, tastingDay);
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
     * <p>지금 읽는 곳은 셋이다: 삭제 시 지울 파일 목록, 그리고 <b>폴더 접미를 되읽는 자리</b> 둘
     * ({@link NoteFolderName#from} — 사진을 덧붙일 때와 날짜 이동으로 폴더를 옮길 때, TΔ5b-2).
     * 화면이 사진을 읽는 모양은 TΔ5a·TΔ12가 정한다.
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
     * <p><b>돌려주는 것은 노트 전문 + 사진이다</b>(TΔ5b-3) — 쓰기 직후의 최종 상태를 <b>같은 트랜잭션
     * 안에서</b> 읽는다. 나눠 부르면 그 사이의 저장이 "엔트리는 새 날짜인데 사진은 옛 목록"인 조합을
     * 만든다는 {@link NoteDetail}의 근거가 쓰기 뒤에도 그대로 걸린다. 메타 수정은 사진을 옮기지 않지만
     * {@link #replaceTastingDay}는 옮기므로, 두 쓰기의 반환형을 갈라 둘 이유가 없다.
     *
     * @throws IllegalArgumentException 커피명 변경 시도(V-9).
     * @throws IllegalStateException    대상 노트 소실 시(호출부가 안내로 수렴, plan §7).
     */
    @Transactional
    public NoteDetail updateMeta(long noteId, NoteMeta meta) {
        NoteEntity row = notes.findById(noteId)
                .orElseThrow(() -> new IllegalStateException("수정 대상 노트 소실: " + noteId));
        Sourced<String> stored = NoteEntityMapper.toSourced(row.getCoffeeName());
        if (!Objects.equals(Sourced.valueOrNull(stored), Sourced.valueOrNull(meta.coffeeName()))) {
            throw new IllegalArgumentException("coffee_name은 노트 생성 후 불변(V-9): " + noteId);
        }
        updateNoteFields(noteId, row, meta);
        return reloadDetail(noteId);
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
     * 엔트리 교체 — {@code targetDate} 엔트리의 회차를 {@code tasting_day}의 것으로 갈아끼우고, 필요하면 날짜를
     * 옮긴다 (ref: plan.md#ADR-27·ADR-59, V-10 개정본, changes/0029 delta.md#D-12).
     * <ul>
     *   <li>{@code tastingDay.date()}가 {@code targetDate}와 다르면 날짜 이동 — 이동처 date가 <b>비어 있으면</b>
     *       {@code tasted_on} 갱신이고, <b>이미 기록이 있으면</b> 그날의 회차 뒤로 합친다(엔트리 총수 1 감소).
     *       날짜 오름차순 정렬은 유지된다.</li>
     *   <li>이동처가 빈 경우 엔트리 <b>행은 살아남는다</b> — 삭제 후 재삽입이 아니라 {@code tasted_on}
     *       갱신이라 {@code created_at}이 보존된다(재기록 경로 {@link #commit}과 갈리는 지점).</li>
     * </ul>
     *
     * <p><b>검증 셋을 먼저 통과시킨다</b>(노트 존재 → 대상 엔트리 존재). 어느 하나라도 걸리면 <b>쓰기를
     * 시작하기 전에</b> 던지므로 부분 반영이 남지 않는다.
     *
     * <p>대상 엔트리 존재는 조회한 도메인이 아니라 <b>행으로</b> 확인한다. 로드 경계 위생(ADR-66)이 회차
     * 0개 엔트리를 드롭할 수 있어(V-15) 도메인으로 판정하면 <b>행은 있는데 소실로 보이는</b> 갈래가 생긴다
     * — 고칠 대상이 무엇인지는 행이 답한다.
     *
     * <p>갈래에 따라 대상 엔트리를 <b>다른 접근자로</b> 집는 것은 {@code findTastingDayId}·{@code findTastingDay}의
     * 계약 그대로다: 병합에서 원본은 <b>지울 것</b>이고(id만), 그 밖에서는 <b>고칠 것</b>이다(관리되는 엔티티).
     *
     * <p><b>사진 색인이 같은 트랜잭션에서 따라온다</b>(TΔ5b-2, D-12 ③) — {@code note_photo}의 참조 축이
     * {@code (note_id, tasted_on)}이라 엔트리만 옮기면 사진이 옛 날짜에 남아 어느 화면에도 보이지 않는다.
     * 갈라 두면 <i>"엔트리는 옮겨졌는데 사진은 안 옮겨진"</i> 중간 상태가 커밋될 수 있어 한 경계 안이다.
     *
     * @param movedPhotoPaths 파일 이동이 실제로 만든 {@code 옛 경로 → 새 경로}
     *                        ({@code PhotoStore.moveTastingDayPhotos}의 반환값). 날짜가 그대로거나 옮긴 사진이
     *                        없으면 빈 맵. <b>계산하지 않고 받는</b> 이유는 병합에서 파일명이 유일화로
     *                        바뀌기 때문이다 — 실제 자리는 파일을 옮긴 쪽만 안다.
     * @throws IllegalStateException 대상 노트 또는 {@code targetDate} 엔트리 소실 시
     *                               (호출부가 안내로 수렴, plan §7).
     */
    @Transactional
    public NoteDetail replaceTastingDay(long noteId, LocalDate targetDate, TastingDay tastingDay,
                                   Map<String, String> movedPhotoPaths) {
        if (notes.findById(noteId).isEmpty()) {
            throw new IllegalStateException("수정 대상 노트 소실: " + noteId);
        }
        LocalDate movedTo = tastingDay.date();
        Optional<Long> collision = movedTo.equals(targetDate)
                ? Optional.empty()
                : notes.findTastingDayId(noteId, movedTo);

        if (collision.isPresent()) {
            mergeIntoExisting(collision.get(), requireTastingDayId(noteId, targetDate), tastingDay.cups());
        } else {
            TastingDayEntity target = notes.findTastingDay(noteId, targetDate)
                    .orElseThrow(() -> missingTastingDay(noteId, targetDate));
            target.updateTastedOn(movedTo);
            replaceCups(target.getId(), tastingDay.cups());
        }
        if (!movedTo.equals(targetDate)) {
            movePhotoRows(noteId, targetDate, movedTo, movedPhotoPaths);
        }
        // 사진 색인을 방금 옮긴 트랜잭션이 그 결과를 그대로 읽어 돌려준다 — 화면이 이동 후의 사진 URL을
        // 스스로 계산하지 않게 하는 자리다(경로 규약을 클라이언트가 조립하지 않는다, ADR-75).
        return reloadDetail(noteId);
    }

    /**
     * 그 날짜의 사진 색인을 새 날짜로 옮긴다 — 회차와 같은 모양으로 <b>이동처 뒤에 이어 붙인다</b>
     * (ref: changes/0029 tasks.md TΔ5b-2, delta.md#D-12 ③, spec FR-10·FR-21).
     *
     * <p>{@code seq}를 0부터 다시 발급하지 않는 것은 선택이 아니라 제약이다 —
     * {@code UNIQUE (note_id, tasted_on, seq)}가 이동처에 이미 있는 사진과 부딪힌다. 그 결과 순서도
     * 회차 병합과 같아진다: <b>그날 먼저 있던 사진이 앞, 옮겨 온 것이 뒤</b>.
     */
    // POLICY: 옮긴 기록이 없는 행은 경로를 그대로 둔다 — 폴더 스캔이 있는 파일을 빠뜨리지 않으므로
    //         매핑에 없다는 것은 그 파일이 이동 전부터 없었다는 뜻이고, 날짜 세그먼트만 갈아 끼우면
    //         없는 파일을 가리키는 새 경로를 지어내는 셈이다 (ref: plan.md#ADR-79 — 행은 색인, 정본은 파일).
    private void movePhotoRows(long noteId, LocalDate from, LocalDate to, Map<String, String> movedPaths) {
        List<NotePhotoEntity> rows = notes.findPhotoRows(noteId, from);
        if (rows.isEmpty()) {
            return;
        }
        Map<String, String> moved = movedPaths == null ? Map.of() : movedPaths;
        int seq = (int) notes.countPhotos(noteId, to);
        for (NotePhotoEntity row : rows) {
            row.moveTo(to, seq++, moved.getOrDefault(row.getPath(), row.getPath()));
        }
    }

    // POLICY: 날짜 이동으로 이동처 date에 기존 엔트리가 있으면 덮어쓰지 않고 그날의 회차 뒤로 합친다 —
    //         기존이 앞, 옮겨 온 것이 뒤이고 seq는 이어서 발급된다. 엔트리 총수는 1 감소하고,
    //         화면은 경고가 아니라 안내를 띄운다(잃는 것이 없다)
    //         (ref: specs/coffee-note-agent/changes/0029-app-interface/delta.md#D-12, data-model.md#V-10).
    // 구 규칙(이동처를 통째로 대체)은 회차 도입(ADR-59) 이전에 쓰인 것이고 그 재편에서 갱신되지 않았다 —
    // 회차 배열 위에서는 "그날의 N회차를 전부 지운다"는 뜻이 돼 있었다. 캡처 경로가 같은 상황("이 날짜에
    // 이미 기록이 있다")에 append로 답하는 것과 갈릴 이유가 없다(ADR-4·59).
    // 살아남는 행은 이동처 엔트리다 — 합쳐지는 자리는 그날의 기록이고, 그 created_at이 그날 첫 기록이
    // 만들어진 시각이다. 옮겨 온 쪽은 회차로 흡수되므로 엔트리 행으로 남을 자리가 없다.
    // UNIQUE(note_id, tasted_on)를 지나지 않는다는 점도 갈린다: 병합에는 tasted_on UPDATE가 없어
    // 중간 상태 자체가 생기지 않는다(무충돌 이동만이 그 순서를 지는 갈래다).
    private void mergeIntoExisting(long destinationId, long sourceId, List<Cup> cups) {
        notes.deleteTastingDay(sourceId);
        appendCups(destinationId, cups, (int) notes.countCups(destinationId));
    }

    private long requireTastingDayId(long noteId, LocalDate targetDate) {
        return notes.findTastingDayId(noteId, targetDate).orElseThrow(() -> missingTastingDay(noteId, targetDate));
    }

    private static IllegalStateException missingTastingDay(long noteId, LocalDate targetDate) {
        return new IllegalStateException("수정 대상 엔트리 소실: " + noteId + " " + targetDate);
    }

    /**
     * 회차 통째 교체 — 엔트리 행은 살려 두고 그 아래만 갈아끼운다.
     *
     * <p>회차에는 필드 단위 갱신 개념이 없다(ADR-59 — 서버는 회차 단위 병합을 하지 않고 에이전트가 구성한
     * 배열을 신뢰한다). 삭제가 벌크라 즉시 나가므로 {@code UNIQUE(tasting_day_id, seq)}가 새 회차보다 먼저 풀린다.
     */
    private void replaceCups(long tastingDayId, List<Cup> cups) {
        notes.deleteCups(tastingDayId);
        insertCups(tastingDayId, cups);
    }

    // ────────────────────────────── 삭제 ──────────────────────────────

    /**
     * 노트 한 건 삭제 — 하위 행(엔트리·회차·레시피·감상·배열 4종)을 <b>남기지 않는다</b>
     * (ref: changes/0028-rdb-storage/delta.md#삭제-정책, AC-Δ8).
     *
     * <p>POLICY: hard delete — {@code deleted_at} 계열 컬럼을 두지 않는다. 1인용 개인 기록이라 복구 요구가
     * 관측된 적이 없고, soft delete는 <b>모든 조회에 조건을 얹는다</b>(ref: 0028 delta#삭제-정책, Q-3·Q-12).
     *
     * <p><b>지웠는지를 돌려준다</b>(TΔ5b-3) — {@code DELETE /api/notes/&#123;id&#125;}가 없는 id에 404로
     * 답해야 하는데(계약 {@code note-update.contract.json}), 그 판정에 필요한 것은 <i>노트를 읽는 일</i>이
     * 아니라 <b>삭제가 실제로 행을 지웠는가</b>다. 벌크 삭제의 반환 건수가 그 답이라 아래 문단의 규율을
     * 깨지 않고 얻어진다.
     *
     * <p>사진({@code data/photos/})·카드({@code artifact/cards/})는 대상이 아니다 — 파일시스템에 남고,
     * 노트↔사진 연결({@code note_photo})은 TΔ8b가 들인다(0028 Q-13).
     *
     * <p><b>더할 규칙이 없다</b>: hard delete라 표식을 남길 것이 없고 남는 것은 순서뿐이라 행 층
     * ({@code deleteNote})이 통째로 진다 — 쓰기 3종 중 유일하게 도메인을 지나지 않는 경로다.
     *
     * <p><b>지울 노트를 읽지 않는다.</b> 존재를 먼저 확인하면 그 노트가 영속성 컨텍스트에 실리고, 뒤이은
     * 벌크 삭제는 컨텍스트를 모르므로 <b>이미 지운 행의 살아 있는 사본</b>이 남는다({@code findTastingDayId}가
     * 엔티티가 아니라 id만 돌려주는 것과 같은 이유). 없는 id는 아무 행도 지우지 못할 뿐이다.
     *
     * @return 노트 행이 실제로 지워졌으면 {@code true}, 없는 id였으면 {@code false}.
     */
    @Transactional
    public boolean delete(long id) {
        return notes.deleteNote(id) > 0;
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

    /**
     * 쓰기 직후의 최종 상태 + 사진 — 수정 경로가 돌려주는 값이다(TΔ5b-3).
     * <p>{@link #findDetail}과 갈리는 것은 <b>없으면 던진다</b>는 점뿐이다: 방금 쓴 노트가 없다는 것은
     * 빈 결과가 아니라 고장이다.
     */
    private NoteDetail reloadDetail(long noteId) {
        return new NoteDetail(reload(noteId), notes.findPhotos(noteId));
    }

    /** 쓰기 직후의 최종 상태 — 저장이 실제로 무엇을 남겼는지가 호출부가 들고 갈 값이다. */
    private Note reload(long noteId) {
        return notes.findById(noteId).map(row -> assemble(List.of(row)).getFirst())
                .orElseThrow(() -> new IllegalStateException("방금 저장한 노트 조회 실패: " + noteId));
    }
}
