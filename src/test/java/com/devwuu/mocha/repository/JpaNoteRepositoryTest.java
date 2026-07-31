package com.devwuu.mocha.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 * TΔ5a (changes/0028-rdb-storage) — {@link JpaNoteRepository}의 조회 + 신규 생성 (AC-Δ1).
 *
 * <p>판정은 둘이다: <b>저장 → 조회 왕복이 도메인 동치</b>이고, <b>정렬이 삽입 순서에 의존하지 않는다</b>.
 *
 * <p>TΔ3c의 왕복 테스트가 순수 매핑 단위였던 것과 갈린다 — 여기서는 값이 실제로 Postgres를 지난다.
 * {@code numeric} scale, {@code date} 타입, {@code @Embeddable} 인스턴스화, 감사 컬럼 주입이 왕복에
 * 포함되므로 쓰기 뒤 {@code em.clear()}로 영속성 컨텍스트를 비운다 — 비우지 않으면 조회가 identity map을
 * 되돌려주고 DB를 지나지 않은 채 그린이 된다.
 *
 * <p>저장소는 아직 빈이 아니다({@code RepositoryConfig}가 여전히 JSON 구현체를 준다 — 교체는 TΔ9a).
 * 직접 조립해 쓰고, 트랜잭션은 테스트가 연다(격리는 롤백이 소유).
 */
@Transactional
class JpaNoteRepositoryTest extends PostgresIntegrationTest {

