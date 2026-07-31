package com.devwuu.mocha.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.devwuu.mocha.domain.Aliases;
import com.devwuu.mocha.domain.Bean;
import com.devwuu.mocha.domain.Brew;
import com.devwuu.mocha.domain.Entry;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.Rating;
import com.devwuu.mocha.domain.Recipe;
import com.devwuu.mocha.domain.Source;
import com.devwuu.mocha.domain.Sourced;
import com.devwuu.mocha.domain.Tasting;
import com.devwuu.mocha.repository.entity.NoteBeanEntity;
import com.devwuu.mocha.repository.entity.NoteEntity;
import com.devwuu.mocha.repository.entity.SourcedValue;
import com.devwuu.mocha.repository.jpa.NoteEntityRepository;
import com.devwuu.mocha.support.PostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * TΔ5a (changes/0028-rdb-storage) — {@link NoteTxService}의 조회 + 신규 생성 (AC-Δ1).
 *
 * <p>판정은 둘이다: <b>저장 → 조회 왕복이 도메인 동치</b>이고, <b>정렬이 삽입 순서에 의존하지 않는다</b>.
 *
 * <p>TΔ3c의 왕복 테스트가 순수 매핑 단위였던 것과 갈린다 — 여기서는 값이 실제로 Postgres를 지난다.
 * {@code numeric} scale, {@code date} 타입, {@code @Embeddable} 인스턴스화, 감사 컬럼 주입이 왕복에
 * 포함되므로 쓰기 뒤 {@code em.clear()}로 영속성 컨텍스트를 비운다 — 비우지 않으면 조회가 identity map을
 * 되돌려주고 DB를 지나지 않은 채 그린이 된다.
 *
 * <p>트랜잭션은 테스트가 연다(격리는 롤백이 소유) — 그래서 빈(프록시)이 아니라 협력자로 직접 조립한
 * 인스턴스를 쓴다. 저장소 자신의 {@code @Transactional} 경계는 TΔ6a의 빈 배선으로 살아났고 그 관측은
 * 컨텍스트 기동(-{@code MochaApplicationTests})이 진다.
 */
@Transactional
class NoteTxServiceTest extends PostgresIntegrationTest {

