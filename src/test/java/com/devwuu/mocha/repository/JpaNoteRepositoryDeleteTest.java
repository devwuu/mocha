package com.devwuu.mocha.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.devwuu.mocha.domain.Aliases;
import com.devwuu.mocha.domain.Bean;
import com.devwuu.mocha.domain.Brew;
import com.devwuu.mocha.domain.Entry;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.NoteMeta;
import com.devwuu.mocha.domain.Rating;
import com.devwuu.mocha.domain.Recipe;
import com.devwuu.mocha.domain.Source;
import com.devwuu.mocha.domain.Sourced;
import com.devwuu.mocha.domain.Tasting;
import com.devwuu.mocha.repository.jpa.NoteEntityRepository;
import com.devwuu.mocha.support.PostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * TΔ5d (changes/0028-rdb-storage) — {@link JpaNoteRepository#delete} (AC-Δ8).
 *
 * <p>이 테스트가 <b>유일한 안전망</b>이다. FK를 걸지 않기로 했으므로(ADR-74) 하위 단을 하나라도 빠뜨리면
 * DB는 아무 말 없이 고아를 남기고, 조립 경로는 부모를 통해서만 읽어 끊긴 행을 <b>지나쳐 버린다</b> —
 * 즉 도메인으로는 관측되지 않는다(TΔ5b·TΔ5c가 같은 사각을 만났다). 그래서 판정은 도메인이 아니라
 * <b>테이블 행 수</b>로 한다.
 *
 * <p>같은 이유로 "전부 0"만 세면 부족하다: {@code WHERE}를 빠뜨려 <b>테이블을 통째로 비우는</b> 구현도
 * 그 단언을 통과한다. 곁에 다른 노트를 심어 <b>남아야 할 것이 남는지</b>를 함께 본다.
 *
 * <p>{@link #flushAndClear()}로 컨텍스트를 비우는 이유는 앞선 저장소 테스트와 같다 — 비우지 않으면 조회가
 * identity map을 되돌려주고 DB를 지나지 않은 채 그린이 된다.
 */
@Transactional
class JpaNoteRepositoryDeleteTest extends PostgresIntegrationTest {

    /** 노트 아래에 행이 생기는 테이블 전부 — 하나라도 빠지면 그 단이 검증에서 새는 자리다. */
    private static final List<String> CHILD_TABLES =
            List.of("entry", "brew", "recipe", "tasting", "note_bean", "note_official_note", "note_alias", "note_source");

    @Autowired
    EntityManager em;

    @Autowired
    NoteEntityRepository notes;

    /** 네이티브 SQL이 향할 스키마 — JPQL과 같은 행을 보게 한다(TΔ5b가 밟은 {@code search_path} 함정). */
    @Value("${spring.jpa.properties.hibernate.default_schema}")
    String schema;

    JpaNoteRepository repo;

    @BeforeEach
    void setUp() {
        repo = new JpaNoteRepository(notes);
    }

    @Test
    @DisplayName("AC-Δ8: delete(id)가 하위 행을 남기지 않는다 — 3단 중첩·배열 4종 전부 잔존 0")
    void deleteLeavesNoRows() {
        Note saved = seedFullyPopulated();
        // 심은 것이 실제로 8개 테이블 전부에 걸쳐 있어야 판정에 의미가 있다 — 비어 있는 테이블을 세고
        // "0이다"라고 말하는 것은 아무것도 검증하지 않는다.
        flushAndClear();
        assertThat(CHILD_TABLES).allSatisfy(table -> assertThat(rowCount(table)).isPositive());

        repo.delete(saved.id());
        flushAndClear();

        assertThat(CHILD_TABLES).allSatisfy(table -> assertThat(rowCount(table)).isZero());
        assertThat(rowCount("note")).isZero();
    }

    @Test
    @DisplayName("AC-Δ8: 삭제는 그 노트에만 미친다 — 곁의 노트는 하위 행까지 무사하다")
    void deleteIsScopedToOneNote() {
        // WHERE를 빠뜨린 구현은 "전부 0" 판정을 통과한다. 남아야 할 것으로 그 갈래를 가른다.
        Note doomed = seedFullyPopulated();
        Note survivor = seedFullyPopulated();

        repo.delete(doomed.id());
        flushAndClear();

        Note loaded = repo.findById(survivor.id()).orElseThrow();
        assertThat(loaded.entries()).hasSize(2);
        assertThat(loaded.entries().getFirst().brews()).hasSize(2);
        assertThat(loaded.beans()).hasSize(1);
        assertThat(loaded.officialNotes().value()).containsExactly("자스민", "베르가못");
        assertThat(loaded.sources()).hasSize(1);
        assertThat(loaded.aliases().coffeeName()).containsExactly("예가체프 G1");
        assertThat(rowCount("note")).isOne();
    }

    @Test
    @DisplayName("delta §삭제 정책: hard delete — 삭제된 노트는 조회에서 사라진다")
    void deletedNoteDisappearsFromReads() {
        Note doomed = seedFullyPopulated();
        Note survivor = seedFullyPopulated();

        repo.delete(doomed.id());
        flushAndClear();

        // 표식을 남겨 거르는 것이 아니라 행이 없다 — soft delete를 두지 않은 결정의 관측면이다.
        assertThat(repo.findById(doomed.id())).isEmpty();
        assertThat(repo.findAll()).extracting(Note::id).containsExactly(survivor.id());
    }

    @Test
    @DisplayName("TΔ5d: 없는 id 삭제는 무해하다(멱등) — 다른 노트도 건드리지 않는다")
    void deleteOfMissingIdIsHarmless() {
        Note saved = seedFullyPopulated();

        assertThatCode(() -> repo.delete(99_999_999L)).doesNotThrowAnyException();
        // 같은 노트를 두 번 지워도 마찬가지다 — 지울 것이 없다는 것과 지웠다는 것을 A1이 가리지 않는다.
        repo.delete(saved.id());
        assertThatCode(() -> repo.delete(saved.id())).doesNotThrowAnyException();
        flushAndClear();

        assertThat(rowCount("note")).isZero();
    }

    // ────────────────────────────── 표본·헬퍼 ──────────────────────────────

    /**
     * 8개 하위 테이블에 전부 행이 생기는 노트 하나 — 엔트리 2건 × 회차 2개, 각 회차에 레시피·감상.
     * <p>회차를 둘 두는 것은 {@code brew} 삭제가 한 건만 지우고 나머지를 남기는 갈래를 가르기 위해서다.
     */
    private Note seedFullyPopulated() {
        Note saved = repo.upsertEntry(null, fullMeta(), entry(day(9)),
                new Aliases(List.of("예가체프 G1"), List.of("커피베라 성수")));
        return repo.upsertEntry(saved.id(), fullMeta(), entry(day(10)), Aliases.empty());
    }

    private static NoteMeta fullMeta() {
        return new NoteMeta(
                new Sourced<>("커피베라 예가체프 G1", Source.USER),
                new Sourced<>("커피베라", Source.USER),
                List.of(new Bean(new Sourced<>("에티오피아 예가체프", Source.SEARCH), new Sourced<>("워시드", Source.SEARCH))),
                new Sourced<>("미디엄", Source.SEARCH),
                new Sourced<>(List.of("자스민", "베르가못"), Source.SEARCH),
                List.of("https://example.com/coffeevera"));
    }

    private static LocalDate day(int dayOfMonth) {
        return LocalDate.of(2026, 7, dayOfMonth);
    }

    // updatedAt은 감사 컬럼이 발급하므로 인자값은 버려진다(Q-5) — 표본에서는 자리만 채운다.
    private static Entry entry(LocalDate date) {
        return new Entry(date, List.of(
                new Brew(recipe(15.0), new Tasting("첫 잔", "첫 잔", Rating.GOOD)),
                new Brew(recipe(16.0), new Tasting("둘째 잔", "둘째 잔", Rating.PERFECT))),
                OffsetDateTime.of(2000, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC));
    }

    private static Recipe recipe(double doseG) {
        return new Recipe("핸드드립", doseG, null, null, null, null, null, null, null, null);
    }

    /** 실 커밋이 하는 일을 재현한다 — flush 뒤에 컨텍스트를 비운다(상세는 TΔ5c 테스트의 같은 헬퍼가 소유). */
    private void flushAndClear() {
        em.flush();
        em.clear();
    }

    /** {@code %s}에 스키마를 채워 네이티브 질의를 만든다 — 식별자는 파라미터로 바인딩할 수 없다. */
    private Query nativeQuery(String sql) {
        return em.createNativeQuery(sql.formatted(schema));
    }

    /** 테이블 전체 행 수 — 고아 판정은 조립 경로로 볼 수 없다(부모를 통해서만 읽으므로). */
    private long rowCount(String table) {
        return ((Number) nativeQuery("SELECT count(*) FROM %s." + table).getSingleResult()).longValue();
    }
}