    private static final OffsetDateTime IGNORED = OffsetDateTime.of(2000, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    @Autowired
    EntityManager em;

    @Autowired
    NoteEntityRepository notes;

    JpaNoteRepository repo;

    @BeforeEach
    void setUp() {
        // 저장소를 빈으로 올리지 않는다 — RepositoryConfig가 NoteRepository로 아직 JSON 구현체를 주고
        // 있어(교체는 TΔ6a) 여기서 빈을 하나 더 만들면 타입 후보가 둘이 된다. 협력자 하나만 주입받아
        // 직접 조립한다. TΔ6a 이후에는 이 조립이 RepositoryConfig로 옮겨간다.
        repo = new JpaNoteRepository(notes);
    }

    @Test
    @DisplayName("TΔ5a: 저장 → 조회 왕복이 도메인 동치 — 3단 중첩과 배열 6종이 Postgres를 지나 그대로 돌아온다")
    void roundTripThroughDatabase() {
        Note input = sampleNote(List.of(firstEntry(), secondEntry()));

        Note saved = repo.insert(input);
        em.clear();
        Note restored = repo.findBySlug(saved.slug()).orElseThrow();

        // 식별자·타임스탬프는 DB가 발급하므로 입력과 같을 수 없다 — 그 둘을 뺀 내용 전부가 동치여야 한다.
        assertThat(content(restored)).isEqualTo(content(input));
        // 컨텍스트를 비운 뒤 읽은 것이 저장이 돌려준 것과 같다 = 값이 메모리가 아니라 DB에서 되살아났다.
        assertThat(restored).isEqualTo(saved);
    }

    @Test
    @DisplayName("TΔ5a: 식별자는 DB가 발급한다 — slug는 note.id의 십진 표기이고 조회 키로 실제로 동작한다")
    void identifierComesFromDatabase() {
        Note first = repo.insert(sampleNote(List.of(firstEntry())));
        Note second = repo.insert(sampleNote(List.of(firstEntry())));
        em.clear();

        assertThat(Long.parseLong(second.slug())).isEqualTo(Long.parseLong(first.slug()) + 1);
        assertThat(repo.findById(Long.parseLong(first.slug())).orElseThrow().slug()).isEqualTo(first.slug());
    }

    @Test
    @DisplayName("TΔ5a: 엔트리 정렬은 질의가 소유한다 — 내림차순으로 심어도 날짜 오름차순으로 돌아온다")
    void entryOrderComesFromQueryNotInsertion() {
        // 날짜 내림차순으로 심는다 — 삽입 순서를 그대로 되돌려주면 여기서 갈린다(Note 계약: 날짜 오름차순).
        Note input = sampleNote(List.of(secondEntry(), firstEntry()));

        Note saved = repo.insert(input);
        em.clear();
        Note restored = repo.findBySlug(saved.slug()).orElseThrow();

        assertThat(restored.entries()).extracting(Entry::date)
                .containsExactly(LocalDate.of(2026, 7, 10), LocalDate.of(2026, 7, 12));
        // 회차는 seq 오름차순 — 첫 엔트리의 두 회차가 뒤집히지 않았다(AC-Δ4의 저장소 쪽 최소 확인).
        assertThat(restored.entries().getFirst().brews()).extracting(b -> b.recipe().doseG())
                .containsExactly(15.0, 16.0);
    }

    @Test
    @DisplayName("TΔ5a: findAll은 id 오름차순으로 결정적이다 — 행의 물리 순서가 바뀌어도 흔들리지 않는다")
    void findAllIsDeterministicById() {
        List<String> inserted = List.of(
                repo.insert(sampleNote(List.of(firstEntry()))).slug(),
                repo.insert(sampleNote(List.of(firstEntry()))).slug(),
                repo.insert(sampleNote(List.of(firstEntry()))).slug());

        // 첫 노트를 수정하면 Postgres는 새 튜플을 힙 끝에 append한다 — ORDER BY가 없으면 순서가 밀린다.
        NoteEntity first = em.find(NoteEntity.class, Long.parseLong(inserted.getFirst()));
        first.updateRoastLevel(new SourcedValue("중배전", Source.USER));
        em.flush();
        em.clear();

        assertThat(repo.findAll()).extracting(Note::slug).containsExactlyElementsOf(inserted);
    }

    @Test
    @DisplayName("TΔ5a: 감사 컬럼이 도메인 타임스탬프를 겸한다 — 조회 왕복에서 실제 값으로 돌아온다(Q-5)")
    void auditColumnsSurfaceAsDomainTimestamps() {
        Note saved = repo.insert(sampleNote(List.of(firstEntry())));
        em.clear();

        Note restored = repo.findBySlug(saved.slug()).orElseThrow();

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
    @DisplayName("TΔ5a: 없는 식별자와 구 slug 표기는 빈 Optional로 수렴한다")
    void missingIdentifierYieldsEmpty() {
        assertThat(repo.findBySlug("99999999")).isEmpty();
        // 구 kebab-case slug는 숫자로 읽히지 않는다 — 예외가 아니라 부재로 수렴한다(TΔ6a까지의 임시 다리).
        assertThat(repo.findBySlug("coffeevera-yirgacheffe-g1")).isEmpty();
        assertThat(repo.findBySlug(null)).isEmpty();
    }

    @Test
    @DisplayName("TΔ5a: nextAvailableSlug는 근거가 소멸했다 — slug 유일화(V-2)를 흉내내지 않고 거부한다")
    void slugAllocationIsGone() {
        assertThatThrownBy(() -> repo.nextAvailableSlug("coffeevera"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("TΔ5a: 빈 저장소의 findAll은 빈 목록이다")
    void findAllOnEmptyRepository() {
        assertThat(repo.findAll()).isEmpty();
    }

    // ────────────────────────────── 표본 ──────────────────────────────

    /** 3단 중첩 + 배열 6종을 전부 채운 노트 — 왕복이 무엇 하나 떨구지 않는지 보는 기준 표본(TΔ3c 표본 승계). */
    private static Note sampleNote(List<Entry> entries) {
        return new Note(
                "무시된다",                                                     // id는 BIGSERIAL이 발급한다
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
                "", note.coffeeName(), note.roastery(), note.beans(), note.roastLevel(), note.officialNotes(),
                note.aliases(), note.sources(),
                note.entries().stream().map(e -> new Entry(e.date(), e.brews(), null)).toList(),
                null, null);
    }
}