    private static final OffsetDateTime IGNORED = OffsetDateTime.of(2000, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    // insert가 인자의 식별자를 무시한다는 계약을 실제로 대조하기 위한 값 — null이면 "무시했다"와
    // "애초에 없었다"가 구별되지 않는다.
    private static final long IGNORED_ID = 999L;

    @Autowired
    EntityManager em;

    @Autowired
    NoteEntityRepository notes;

    NoteTxService repo;

    @BeforeEach
    void setUp() {
        // 협력자 하나만 주입받아 직접 조립한다 — 롤백으로 격리하려면 트랜잭션을 테스트가 열어야 하고,
        // 빈 프록시를 쓰면 저장소가 자기 트랜잭션을 열어 그 경계가 겹친다.
        repo = new NoteTxService(notes);
    }

    @Test
    @DisplayName("TΔ5a: 저장 → 조회 왕복이 도메인 동치 — 3단 중첩과 배열 6종이 Postgres를 지나 그대로 돌아온다")
    void roundTripThroughDatabase() {
        Note input = sampleNote(List.of(firstEntry(), secondEntry()));

        Note saved = repo.insert(input);
        em.clear();
        Note restored = repo.findById(saved.id()).orElseThrow();

        // 식별자·타임스탬프는 DB가 발급하므로 입력과 같을 수 없다 — 그 둘을 뺀 내용 전부가 동치여야 한다.
        assertThat(content(restored)).isEqualTo(content(input));
        // 컨텍스트를 비운 뒤 읽은 것이 저장이 돌려준 것과 같다 = 값이 메모리가 아니라 DB에서 되살아났다.
        assertThat(restored).isEqualTo(saved);
    }

    @Test
    @DisplayName("TΔ5a: 식별자는 DB가 발급한다 — 인자의 id는 무시되고 발급된 id가 조회 키로 동작한다")
    void identifierComesFromDatabase() {
        Note first = repo.insert(sampleNote(List.of(firstEntry())));
        Note second = repo.insert(sampleNote(List.of(firstEntry())));
        em.clear();

        // 표본이 실어 보낸 IGNORED_ID가 아니라 BIGSERIAL이 발급한 값이다(insert 계약).
        assertThat(first.id()).isNotNull().isNotEqualTo(IGNORED_ID);
        assertThat(second.id()).isEqualTo(first.id() + 1);
        assertThat(repo.findById(first.id()).orElseThrow().id()).isEqualTo(first.id());
    }

    @Test
    @DisplayName("TΔ5a: 엔트리 정렬은 질의가 소유한다 — 내림차순으로 심어도 날짜 오름차순으로 돌아온다")
    void entryOrderComesFromQueryNotInsertion() {
        // 날짜 내림차순으로 심는다 — 삽입 순서를 그대로 되돌려주면 여기서 갈린다(Note 계약: 날짜 오름차순).
        Note input = sampleNote(List.of(secondEntry(), firstEntry()));

        Note saved = repo.insert(input);
        em.clear();
        Note restored = repo.findById(saved.id()).orElseThrow();

        assertThat(restored.entries()).extracting(Entry::date)
                .containsExactly(LocalDate.of(2026, 7, 10), LocalDate.of(2026, 7, 12));
        // 회차는 seq 오름차순 — 첫 엔트리의 두 회차가 뒤집히지 않았다(AC-Δ4의 저장소 쪽 최소 확인).
        assertThat(restored.entries().getFirst().brews()).extracting(b -> b.recipe().doseG())
                .containsExactly(15.0, 16.0);
    }

    @Test
    @DisplayName("TΔ5a: findAll은 id 오름차순으로 결정적이다 — 행의 물리 순서가 바뀌어도 흔들리지 않는다")
    void findAllIsDeterministicById() {
        List<Long> inserted = List.of(
                repo.insert(sampleNote(List.of(firstEntry()))).id(),
                repo.insert(sampleNote(List.of(firstEntry()))).id(),
                repo.insert(sampleNote(List.of(firstEntry()))).id());

        // 첫 노트를 수정하면 Postgres는 새 튜플을 힙 끝에 append한다 — ORDER BY가 없으면 순서가 밀린다.
        NoteEntity first = em.find(NoteEntity.class, inserted.getFirst());
        first.updateRoastLevel(new SourcedValue("중배전", Source.USER));
        em.flush();
        em.clear();

        assertThat(repo.findAll()).extracting(Note::id).containsExactlyElementsOf(inserted);
    }

    @Test
    @DisplayName("TΔ5a: 감사 컬럼이 도메인 타임스탬프를 겸한다 — 조회 왕복에서 실제 값으로 돌아온다(Q-5)")
    void auditColumnsSurfaceAsDomainTimestamps() {
        Note saved = repo.insert(sampleNote(List.of(firstEntry())));
        em.clear();

        Note restored = repo.findById(saved.id()).orElseThrow();

        // 입력이 실어 보낸 IGNORED가 아니라 Auditing이 채운 값이어야 한다 — 변환기는 이 필드를 쓰지 않는다(TΔ3c).
        assertThat(restored.createdAt()).isNotNull().isNotEqualTo(IGNORED);
        assertThat(restored.updatedAt()).isEqualTo(restored.createdAt());
        assertThat(restored.entries().getFirst().updatedAt()).isNotNull().isNotEqualTo(IGNORED);
        // POLICY: 표기는 UTC — timestamptz가 오프셋을 저장하지 않으므로 쓰기 표기를 컬럼이 담는 것에
        //         맞춘다(사용자 확정 2026-07-31, JpaAuditingConfig POLICY). 맞추지 않으면 저장이 돌려준
        //         값과 다시 읽은 값의 오프셋이 갈린다 — 그 동치는 roundTripThroughDatabase가 붙잡는다.
        assertThat(restored.createdAt().getOffset()).isEqualTo(ZoneOffset.UTC);
        assertThat(restored.entries().getFirst().updatedAt().getOffset()).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    @DisplayName("TΔ5a: 없는 식별자는 빈 Optional로 수렴한다")
    void missingIdentifierYieldsEmpty() {
        // 구 slug 문자열이 들어오는 경로는 여기서 사라졌다 — 인자가 long이라 타입이 막는다.
        // 모델이 비숫자 문자열을 넘기는 경우(E-6)는 tool 계층의 파싱 실패이며 TΔ6b가 소유한다.
        assertThat(repo.findById(99_999_999L)).isEmpty();
    }

    @Test
    @DisplayName("TΔ5a: 빈 저장소의 findAll은 빈 목록이다")
    void findAllOnEmptyRepository() {
        assertThat(repo.findAll()).isEmpty();
    }

    // TΔ6d가 둔 미구현 표식 2건은 전부 사라졌다 — commit는 TΔ5b가, applyEdit은 TΔ5c가 구현하며
    // 표식 테스트가 실패해 삭제가 강제됐다(장치가 의도대로 작동했다). 각 계약의 검증은
    // NoteTxServiceUpsertEntryTest·NoteTxServiceApplyEditTest가 소유한다.

    // ───────────── 로드 경계 위생 (ADR-66, 구 JsonFileNoteRepositoryTest 이관 — TΔ10b) ─────────────

    @Test
    @DisplayName("이관/AC-Δ2①: 공백 description 원두 행은 로드 시 드롭 — 나머지 원두 유지, 행은 무변경")
    void loadDropsInvalidBeanElements() {
        long noteId = repo.insert(sampleNote(List.of(firstEntry()))).id();
        // 저장소를 지나지 않고 V-14 위반 원두를 심는다 — psql 직접 편집 대역. 스키마가 막는 것은 부재까지고
        // (description NOT NULL) 공백은 통과하므로, 파일 시절의 방어가 그대로 남는 갈래가 정확히 여기다.
        em.persist(new NoteBeanEntity(noteId, 2, new SourcedValue("   ", Source.PHOTO), null));
        em.flush();
        em.clear();

        // 단건·전체 조회 모두 공용 읽기 지점(assemble)을 지난다 — 위반 요소만 드롭, 정상 원두 2종 유지.
        assertThat(repo.findById(noteId).orElseThrow().beans())
                .extracting(b -> b.description().value())
                .containsExactly("에티오피아 예가체프", "콜롬비아 우일라");
        assertThat(repo.findAll().getFirst().beans()).hasSize(2);

        // DB는 다시 쓰지 않는다 — 읽기 메모리 정규화만이다(구 테스트의 "파일 내용 무변경"이 여기로 온다).
        em.clear();
        assertThat(beanRows(noteId))
                .as("드롭은 조회 결과에만 미친다 — 위반 행은 그대로 남고 그 노트의 다음 저장에서 반영된다")
                .hasSize(3);
    }

    @Test
    @DisplayName("재정의/CR25-10: 엔트리 0건 노트가 findAll 전체를 마비시키지 않는다 — 곁의 정상 노트도 온전하다")
    void zeroEntryNoteDoesNotBreakFindAll() {
        // 파일 시절의 "entries 키 없는 JSON"이라는 형태는 소멸했지만 성질은 남는다 — DB에서 엔트리 0행은
        // 결손이 아니라 정상 상태이고(삭제 직후가 그렇다), 그 노트가 조립을 지나며 넘어지면 결손 1건이
        // findAll을 통해 조회·매칭·전체 렌더를 통째로 마비시킨다.
        long empty = repo.insert(sampleNote(List.of())).id();
        long normal = repo.insert(sampleNote(List.of(firstEntry()))).id();
        em.clear();

        assertThat(repo.findById(empty).orElseThrow().entries()).isEmpty();
        assertThat(repo.findAll()).extracting(Note::id).containsExactly(empty, normal);
        assertThat(repo.findAll().getLast().entries()).hasSize(1);
    }

    /** 조립 경로는 부모를 통해서만 자식을 읽으므로, 드롭된 행의 생존은 테이블을 직접 봐야 관측된다. */
    private List<NoteBeanEntity> beanRows(long noteId) {
        return em.createQuery(
                        "select b from NoteBeanEntity b where b.noteId = :noteId", NoteBeanEntity.class)
                .setParameter("noteId", noteId)
                .getResultList();
    }

    // ────────────────────────────── 표본 ──────────────────────────────

    /** 3단 중첩 + 배열 6종을 전부 채운 노트 — 왕복이 무엇 하나 떨구지 않는지 보는 기준 표본(TΔ3c 표본 승계). */
    private static Note sampleNote(List<Entry> entries) {
        return new Note(
                IGNORED_ID,                                                     // id는 BIGSERIAL이 발급한다
                new Sourced<>("커피베라 예가체프 G1", Source.USER),
                new Sourced<>("커피베라", Source.USER),
                List.of(new Bean(new Sourced<>("에티오피아 예가체프", Source.USER), new Sourced<>("워시드", Source.SEARCH)),
                        new Bean(new Sourced<>("콜롬비아 우일라", Source.SEARCH), null)),
                new Sourced<>(null, Source.SEARCH),                              // 값 없이 source만 마킹된 필드
                new Sourced<>(List.of("자스민", "베르가못"), Source.SEARCH),
                new Aliases(List.of("예가체프 G1", "yirgacheffe"), List.of("Coffee Vera")),
                List.of("https://example.com/coffeevera"),
                entries,
                IGNORED,
                IGNORED);
    }

    private static Entry firstEntry() {
        return new Entry(
                LocalDate.of(2026, 7, 10),
                List.of(
                        new Brew(new Recipe("핸드드립", 15.0, 240.0, 220.0, 150.0, 92.5, "중간 (코만단테)",
                                "V60", "뜸 40ml 30초 → 100ml → 100ml", "다음엔 더 굵게"),
                                new Tasting("새콤하고 좋았음", "It was pleasantly bright", Rating.GOOD)),
                        new Brew(new Recipe(null, 16.0, null, null, null, null, null, null, null, null), null)),
                IGNORED);
    }

    private static Entry secondEntry() {
        return new Entry(
                LocalDate.of(2026, 7, 12),
                List.of(new Brew(null, new Tasting("오늘은 밍밍했음", "오늘은 밍밍했음", Rating.OKAY_NOT_MINE))),
                IGNORED);
    }

    /**
     * 식별자·타임스탬프를 지운 사본. DB가 발급하는 값을 뺀 <b>나머지 전부</b>를 한 번에 대조하기 위한 것으로,
     * 필드를 골라 단언하지 않으므로 컴포넌트가 늘어도 새는 값이 생기지 않는다.
     */
    private static Note content(Note note) {
        return new Note(
                (Long) null, note.coffeeName(), note.roastery(), note.beans(), note.roastLevel(), note.officialNotes(),
                note.aliases(), note.sources(),
                note.entries().stream().map(e -> new Entry(e.date(), e.brews(), null)).toList(),
                null, null);
    }
}
