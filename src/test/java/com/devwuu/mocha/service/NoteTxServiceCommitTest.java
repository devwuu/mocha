package com.devwuu.mocha.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.devwuu.mocha.domain.Aliases;
import com.devwuu.mocha.domain.Bean;
import com.devwuu.mocha.domain.Cup;
import com.devwuu.mocha.domain.TastingDay;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.NoteMeta;
import com.devwuu.mocha.domain.Rating;
import com.devwuu.mocha.domain.Recipe;
import com.devwuu.mocha.domain.Source;
import com.devwuu.mocha.domain.Sourced;
import com.devwuu.mocha.domain.Review;
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
 * TΔ5b (changes/0028-rdb-storage) — {@link NoteTxService#commit} (AC-Δ1·Δ3·Δ4).
 *
 * <p>둘을 함께 본다. 하나는 <b>병합 정책</b>(ADR-4·59, V-13)이고, 이것은 구
 * {@code JsonFileNoteRepositoryTest}가 지고 있던 계약 검증분의 이관이다 — 저장 매체가 바뀌어도 답이 같아야
 * 하는 것이 AC-Δ1이다. 다른 하나는 <b>파일에 없던 제약</b>(AC-Δ3·Δ4)으로, 이쪽은 이관이 아니라 신설이다.
 *
 * <p>제약 4종(V-1·V-3·V-8·V-10) 중 <b>둘은 도메인 타입이 이미 막는다</b> — {@code Rating}은 enum이고
 * {@code TastingDay.date}는 {@code LocalDate}라 저장소 인자로는 위반을 만들 수 없다. 그래서 그 둘은 네이티브
 * SQL로 우회해 넣는다. 우회가 곧 검증 대상이다: FK를 버린 이 스키마에서 psql 직접 편집을 막는 것은
 * CHECK와 타입뿐이고(ADR-75), 애플리케이션을 지나지 않는 삽입이 실제로 거부되는지는 그렇게만 관측된다.
 *
 * <p>{@code em.clear()}로 컨텍스트를 비우는 것은 TΔ5a와 같은 이유다 — 비우지 않으면 조회가 identity map을
 * 되돌려주고 DB를 지나지 않은 채 그린이 된다.
 */
@Transactional
class NoteTxServiceCommitTest extends PostgresIntegrationTest {

    @Autowired
    EntityManager em;

    @Autowired
    NoteEntityRepository notes;

    /**
     * 네이티브 SQL이 향할 스키마. JPQL은 {@code hibernate.default_schema}(={@code test})를 보지만 네이티브
     * 문장은 커넥션의 {@code search_path}(={@code public} — 개발 데이터)를 따른다. 1차 구현에서 이 간극을
     * 놓쳐 위반 삽입이 <b>개발 스키마의 빈 테이블에 닿아 성공</b>했다 — 즉 AC-Δ3 테스트가 위반을 위반으로
     * 관측하지 못했다. 테이블명에 스키마를 붙여 두 경로가 같은 행을 보게 한다.
     */
    @Value("${spring.jpa.properties.hibernate.default_schema}")
    String schema;

    NoteTxService repo;

    @BeforeEach
    void setUp() {
        repo = new NoteTxService(notes);
    }

    // ─────────────────────── 신규/기존 분기 (D-1) ───────────────────────

    @Test
    @DisplayName("D-1: noteId가 null이면 신규 노트 — id는 저장이 발급하고 meta·aliases가 그대로 심긴다")
    void nullNoteIdCreatesNewNote() {
        Note saved = repo.commit(null, fullMeta(), tastingDay(day(10), "새콤하고 좋았다"),
                new Aliases(List.of("예가체프 G1"), List.of("커피베라 성수")));
        em.clear();

        Note loaded = repo.findById(saved.id()).orElseThrow();
        assertThat(loaded.id()).isNotNull();
        assertThat(loaded.coffeeName().value()).isEqualTo("커피베라 예가체프 G1");
        assertThat(loaded.beans()).hasSize(1);
        assertThat(loaded.officialNotes().value()).containsExactly("자스민", "베르가못");
        assertThat(loaded.sources()).containsExactly("https://example.com/coffeevera");
        // 신규 노트에 한해 인자 aliases가 그 노트의 초기 별칭이 된다(AliasGenerator 산출, V-13).
        assertThat(loaded.aliases().coffeeName()).containsExactly("예가체프 G1");
        assertThat(loaded.aliases().roastery()).containsExactly("커피베라 성수");
        assertThat(loaded.tastingDays()).hasSize(1);
    }

    @Test
    @DisplayName("D-1: 소실된 noteId는 신규 생성으로 흡수하지 않고 거부 — 중복 노트가 생기면 사진·카드가 두 id로 갈린다")
    void missingNoteIdIsRejected() {
        assertThatThrownBy(() -> repo.commit(99_999_999L, fullMeta(), tastingDay(day(10), "감상"), Aliases.empty()))
                .isInstanceOf(IllegalStateException.class);

        // 던졌다는 것만으로는 부족하다 — 신규 생성으로 흡수한 뒤 조회에서 넘어져도 같은 예외가 난다.
        // 아무 행도 남지 않았음이 "흡수하지 않았다"의 관측이다(FK가 없어 DB는 이 자리를 막지 않는다).
        assertThat(repo.findAll()).isEmpty();
    }

    // ─────────────────────── 병합 정책 이관 (AC-Δ1) ───────────────────────

    @Test
    @DisplayName("AC-14: 같은 날 재기록 시 엔트리 수 불변(갱신만) — 이관: JsonFileNoteRepositoryTest")
    void sameDayUpsertKeepsSingleTastingDay() {
        long noteId = seed(tastingDay(day(10), "새콤하고 좋았다"));

        Note note = repo.commit(noteId, fullMeta(), tastingDay(day(10), "오늘은 좀 밍밍"), Aliases.empty());
        assertThat(note.tastingDays()).hasSize(1);
        assertThat(tasteOf(note.tastingDays().getFirst())).isEqualTo("오늘은 좀 밍밍");

        // DB에서 다시 읽어도 1건 — 옛 엔트리 행이 남아 있으면 여기서 2건이 된다.
        em.clear();
        assertThat(repo.findById(noteId).orElseThrow().tastingDays()).hasSize(1);
    }

    @Test
    @DisplayName("ADR-59/AC-14: 같은 날 회차 append — 에이전트 구성 배열로 통째 교체, 엔트리 수 불변·회차 증가")
    void sameDayCommitAppendsCupRound() {
        Cup first = new Cup(
                new Recipe("핸드드립", 15.0, 240.0, null, 160.0, 92.0, "210클릭 (매버릭 2.0)", null, null,
                        "첫 모금이 살짝 떫었으니 다음엔 220클릭으로"),
                new Review("새콤하고 좋았음", "새콤하고 좋았다", Rating.GOOD));
        long noteId = seed(tastingDay(day(18), List.of(first)));

        // 같은 날 2번째 시도 — 에이전트가 기존 회차를 포함해 append한 전체 배열을 구성한다(V-15 검증 통과분).
        Cup second = new Cup(
                new Recipe("핸드드립", 15.0, 240.0, null, 150.0, 92.0, "220클릭 (매버릭 2.0)", null, null, null),
                new Review("떫은맛 사라지고 단맛 올라옴", "떫은맛이 사라지고 단맛이 올라온다", Rating.PERFECT));
        Note note = repo.commit(noteId, fullMeta(), tastingDay(day(18), List.of(first, second)), Aliases.empty());

        assertThat(note.tastingDays()).hasSize(1);
        assertThat(note.tastingDays().getFirst().cups()).hasSize(2);

        // 왕복 후에도 회차 순서(= 회차 번호)가 유지된다 — 교체로 행이 새로 발급돼도 seq가 순서를 진다.
        em.clear();
        assertThat(repo.findById(noteId).orElseThrow().tastingDays().getFirst().cups())
                .extracting(b -> b.review().myTaste())
                .containsExactly("새콤하고 좋았음", "떫은맛 사라지고 단맛 올라옴");
    }

    @Test
    @DisplayName("ADR-59/AC-79: 기존 회차 지칭 병합 — 그 회차 review만 바뀐 배열로 교체, 회차 수 불변")
    void sameDayCommitMergesIntoReferredCupRound() {
        Recipe recipe = new Recipe("핸드드립", 15.0, 240.0, null, 160.0, 92.0, "210클릭 (매버릭 2.0)", null, null, null);
        long noteId = seed(tastingDay(day(18),
                List.of(new Cup(recipe, new Review("새콤하고 좋았음", "새콤하고 좋았다", Rating.GOOD)))));

        // "아까 내린 거 식으니까 더 맛있네" — 병합은 에이전트가 하고 서버는 append 없이 그대로 교체한다.
        Note note = repo.commit(noteId, fullMeta(), tastingDay(day(18),
                List.of(new Cup(recipe, new Review(
                        "새콤하고 좋았음. 식으니까 더 맛있음",
                        "새콤하고 좋았다 / 식으니까 더 맛있네", Rating.GOOD)))), Aliases.empty());

        assertThat(note.tastingDays()).hasSize(1);
        assertThat(note.tastingDays().getFirst().cups()).hasSize(1);
        Cup merged = note.tastingDays().getFirst().cups().getFirst();
        assertThat(merged.review().myTaste()).isEqualTo("새콤하고 좋았음. 식으니까 더 맛있음");
        assertThat(merged.review().rating()).isEqualTo(Rating.GOOD);
        assertThat(merged.recipe().grind()).isEqualTo("210클릭 (매버릭 2.0)");
    }

    @Test
    @DisplayName("ADR-75: 엔트리 교체가 하위 행을 남기지 않는다 — FK가 없어 고아는 조용히 쌓인다")
    void tastingDayReplacementLeavesNoOrphanRows() {
        // 교체는 삭제 후 재삽입이고, 그 삭제는 코드가 순서를 지고 있다(review·recipe → cup → tastingDay).
        // 한 단이라도 빠지면 DB는 아무 말도 하지 않고 재저장마다 고아가 늘어난다 — 여기가 유일한 안전망이다.
        long noteId = seed(tastingDay(day(10), List.of(
                new Cup(new Recipe("핸드드립", 15.0, null, null, null, null, null, null, null, null),
                        new Review("첫 잔", "첫 잔", Rating.GOOD)),
                new Cup(new Recipe("핸드드립", 16.0, null, null, null, null, null, null, null, null),
                        new Review("둘째 잔", "둘째 잔", Rating.PERFECT)))));

        // 같은 날, 회차 1개짜리로 교체 — 옛 회차 2개와 그 recipe·review가 전부 사라져야 한다.
        repo.commit(noteId, fullMeta(), tastingDay(day(10), List.of(
                new Cup(new Recipe("에스프레소", 18.0, null, null, null, null, null, null, null, null),
                        new Review("다시 내림", "다시 내림", Rating.GOOD)))), Aliases.empty());
        em.clear();

        assertThat(rowCount("tasting_day")).isOne();
        assertThat(rowCount("cup")).isOne();
        assertThat(rowCount("recipe")).isOne();
        assertThat(rowCount("review")).isOne();
    }

    @Test
    @DisplayName("AC-13: 다른 날 재기록 시 엔트리 추가 + 날짜 오름차순 — 나중 날짜를 먼저 심어도 정렬된다")
    void differentDayAppendsSortedByDate() {
        long noteId = seed(tastingDay(day(10), "10일"));

        Note note = repo.commit(noteId, fullMeta(), tastingDay(day(9), "9일"), Aliases.empty());

        assertThat(note.tastingDays()).extracting(TastingDay::date).containsExactly(day(9), day(10));
        assertThat(tasteOf(note.tastingDays().getFirst())).isEqualTo("9일");
    }

    @Test
    @DisplayName("ADR-4: 재기록은 노트 단위 메타를 갱신하지 않는다 — 원두 구성·로스팅·공식 노트 보존")
    void reRecordPreservesNoteLevelMeta() {
        long noteId = seed(tastingDay(day(10), "1일차"));

        // 이번 기록의 meta에는 원두·로스팅·공식 노트가 없다 — 보존이 아니라 갱신이면 여기서 비워진다.
        repo.commit(noteId, metaWithNames("커피베라 예가체프 G1", "커피베라"),
                tastingDay(day(11), "2일차"), Aliases.empty());
        em.clear();

        Note loaded = repo.findById(noteId).orElseThrow();
        assertThat(loaded.beans()).hasSize(1);
        assertThat(loaded.officialNotes().value()).containsExactly("자스민", "베르가못");
        assertThat(loaded.sources()).containsExactly("https://example.com/coffeevera");
    }

    // ─────────────────────── 별칭 축적 (V-13, AC-Δ4 of 0016) ───────────────────────

    @Test
    @DisplayName("V-13/TΔ3a: 명시 인자로 심은 별칭은 뒤에 붙고 첫 등장 순서가 보존된다")
    void addedAliasesKeepFirstSeenOrder() {
        // generated가 non-null인 경로 — 받은 표기를 그대로 심는다. 별칭에는 seq가 없고 id가 첫 등장
        // 순서를 지므로, 기존 행을 지우고 다시 넣으면 이 단언이 깨진다.
        Note seeded = repo.commit(null,
                metaWithNames("Ethiopia Chelbesa", "FroB"), tastingDay(day(9), "1일차"),
                new Aliases(List.of("에티오피아 첼베사"), List.of("프롭")));
        long noteId = seeded.id();

        Note r1 = repo.commit(noteId, metaWithNames("이디오피아 첼베사", "프로브"), tastingDay(day(10), "2일차"),
                new Aliases(List.of("이디오피아 첼베사"), List.of("프로브")));
        assertThat(r1.aliases().coffeeName()).containsExactly("에티오피아 첼베사", "이디오피아 첼베사");
        assertThat(r1.aliases().roastery()).containsExactly("프롭", "프로브");

        // 더할 것이 없는 재기록은 별칭을 그대로 둔다 — 빈 인자가 곧 "축적할 신규 표기 없음"이다.
        Note r2 = repo.commit(noteId, metaWithNames("이디오피아  첼베사", "FROB"), tastingDay(day(11), "3일차"),
                Aliases.empty());
        assertThat(r2.aliases().coffeeName()).containsExactly("에티오피아 첼베사", "이디오피아 첼베사");
        assertThat(r2.aliases().roastery()).containsExactly("프롭", "프로브");

        // DB 왕복에도 축적분과 순서가 그대로다.
        em.clear();
        assertThat(repo.findById(noteId).orElseThrow().aliases().coffeeName())
                .containsExactly("에티오피아 첼베사", "이디오피아 첼베사");
    }

    @Test
    @DisplayName("TΔ4a: 기존 노트 재기록에서도 인자 aliases는 심긴다 — generated가 있으면 그것을 쓴다")
    void aliasArgumentIsPlantedForExistingNote() {
        Note seeded = repo.commit(null, fullMeta(), tastingDay(day(10), "1일차"),
                new Aliases(List.of("예가체프 G1"), List.of()));

        Note re = repo.commit(seeded.id(), fullMeta(), tastingDay(day(11), "2일차"),
                new Aliases(List.of("두 번째로 들어온 별칭"), List.of("두 번째 로스터리")));

        assertThat(re.aliases().coffeeName()).containsExactly("예가체프 G1", "두 번째로 들어온 별칭");
        assertThat(re.aliases().roastery()).containsExactly("두 번째 로스터리");
    }

    @Test
    @DisplayName("V-13(TΔ4c): generated가 null이면 저장된 별칭을 읽어 늘어난 표기만 심는다")
    void accumulatesOnlyNewObservedAliasesWhenNotGenerated() {
        // TΔ4c: 이 계산이 여기 있는 것이 축 재정의의 실체다 — "무엇이 이미 별칭에 있는가"를 읽고
        // 그에 따라 쓰는 read-then-act가 같은 트랜잭션 안에 든다(TΔ4a는 이것을 트랜잭션 밖에 두는
        // 대가를 명시적으로 지고 있었다, delta D-9).
        long noteId = repo.commit(null, metaWithNames("예가체프 G1", "커피베라"), tastingDay(day(9), "1일차"),
                new Aliases(List.of("Yirgacheffe G1"), List.of())).id();
        em.clear();

        // 커피명은 정규화하면 기존 별칭과 같고(Yirgacheffe G1), 로스터리는 처음 보는 표기다.
        Note re = repo.commit(noteId, metaWithNames("yirgacheffe  g1", "커피베라 성수"),
                tastingDay(day(10), "2일차"), null);

        assertThat(re.aliases().coffeeName())
                .as("정규화 일치분은 다시 심지 않는다 — 행 중복·순서 붕괴를 막는 자리")
                .containsExactly("Yirgacheffe G1");
        assertThat(re.aliases().roastery()).containsExactly("커피베라 성수");
    }

    @Test
    @DisplayName("V-13(TΔ4c): 노트 표시값과 정규화 일치하는 관측 표기는 별칭에 넣지 않는다")
    void observedValueSameAsCanonicalIsNotAccumulated() {
        long noteId = repo.commit(null, metaWithNames("예가체프 G1", "커피베라"), tastingDay(day(9), "1일차"),
                Aliases.empty()).id();
        em.clear();

        Note re = repo.commit(noteId, metaWithNames("예가체프  G1", "커피베라"), tastingDay(day(10), "2일차"), null);

        assertThat(re.aliases().coffeeName()).isEmpty();
        assertThat(re.aliases().roastery()).isEmpty();
    }

    // ─────────────────────── 회차 순서는 seq가 소유 (AC-Δ4) ───────────────────────

    @Test
    @DisplayName("AC-Δ4: 조회 순서가 seq를 따른다 — seq를 밀면 삽입 순서·id와 무관하게 순서가 바뀐다")
    void cupOrderFollowsSeqNotInsertionOrder() {
        long noteId = seed(tastingDay(day(10), List.of(
                new Cup(null, new Review("첫 잔", "첫 잔", Rating.GOOD)),
                new Cup(null, new Review("둘째 잔", "둘째 잔", Rating.PERFECT)))));
        long tastingDayId = notes.findTastingDayId(noteId, day(10)).orElseThrow();

        // 먼저 심은 회차(seq 0, 낮은 id)를 seq 2로 민다 — 순서가 id·삽입 순서에 걸려 있으면 그대로일 것이고,
        // seq에 걸려 있으면 뒤로 간다. UNIQUE(tasting_day_id, seq) 때문에 빈 자리로 옮긴다.
        int moved = nativeQuery("UPDATE %s.cup SET seq = 2 WHERE tasting_day_id = :tastingDayId AND seq = 0")
                .setParameter("tastingDayId", tastingDayId)
                .executeUpdate();
        assertThat(moved).isOne(); // 대조를 실제로 만들었는지 — 0건이면 아래 단언이 무의미해진다.
        em.clear();

        assertThat(repo.findById(noteId).orElseThrow().tastingDays().getFirst().cups())
                .extracting(b -> b.review().myTaste())
                .containsExactly("둘째 잔", "첫 잔");
    }

    // ─────────────────────── 제약 위반 삽입 실패 4건 (AC-Δ3) ───────────────────────

    @Test
    @DisplayName("AC-Δ3/V-8: recipe 수치 0은 CHECK가 거부한다 — 저장 경로 정규화가 빠져도 DB에 남지 않는다")
    void violationV8IsRejectedByCheck() {
        // Recipe.normalize가 0을 드롭하지만 그 정규화는 검증기(CupRules)의 몫이고 저장소는 걸지 않는다 —
        // 여기 도달하면 삼키지 않고 거부한다(TΔ3c toRecipeEntity 계약).
        Recipe zeroDose = new Recipe("핸드드립", 0.0, 240.0, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> repo.commit(null, fullMeta(),
                tastingDay(day(10), List.of(new Cup(zeroDose, null))), Aliases.empty()))
                .hasStackTraceContaining("recipe_dose_g_check");
    }

    @Test
    @DisplayName("AC-Δ3/V-10: 같은 (note_id, tasted_on) 두 번째 행은 UNIQUE가 거부한다 — 하루 2엔트리 금지")
    void violationV10IsRejectedByUnique() {
        long noteId = seed(tastingDay(day(10), "10일"));

        // 저장소를 지나면 같은 날은 병합되므로(AC-14) 위반은 애플리케이션 밖에서만 만들어진다 — psql 직접
        // 편집이 그 자리다. FK 없는 스키마에서 하루 유일성을 지키는 것은 이 UNIQUE뿐이다(ADR-75).
        assertThatThrownBy(() -> insertTastingDayRow(noteId, "2026-07-10"))
                .hasStackTraceContaining("entry_note_id_tasted_on_key");
    }

    @Test
    @DisplayName("AC-Δ3/V-1: 4범주 밖 rating은 CHECK가 거부한다 — enum 타입 밖에서 들어와도")
    void violationV1IsRejectedByCheck() {
        // 감상 없는 회차로 심는다 — cup 행만 서고 review 자리가 비어 있어야 아래 네이티브 삽입이
        // PK 충돌이 아니라 CHECK에 걸린다.
        Recipe recipe = new Recipe("핸드드립", 15.0, null, null, null, null, null, null, null, null);
        long noteId = seed(tastingDay(day(10), List.of(new Cup(recipe, null))));
        long tastingDayId = notes.findTastingDayId(noteId, day(10)).orElseThrow();
        Number cupId = (Number) nativeQuery("SELECT id FROM %s.cup WHERE tasting_day_id = :tastingDayId")
                .setParameter("tastingDayId", tastingDayId).getSingleResult();

        assertThatThrownBy(() -> nativeQuery("""
                        INSERT INTO %s.review (cup_id, my_taste, my_taste_original, rating)
                        VALUES (:cupId, '감상', '감상', 'AWESOME')
                        """)
                        .setParameter("cupId", cupId.longValue())
                        .executeUpdate())
                .hasStackTraceContaining("tasting_rating_check");
    }

    @Test
    @DisplayName("AC-Δ3/V-3: 날짜 형식 위반은 date 타입이 거부한다 — 문자열 컬럼이었다면 통과했을 값")
    void violationV3IsRejectedByDateType() {
        long noteId = seed(tastingDay(day(10), "10일"));

        assertThatThrownBy(() -> insertTastingDayRow(noteId, "2026-13-99"))
                .hasStackTraceContaining("2026-13-99");
    }

    // ────────────────────────────── 표본·헬퍼 ──────────────────────────────

    /** 노트 하나를 심고 id를 준다 — 병합 테스트의 출발점(신규 갈래는 그 자체가 검증 대상이라 따로 본다). */
    private long seed(TastingDay tastingDay) {
        return repo.commit(null, fullMeta(), tastingDay, Aliases.empty()).id();
    }

    /** 감사 컬럼을 거치지 않고 엔트리 행을 직접 넣는다 — psql 직접 편집 대역(AC-Δ3). */
    private int insertTastingDayRow(long noteId, String tastedOn) {
        return nativeQuery("""
                        INSERT INTO %s.tasting_day (note_id, tasted_on, created_at, created_by, modified_at, modified_by)
                        VALUES (:noteId, CAST(:tastedOn AS DATE), now(), 'agent', now(), 'agent')
                        """)
                .setParameter("noteId", noteId)
                .setParameter("tastedOn", tastedOn)
                .executeUpdate();
    }

    /** {@code %s}에 스키마를 채워 네이티브 질의를 만든다 — 식별자는 파라미터로 바인딩할 수 없다. */
    private Query nativeQuery(String sql) {
        return em.createNativeQuery(sql.formatted(schema));
    }

    /**
     * 테이블 전체 행 수. 고아 판정은 엔티티 조회로는 볼 수 없다 — 부모를 통해서만 읽는 조립 경로(TΔ5a)가
     * 연결이 끊긴 행을 애초에 지나치기 때문이다. 테이블을 직접 세는 것이 그 사각을 여는 유일한 방법이다.
     */
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

    /** 커피명·로스터리만 담은 최소 메타 — 관측 표기 축적(V-13)과 메타 보존(ADR-4)의 입력. */
    private static NoteMeta metaWithNames(String coffeeName, String roastery) {
        return new NoteMeta(new Sourced<>(coffeeName, Source.USER), new Sourced<>(roastery, Source.USER),
                List.of(), null, null, List.of());
    }

    private static LocalDate day(int dayOfMonth) {
        return LocalDate.of(2026, 7, dayOfMonth);
    }

    private static TastingDay tastingDay(LocalDate date, String taste) {
        return tastingDay(date, List.of(new Cup(null, new Review(taste, taste, Rating.GOOD))));
    }

    // updatedAt은 감사 컬럼이 발급하므로 인자값은 버려진다(Q-5) — 표본에서는 자리만 채운다.
    private static TastingDay tastingDay(LocalDate date, List<Cup> cups) {
        return new TastingDay(date, cups, OffsetDateTime.of(2000, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC));
    }

    /** 이 테스트의 감상 접근 헬퍼 — 회차 1개 전제. */
    private static String tasteOf(TastingDay tastingDay) {
        return tastingDay.cups().getFirst().review().myTaste();
    }
}
