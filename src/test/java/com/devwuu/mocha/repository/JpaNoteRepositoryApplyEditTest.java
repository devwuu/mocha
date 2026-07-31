package com.devwuu.mocha.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
 * TΔ5c (changes/0028-rdb-storage) — {@link JpaNoteRepository#applyEdit} (AC-Δ1).
 *
 * <p>구 {@code JsonFileNoteRepositoryTest}의 수정 세션 검증분 4건 이관이다(TΔ6a가 넘긴 목록) — 저장 매체가
 * 바뀌어도 답이 같아야 하는 것이 AC-Δ1이다. 다만 <b>파일에 없던 위험</b>이 하나 붙는다: 파일 구현은 엔트리
 * 목록을 메모리에서 재조립해 한 번에 썼지만 <b>DB는 중간 상태에서 제약을 검사</b>한다 — 날짜 이동은
 * {@code UNIQUE(note_id, tasted_on)}를 지나야 하므로 "이동처를 비운 뒤 넣는다"가 순서로 지켜져야 한다.
 *
 * <p>컨텍스트를 비우는 이유는 TΔ5a·TΔ5b와 같다 — 비우지 않으면 조회가 identity map을 되돌려주고 DB를
 * 지나지 않은 채 그린이 된다. 다만 여기서는 {@link #flushAndClear()}로 <b>flush를 먼저</b> 건다: 수정
 * 경로는 관리되는 엔티티를 고치는 형태라 UPDATE가 flush까지 pending으로 남고, 앞선 두 task처럼
 * {@code clear()}만 부르면 그 갱신이 버려진다(그 자리의 상세는 헬퍼 javadoc이 소유한다).
 */
@Transactional
class JpaNoteRepositoryApplyEditTest extends PostgresIntegrationTest {

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

    // ─────────────────────── 필드 갱신 (FR-21, 이관) ───────────────────────

    @Test
    @DisplayName("이관: applyEdit 필드 갱신 — 같은 date의 엔트리·노트 필드가 draft로 갱신된다")
    void applyEditUpdatesFields() {
        Note saved = seed(entry(day(10), "새콤하고 좋았다"));

        // 노트 단위 필드도 수정 범위다 — 커피명을 제외한 전부.
        Note draft = draftOf(saved, saved.coffeeName(), new Sourced<>("커피베라 성수점", Source.USER),
                saved.aliases(), List.of(entry(day(10), "다시 보니 복숭아향")));
        Note updated = repo.applyEdit(saved.id(), day(10), draft);

        assertThat(updated.entries()).hasSize(1);
        assertThat(tasteOf(updated.entries().getFirst())).isEqualTo("다시 보니 복숭아향");
        assertThat(updated.roastery().value()).isEqualTo("커피베라 성수점");

        // DB 왕복에도 동일 — identity map이 아니라 행이 답한다.
        flushAndClear();
        Note loaded = repo.findById(saved.id()).orElseThrow();
        assertThat(loaded.roastery().value()).isEqualTo("커피베라 성수점");
        assertThat(tasteOf(loaded.entries().getFirst())).isEqualTo("다시 보니 복숭아향");
    }

    @Test
    @DisplayName("Q-6: 로스터리 수정은 정규화 비교 키(roastery_normalized)도 함께 움직인다")
    void roasteryEditMovesNormalizedKey() {
        // 매칭(FR-14)이 보는 것은 표시값이 아니라 이 컬럼이라, 따라 움직이지 않으면 수정된 노트가
        // 옛 표기로만 매칭된다. 값 계산은 Aliases.normalize()가 단일 소스다.
        Note saved = seed(entry(day(10), "감상"));

        repo.applyEdit(saved.id(), day(10), draftOf(saved, saved.coffeeName(),
                new Sourced<>("Coffee  VERA", Source.USER), saved.aliases(), List.of(entry(day(10), "감상"))));
        flushAndClear();

        assertThat(notes.findById(saved.id()).orElseThrow().getRoasteryNormalized())
                .isEqualTo(Aliases.normalize("Coffee  VERA"));
    }

    @Test
    @DisplayName("FR-21: 배열 3종은 draft로 통째 교체 — 옛 원두·공식 노트·참조가 남지 않는다")
    void noteArraysAreReplacedWholesale() {
        Note saved = seed(entry(day(10), "감상"));

        Note draft = new Note(
                saved.id(), saved.coffeeName(), saved.roastery(),
                List.of(new Bean(new Sourced<>("콜롬비아 우일라", Source.USER), null)),
                new Sourced<>("미디엄", Source.SEARCH),
                new Sourced<>(List.of("초콜릿"), Source.SEARCH),
                saved.aliases(),
                List.of("https://example.com/other"),
                List.of(entry(day(10), "감상")),
                saved.createdAt(), saved.updatedAt());
        Note updated = repo.applyEdit(saved.id(), day(10), draft);

        assertThat(updated.beans()).hasSize(1);
        assertThat(updated.beans().getFirst().description().value()).isEqualTo("콜롬비아 우일라");
        assertThat(updated.officialNotes().value()).containsExactly("초콜릿");
        assertThat(updated.sources()).containsExactly("https://example.com/other");
        assertThat(updated.roastLevel().value()).isEqualTo("미디엄");

        // 교체이므로 옛 행이 남아선 안 된다 — 조립 경로는 부모를 통해서만 읽어 고아를 지나치므로
        // 테이블을 직접 센다(ADR-74: FK가 없어 DB는 이 자리를 막지 않는다).
        flushAndClear();
        assertThat(rowCount("note_bean")).isOne();
        assertThat(rowCount("note_official_note")).isOne();
        assertThat(rowCount("note_source")).isOne();
    }

    // ─────────────────────── 날짜 이동 (V-10, 이관) ───────────────────────

    @Test
    @DisplayName("이관: 무충돌 날짜 이동 — 옛 date 제거·새 date 추가, 총수 불변·오름차순 유지")
    void applyEditMovesDateWithoutConflict() {
        Note seeded = seed(entry(day(9), "9일"));
        Note saved = repo.upsertEntry(seeded.id(), fullMeta(), entry(day(10), "10일"), Aliases.empty());

        Note updated = repo.applyEdit(saved.id(), day(10),
                editDraft(saved, entry(day(11), "사실 11일에 마심")));

        assertThat(updated.entries()).extracting(Entry::date).containsExactly(day(9), day(11));
        assertThat(tasteOf(updated.entries().getLast())).isEqualTo("사실 11일에 마심");

        flushAndClear();
        assertThat(repo.findById(updated.id()).orElseThrow().entries())
                .extracting(Entry::date).containsExactly(day(9), day(11));
    }

    @Test
    @DisplayName("V-10 이관: 날짜 이동 충돌 시 대상 덮어쓰기 + 원본 date 제거 — 엔트리 총수 1 감소")
    void applyEditOverwritesOnDateConflict() {
        Note saved = seed(entry(day(9), "9일 원본"));
        saved = repo.upsertEntry(saved.id(), fullMeta(), entry(day(10), "10일 원본"), Aliases.empty());
        assertThat(saved.entries()).hasSize(2);

        // 7/9 엔트리를 7/10으로 이동 — 기존 7/10과 충돌한다. UNIQUE(note_id, tasted_on)를 지나야 하는
        // 자리이고, 삭제보다 삽입이 먼저 나가면 여기서 제약 위반으로 터진다.
        Note updated = repo.applyEdit(saved.id(), day(9),
                editDraft(saved, entry(day(10), "이동해 온 9일 기록")));

        assertThat(updated.entries()).hasSize(1);
        assertThat(updated.entries().getFirst().date()).isEqualTo(day(10));
        assertThat(tasteOf(updated.entries().getFirst())).isEqualTo("이동해 온 9일 기록");

        flushAndClear();
        assertThat(repo.findById(updated.id()).orElseThrow().entries()).hasSize(1);
    }

    @Test
    @DisplayName("TΔ5c: 날짜 이동은 행 교체가 아니라 갱신 — 엔트리 행이 살아남는다(created_at 보존)")
    void dateMovePreservesEntryRow() {
        // 수정은 "같은 기록이 다른 날짜에 놓이는 것"이라 행이 살아야 감사 축이 의미를 갖는다 —
        // 지우고 다시 넣으면 created_at이 매번 새로 발급된다(재기록 경로가 치르는 대가, TΔ5b).
        Note saved = seed(entry(day(9), "9일"));
        long entryIdBefore = notes.findEntryId(saved.id(), day(9)).orElseThrow();

        repo.applyEdit(saved.id(), day(9), editDraft(saved, entry(day(10), "이동")));
        flushAndClear();

        assertThat(notes.findEntryId(saved.id(), day(10))).contains(entryIdBefore);
        assertThat(notes.findEntryId(saved.id(), day(9))).isEmpty();
    }

    @Test
    @DisplayName("판정: 날짜 이동이 UNIQUE 위반을 내지 않는다 — DB는 중간 상태에서 제약을 검사한다")
    void dateMoveDoesNotViolateUniqueMidway() {
        Note saved = seed(entry(day(9), "9일"));
        saved = repo.upsertEntry(saved.id(), fullMeta(), entry(day(10), "10일"), Aliases.empty());
        long noteId = saved.id();
        Note base = saved;

        // 예외가 나지 않는 것 자체가 판정이다 — 파일 구현에는 없던 실패 모드라 별도로 세운다.
        assertThatCode(() -> repo.applyEdit(noteId, day(9), editDraft(base, entry(day(10), "충돌 이동"))))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("ADR-74: 날짜 이동이 하위 행을 남기지 않는다 — 원본·덮어쓴 엔트리의 회차까지")
    void dateMoveLeavesNoOrphanRows() {
        // 이동은 삭제 둘(원본 date + 이동처 date) + 삽입 하나다. 어느 한 단이라도 하위를 빠뜨리면
        // DB는 아무 말도 하지 않고 고아가 쌓인다 — FK가 없어 이 단언이 유일한 안전망이다.
        Note saved = seed(entry(day(9), List.of(
                new Brew(recipe(15.0), new Tasting("9일 첫 잔", "9일 첫 잔", Rating.GOOD)),
                new Brew(recipe(16.0), new Tasting("9일 둘째 잔", "9일 둘째 잔", Rating.PERFECT)))));
        saved = repo.upsertEntry(saved.id(), fullMeta(),
                entry(day(10), List.of(new Brew(recipe(17.0), new Tasting("10일", "10일", Rating.GOOD)))),
                Aliases.empty());

        repo.applyEdit(saved.id(), day(9), editDraft(saved,
                entry(day(10), List.of(new Brew(recipe(18.0), new Tasting("이동", "이동", Rating.GOOD))))));
        flushAndClear();

        assertThat(rowCount("entry")).isOne();
        assertThat(rowCount("brew")).isOne();
        assertThat(rowCount("recipe")).isOne();
        assertThat(rowCount("tasting")).isOne();
    }

    // ─────────────────────── 거부 (V-9 · §2.3 · 소실) ───────────────────────

    @Test
    @DisplayName("V-9 이관: coffee_name 변경 시도는 거부되고 원본은 무변화 — 오타 정정도 예외 없이")
    void applyEditRejectsCoffeeNameChange() {
        Note saved = seed(entry(day(10), "원본 감상"));

        Note draft = draftOf(saved, new Sourced<>("커피베라 예가체프 G2", Source.USER), saved.roastery(),
                saved.aliases(), List.of(entry(day(10), "감상 수정")));

        assertThatThrownBy(() -> repo.applyEdit(saved.id(), day(10), draft))
                .isInstanceOf(IllegalArgumentException.class);

        // 거부는 쓰기 전에 일어나므로 롤백에 기대지 않고도 원본이 그대로다.
        assertThat(repo.findById(saved.id()).orElseThrow().entries())
                .satisfies(entries -> assertThat(tasteOf(entries.getFirst())).isEqualTo("원본 감상"));
    }

    @Test
    @DisplayName("data-model §2.3: draft 엔트리가 1건이 아니면 거부 — 수정 대상이 특정되지 않는다")
    void applyEditRequiresExactlyOneDraftEntry() {
        Note saved = seed(entry(day(10), "원본"));
        Note twoEntries = draftOf(saved, saved.coffeeName(), saved.roastery(), saved.aliases(),
                List.of(entry(day(10), "하나"), entry(day(11), "둘")));

        assertThatThrownBy(() -> repo.applyEdit(saved.id(), day(10), twoEntries))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("plan §7: 대상 노트 소실은 IllegalStateException — 호출부가 만료 안내로 수렴시킨다")
    void applyEditRejectsMissingNote() {
        Note saved = seed(entry(day(10), "원본"));

        assertThatThrownBy(() -> repo.applyEdit(99_999_999L, day(10), editDraft(saved, entry(day(10), "수정"))))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("plan §7: 대상 date 엔트리 소실은 IllegalStateException — 행이 판정 기준이다")
    void applyEditRejectsMissingTargetEntry() {
        Note saved = seed(entry(day(10), "원본"));

        assertThatThrownBy(() -> repo.applyEdit(saved.id(), day(11), editDraft(saved, entry(day(11), "수정"))))
                .isInstanceOf(IllegalStateException.class);
        // 던지기만 하고 아무것도 지우지 않았다 — 검증이 쓰기보다 앞이다.
        assertThat(repo.findById(saved.id()).orElseThrow().entries()).hasSize(1);
    }

    // ─────────────────────── 별칭 존치 (V-13) ───────────────────────

    @Test
    @DisplayName("V-13: 수정 세션은 별칭을 건드리지 않는다 — draft가 무엇을 실어도 원본 존치")
    void applyEditPreservesAliases() {
        Note saved = repo.upsertEntry(null, fullMeta(), entry(day(10), "원본"),
                new Aliases(List.of("예가체프 G1"), List.of("커피베라 성수")));

        // draft에 다른 별칭을 실어도 무시된다 — 별칭 갱신은 재기록 경로(관측 축적)만의 개념이다.
        Note draft = draftOf(saved, saved.coffeeName(), saved.roastery(),
                new Aliases(List.of("draft가 실은 별칭"), List.of("draft 로스터리")),
                List.of(entry(day(10), "수정")));
        Note updated = repo.applyEdit(saved.id(), day(10), draft);

        assertThat(updated.aliases().coffeeName()).containsExactly("예가체프 G1");
        assertThat(updated.aliases().roastery()).containsExactly("커피베라 성수");

        flushAndClear();
        assertThat(repo.findById(saved.id()).orElseThrow().aliases().coffeeName())
                .containsExactly("예가체프 G1");
    }

    // ────────────────────────────── 표본·헬퍼 ──────────────────────────────

    /** 노트 하나를 심는다 — 수정 테스트의 출발점. */
    private Note seed(Entry entry) {
        return repo.upsertEntry(null, fullMeta(), entry, Aliases.empty());
    }

    /**
     * 저장된 노트에서 대상 엔트리 1건만 실은 edit draft를 만든다(data-model §2.3).
     * <p>수정 파이프라인이 원본 노트를 복사해 필드를 덮는 형태 그대로다.
     */
    private static Note editDraft(Note base, Entry edited) {
        return draftOf(base, base.coffeeName(), base.roastery(), base.aliases(), List.of(edited));
    }

    /**
     * draft 사본 — 바꿀 컴포넌트만 인자로 받고 나머지는 원본에서 나른다.
     * <p>도메인에 {@code with*}를 두지 않은 것은 이 편의가 테스트에서만 필요하기 때문이다(§4 right-sizing).
     */
    private static Note draftOf(Note base, Sourced<String> coffeeName, Sourced<String> roastery,
                                Aliases aliases, List<Entry> entries) {
        return new Note(
                base.id(), coffeeName, roastery, base.beans(), base.roastLevel(),
                base.officialNotes(), aliases, base.sources(), entries,
                base.createdAt(), base.updatedAt());
    }

    /**
     * 실 커밋이 하는 일을 재현한다 — <b>flush 뒤에</b> 컨텍스트를 비운다.
     *
     * <p>{@code clear()}만 부르면 아직 나가지 않은 UPDATE가 <b>버려진다</b>. 수정 경로는 관리되는 엔티티의
     * 필드 갱신이라(노트 본문·엔트리 날짜) 그 UPDATE가 flush까지 pending으로 남는데, 자식 INSERT는
     * {@code IDENTITY} PK라 persist 즉시 나가버려 조립 질의의 auto-flush마저 트리거되지 않는다 —
     * 즉 비우기 전에 flush하지 않으면 <b>테스트만 갱신을 잃는다</b>(프로덕션은 트랜잭션 커밋이 flush한다).
     *
     * <p>저장소에 {@code flush()}를 넣어 맞추는 쪽은 택하지 않았다: 영속성 관리는 JPA에 맡기고 저장소는
     * 무엇을 고칠지만 정한다는 것이 이 계층의 선이다.
     */
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

    private static NoteMeta fullMeta() {
        return new NoteMeta(
                new Sourced<>("커피베라 예가체프 G1", Source.USER),
                new Sourced<>("커피베라", Source.USER),
                List.of(new Bean(new Sourced<>("에티오피아 예가체프", Source.SEARCH), new Sourced<>("워시드", Source.SEARCH))),
                new Sourced<>(null, Source.SEARCH),
                new Sourced<>(List.of("자스민", "베르가못"), Source.SEARCH),
                List.of("https://example.com/coffeevera"));
    }

    private static Recipe recipe(double doseG) {
        return new Recipe("핸드드립", doseG, null, null, null, null, null, null, null, null);
    }

    private static LocalDate day(int dayOfMonth) {
        return LocalDate.of(2026, 7, dayOfMonth);
    }

    private static Entry entry(LocalDate date, String taste) {
        return entry(date, List.of(new Brew(null, new Tasting(taste, taste, Rating.GOOD))));
    }

    // updatedAt은 감사 컬럼이 발급하므로 인자값은 버려진다(Q-5) — 표본에서는 자리만 채운다.
    private static Entry entry(LocalDate date, List<Brew> brews) {
        return new Entry(date, brews, OffsetDateTime.of(2000, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC));
    }

    /** 이 테스트의 감상 접근 헬퍼 — 회차 1개 전제. */
    private static String tasteOf(Entry entry) {
        return entry.brews().getFirst().tasting().myTaste();
    }
}
