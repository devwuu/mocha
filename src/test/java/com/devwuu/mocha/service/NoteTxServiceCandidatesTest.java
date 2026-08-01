package com.devwuu.mocha.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.devwuu.mocha.domain.Aliases;
import com.devwuu.mocha.domain.Brew;
import com.devwuu.mocha.domain.Entry;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.NoteCandidate;
import com.devwuu.mocha.domain.Rating;
import com.devwuu.mocha.domain.Source;
import com.devwuu.mocha.domain.Sourced;
import com.devwuu.mocha.domain.Tasting;
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
 * TΔ7 (changes/0029): {@link NoteTxService#findCandidates} — 매칭 후보 검색.
 *
 * <p><b>이 검색이 존재하는 이유가 검증의 축을 정한다</b>: 사용자가 변경 시트를 연 시점은 <i>에이전트의
 * 매칭 판정이 틀렸다</i>는 신호다(TΔ11). 그래서 여기서 다시 모델을 부르지 않고 — 방금 틀린 판정기를
 * 되부르는 셈이다 — 정규화 컬럼·별칭 인덱스(0028)로 결정론적으로 찾는다. 이 델타가 하네스를 확률적
 * 영역에서 결정론적 영역으로 옮기는 것이기도 하다(delta.md §6).
 *
 * <p>실 Postgres에 붙는 것이 필수다(백엔드 CLAUDE.md §5.2) — 검증 대상이 {@code LIKE} 이스케이프,
 * {@code NULLS LAST}, {@code MAX} 집계, 다중 {@code ORDER BY}라서 <b>전부 SQL 방언의 성질</b>이다.
 * 인메모리 대체에서의 그린은 아무것도 보증하지 않는다.
 */
@Transactional
class NoteTxServiceCandidatesTest extends PostgresIntegrationTest {

    private static final OffsetDateTime IGNORED = OffsetDateTime.of(2000, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    @Autowired
    EntityManager em;

    @Autowired
    NoteEntityRepository notes;

    NoteTxService repo;

    @BeforeEach
    void setUp() {
        repo = new NoteTxService(notes);
    }

    @Test
    @DisplayName("TΔ7: 커피명 부분일치로 좁힌다 — 시트가 실제로 좁혀지는지가 이 화면의 최소 동작이다")
    void narrowsByCoffeeNamePartialMatch() {
        insert("에티오피아 게뎁", "모모스커피", dates(LocalDate.of(2026, 7, 28)));
        insert("콜롬비아 엘 파라이소", "모모스커피", dates(LocalDate.of(2026, 5, 2)));
        em.clear();

        assertThat(repo.findCandidates("게뎁")).extracting(NoteCandidate::coffeeName)
                .containsExactly("에티오피아 게뎁");
    }

    @Test
    @DisplayName("TΔ7: 로스터리로도 찾는다 — 커피명이 기억 안 날 때 사용자가 아는 것이 로스터리다")
    void narrowsByRoastery() {
        insert("에티오피아 게뎁", "모모스커피", dates(LocalDate.of(2026, 7, 28)));
        insert("콜롬비아 엘 파라이소", "프릳츠", dates(LocalDate.of(2026, 5, 2)));
        em.clear();

        assertThat(repo.findCandidates("프릳츠")).extracting(NoteCandidate::coffeeName)
                .containsExactly("콜롬비아 엘 파라이소");
    }

    @Test
    @DisplayName("TΔ7: 별칭으로도 찾는다 — 사용자가 어느 표기로 치든 같은 노트가 잡혀야 한다(V-13)")
    void narrowsByAlias() {
        // AliasGenerator가 노트당 평생 1회 심는 표기 변형(ADR-37). 커피명 원문만 대조하면 이 셋 중
        // 저장된 표기 하나로만 찾을 수 있고, 그것이 무엇인지는 사용자가 알 방법이 없다.
        insertWithAliases("예가체프 G1", "커피베라",
                new Aliases(List.of("예가체프 지1", "예가체프 G-1"), List.of("Coffee Vera")));
        insert("에티오피아 게뎁", "모모스커피", dates(LocalDate.of(2026, 7, 28)));
        em.clear();

        assertThat(repo.findCandidates("예가체프 지1")).extracting(NoteCandidate::coffeeName)
                .containsExactly("예가체프 G1");
        // 로스터리 별칭도 같은 테이블에 산다 — kind로 갈릴 뿐 대조 축은 하나다.
        assertThat(repo.findCandidates("Coffee Vera")).extracting(NoteCandidate::coffeeName)
                .containsExactly("예가체프 G1");
    }

    @Test
    @DisplayName("TΔ7: 표기 흔들림(공백·대소문자)을 정규화가 흡수한다 — 검색어와 컬럼이 같은 함수를 지난다")
    void normalizationAbsorbsSpacingAndCase() {
        insertWithAliases("예가체프 G1", "커피베라", Aliases.empty());
        em.clear();

        // Aliases.normalize = 소문자화 + 모든 공백 제거. 셋 다 "예가체프g1"로 수렴한다.
        assertThat(repo.findCandidates("예가체프G1")).hasSize(1);
        assertThat(repo.findCandidates("예가체프 g1")).hasSize(1);
        assertThat(repo.findCandidates("  예가체프   G1  ")).hasSize(1);
    }

    @Test
    @DisplayName("TΔ7: 정렬은 커피명 → 로스터리 → 최근 시음일 내림차순 — 관련도 순위는 두지 않는다")
    void ordersByCoffeeNameThenRoasteryThenLatestDate() {
        // 삽입 순서를 의도적으로 뒤집어 심는다 — 질의가 정렬을 지지 않으면 여기서 갈린다.
        insert("에티오피아 게뎁", "프릳츠", dates(LocalDate.of(2026, 7, 2)));
        insert("에티오피아 게뎁 G1", "프릳츠", dates(LocalDate.of(2026, 6, 14)));
        insert("에티오피아 게뎁", "모모스커피", dates(LocalDate.of(2026, 7, 28)));
        em.clear();

        // 축 ①이 "에티오피아 게뎁" 둘을 앞으로, 그 안에서 축 ②(모모스커피 < 프릳츠)가 가른다.
        // 표본을 한글로만 둔 것은 의도다 — 스크립트가 섞이면 순서가 DB collation에 의존한다.
        assertThat(repo.findCandidates("게뎁"))
                .extracting(NoteCandidate::coffeeName, NoteCandidate::roastery)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("에티오피아 게뎁", "모모스커피"),
                        org.assertj.core.groups.Tuple.tuple("에티오피아 게뎁", "프릳츠"),
                        org.assertj.core.groups.Tuple.tuple("에티오피아 게뎁 G1", "프릳츠"));
    }

    @Test
    @DisplayName("TΔ7: 3축은 최근 시음일 내림차순 — 앞 두 축이 같은 경우에만 발동한다")
    void thirdAxisIsLatestDateDescending() {
        // 노트 정체성이 coffee_name+roastery라 앞 두 축이 대개 유일하게 결정한다. 3축이 실제로 갈리는
        // 것은 로스터리를 모르는 동명 노트가 여럿일 때다 — 실사용에서 도달 가능한 조합이라 박아 둔다.
        insert("게뎁 워시드", null, dates(LocalDate.of(2026, 3, 1)));
        insert("게뎁 워시드", null, dates(LocalDate.of(2026, 8, 1)));
        em.clear();

        assertThat(repo.findCandidates("게뎁")).extracting(NoteCandidate::latestDate)
                .containsExactly(LocalDate.of(2026, 8, 1), LocalDate.of(2026, 3, 1));
    }

    @Test
    @DisplayName("TΔ7: 최근 시음일은 엔트리 여러 건 중 가장 늦은 날짜다 — 노트당 한 줄로 접힌다")
    void latestDateIsTheMaximumAcrossEntries() {
        insert("에티오피아 게뎁", "모모스커피",
                dates(LocalDate.of(2026, 7, 3), LocalDate.of(2026, 7, 28), LocalDate.of(2026, 7, 11)));
        em.clear();

        // 엔트리 3건이 join으로 3행이 됐다가 groupBy로 접힌다 — 접지 않으면 시트에 같은 노트가 셋 뜬다.
        assertThat(repo.findCandidates("게뎁")).singleElement()
                .extracting(NoteCandidate::latestDate).isEqualTo(LocalDate.of(2026, 7, 28));
    }

    @Test
    @DisplayName("TΔ7: 로스터리 미상·엔트리 0건 노트도 후보다 — nullable 지점이 계약에 있는 이유다")
    void notesWithoutRoasteryOrEntriesAreStillCandidates() {
        // 엔트리 0건은 결손이 아니라 정상 상태다(삭제 직후가 그렇다). inner join이면 여기서 통째로 빠진다.
        insert("게뎁 워시드", null, List.of());
        em.clear();

        assertThat(repo.findCandidates("게뎁")).singleElement()
                .satisfies(candidate -> {
                    assertThat(candidate.roastery()).isNull();
                    assertThat(candidate.latestDate()).isNull();
                });
    }

    @Test
    @DisplayName("TΔ7: null은 뒤로 간다(NULLS LAST) — Postgres 기본값은 DESC에서 FIRST라 명시가 필요하다")
    void nullsSortLast() {
        insert("게뎁 워시드", null, List.of());
        insert("게뎁 워시드", "모모스커피", dates(LocalDate.of(2026, 7, 28)));
        em.clear();

        // 로스터리 미상이 목록 맨 위에 오면 "정보가 가장 적은 후보"가 먼저 보인다.
        assertThat(repo.findCandidates("게뎁")).extracting(NoteCandidate::roastery)
                .containsExactly("모모스커피", null);
    }

    @Test
    @DisplayName("TΔ7: 빈 검색어는 전건 — 커피명이 아직 안 뽑힌 상태에서도 시트가 쓸모 있어야 한다")
    void blankQueryReturnsEverything() {
        insert("에티오피아 게뎁", "모모스커피", dates(LocalDate.of(2026, 7, 28)));
        insert("콜롬비아 엘 파라이소", "프릳츠", dates(LocalDate.of(2026, 5, 2)));
        em.clear();

        assertThat(repo.findCandidates("")).hasSize(2);
        assertThat(repo.findCandidates("   ")).as("공백만 친 것도 빈 검색어로 수렴한다").hasSize(2);
        assertThat(repo.findCandidates(null)).as("q 파라미터 자체가 없는 요청").hasSize(2);
    }

    @Test
    @DisplayName("TΔ7: 후보 없음은 빈 목록이다 — 그때 사용자의 다음 행동이 [새 노트로 등록]이다")
    void noMatchYieldsEmptyList() {
        insert("에티오피아 게뎁", "모모스커피", dates(LocalDate.of(2026, 7, 28)));
        em.clear();

        assertThat(repo.findCandidates("케냐")).isEmpty();
    }

    @Test
    @DisplayName("TΔ7: LIKE 와일드카드가 검색어로 새지 않는다 — %만 쳐도 전건이 나오지 않는다")
    void likeWildcardsInTheQueryAreEscaped() {
        insert("에티오피아 게뎁", "모모스커피", dates(LocalDate.of(2026, 7, 28)));
        em.clear();

        // 이스케이프가 없으면 `%`가 "전부", `_`가 "아무 글자 하나"로 해석돼 시트가 무관한 노트를 보여준다.
        assertThat(repo.findCandidates("%")).as("%가 와일드카드로 샜다").isEmpty();
        assertThat(repo.findCandidates("_")).as("_가 와일드카드로 샜다").isEmpty();
        assertThat(repo.findCandidates("게%뎁")).as("검색어 안의 %도 리터럴이다").isEmpty();
    }

    @Test
    @DisplayName("TΔ7: 빈 저장소는 빈 목록 — 첫 실사용(노트 0건)에서 시트가 넘어지지 않는다")
    void emptyRepositoryYieldsEmptyList() {
        assertThat(repo.findCandidates("게뎁")).isEmpty();
        assertThat(repo.findCandidates("")).isEmpty();
    }

    // ────────────────────────────── 표본 ──────────────────────────────

    private void insert(String coffeeName, String roastery, List<LocalDate> entryDates) {
        insert(coffeeName, roastery, entryDates, Aliases.empty());
    }

    private void insertWithAliases(String coffeeName, String roastery, Aliases aliases) {
        insert(coffeeName, roastery, dates(LocalDate.of(2026, 7, 1)), aliases);
    }

    private void insert(String coffeeName, String roastery, List<LocalDate> entryDates, Aliases aliases) {
        repo.insert(new Note(
                null,
                new Sourced<>(coffeeName, Source.USER),
                roastery == null ? null : new Sourced<>(roastery, Source.USER),
                List.of(),
                null,
                null,
                aliases,
                List.of(),
                entryDates.stream().map(NoteTxServiceCandidatesTest::entry).toList(),
                IGNORED,
                IGNORED));
    }

    private static List<LocalDate> dates(LocalDate... dates) {
        return List.of(dates);
    }

    private static Entry entry(LocalDate date) {
        return new Entry(date, List.of(new Brew(null, new Tasting("맛있었음", "맛있었음", Rating.GOOD))), IGNORED);
    }
}
