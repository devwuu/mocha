package com.devwuu.mocha.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

import com.devwuu.mocha.domain.Aliases;
import com.devwuu.mocha.domain.Bean;
import com.devwuu.mocha.domain.Brew;
import com.devwuu.mocha.domain.Entry;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.NoteCursor;
import com.devwuu.mocha.domain.NoteFilter;
import com.devwuu.mocha.domain.NoteListItem;
import com.devwuu.mocha.domain.NotePage;
import com.devwuu.mocha.domain.Rating;
import com.devwuu.mocha.domain.Source;
import com.devwuu.mocha.domain.Sourced;
import com.devwuu.mocha.domain.Review;
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
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * TΔ5a (changes/0029): {@link NoteTxService#findNotes} — 갤러리 목록 조회.
 *
 * <p><b>검증의 축은 계약이 선언한 것 그대로다</b>(정본 {@code contract/note-list.contract.json}): 정렬 2축 ·
 * 커서 페이징 · 필터 4축의 <i>축 안 OR / 축 간 AND</i> · 필터가 적용된 총수 · <b>필터가 적용되지 않는</b>
 * facet · 썸네일 선택. 화면이 먼저 서고 API가 뒤따르는 슬라이스(D-10 ③)에서, 여기가 그 계약이 실제로
 * 지켜지는지 답하는 자리다.
 *
 * <p>실 Postgres에 붙는 것이 필수다(백엔드 CLAUDE.md §5.2) — 검증 대상이 {@code NULLS LAST}, {@code MAX}
 * 집계 + {@code HAVING} 커서 비교, {@code EXISTS} 상관 서브쿼리, {@code LIKE} 이스케이프라서 <b>전부 SQL
 * 방언의 성질</b>이다. 인메모리 대체에서의 그린은 아무것도 보증하지 않는다.
 */
@Transactional
class NoteTxServiceListTest extends PostgresIntegrationTest {

    private static final OffsetDateTime IGNORED = OffsetDateTime.of(2000, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    /** 페이지 크기를 넉넉히 — 페이징 자체가 대상인 테스트만 작은 값을 준다. */
    private static final int ALL = 100;

    @Autowired
    EntityManager em;

    @Autowired
    NoteEntityRepository notes;

    NoteTxService repo;

    @BeforeEach
    void setUp() {
        repo = new NoteTxService(notes);
    }

    // ────────────────────────────── 정렬 ──────────────────────────────

    @Test
    @DisplayName("TΔ5a: 최근 시음일 내림차순 → note_id 내림차순 — 갤러리가 답하는 질문이 '요즘 뭘 마셨나'다")
    void sortsByRecencyThenIdDescending() {
        long older = insert("케냐 AA 키리냐가", "커피베라", date(2026, 6, 1));
        // 같은 날 마신 노트 둘 — 뒤 축이 없으면 페이지 경계에서 순서가 흔들린다.
        long sameDayFirst = insert("에티오피아 게뎁", "모모스커피", date(2026, 7, 2));
        long sameDayLater = insert("에티오피아 게뎁", "프릳츠", date(2026, 7, 2));
        em.clear();

        assertThat(page(NoteFilter.none(), null, ALL).notes()).extracting(NoteListItem::noteId)
                .containsExactly(sameDayLater, sameDayFirst, older);
    }

    @Test
    @DisplayName("TΔ5a: 엔트리 없는 노트는 맨 뒤다(NULLS LAST) — Postgres 기본값은 DESC에서 FIRST라 명시가 필요하다")
    void notesWithoutEntriesSortLast() {
        long withoutEntries = insert("게뎁 워시드", null);
        long withEntries = insert("에티오피아 게뎁", "모모스커피", date(2026, 7, 2));
        em.clear();

        // 명시하지 않으면 "아직 아무것도 안 마신 노트"가 갤러리 맨 위를 차지한다.
        assertThat(page(NoteFilter.none(), null, ALL).notes()).extracting(NoteListItem::noteId)
                .containsExactly(withEntries, withoutEntries);
    }

    @Test
    @DisplayName("TΔ5a: 엔트리가 여럿이면 가장 최근 날짜가 정렬 키다 — 노트의 위치는 마지막으로 마신 날이 정한다")
    void latestDateIsTheMaxOfEntries() {
        insert("에티오피아 게뎁", "프릳츠", date(2026, 6, 28), date(2026, 7, 2));
        insert("케냐 AA", "커피베라", date(2026, 6, 30));
        em.clear();

        assertThat(page(NoteFilter.none(), null, ALL).notes())
                .extracting(NoteListItem::coffeeName, NoteListItem::latestDate)
                .containsExactly(
                        tuple("에티오피아 게뎁", LocalDate.of(2026, 7, 2)),
                        tuple("케냐 AA", LocalDate.of(2026, 6, 30)));
    }

    // ────────────────────────────── 페이징 ──────────────────────────────

    @Test
    @DisplayName("TΔ5a: 커서로 이어 읽으면 겹치지도 빠뜨리지도 않는다 — 무한 스크롤이 성립하는 최소 조건")
    void cursorPagingCoversEveryNoteExactlyOnce() {
        long a = insert("A", "프릳츠", date(2026, 7, 3));
        long b = insert("B", "프릳츠", date(2026, 7, 2));
        long c = insert("C", "프릳츠", date(2026, 7, 1));
        // 마지막 둘은 시음일이 없다 — NULLS LAST 구간에서도 커서가 이어지는지가 이 표본의 값이다.
        long d = insert("D", "프릳츠");
        long e = insert("E", "프릳츠");
        em.clear();

        List<Long> read = new ArrayList<>();
        NoteCursor cursor = null;
        do {
            NotePage page = page(NoteFilter.none(), cursor, 2);
            page.notes().forEach(note -> read.add(note.noteId()));
            cursor = page.nextCursor();
        } while (cursor != null);

        // 시음일 있는 셋이 날짜 내림차순, 그 뒤 시음일 없는 둘이 id 내림차순. 한 건도 겹치지 않는다.
        assertThat(read).containsExactly(a, b, c, e, d);
    }

    @Test
    @DisplayName("TΔ5a: 마지막 페이지의 next_cursor는 null — 그것이 '더 없다'의 유일한 신호다")
    void lastPageHasNoCursor() {
        insert("A", "프릳츠", date(2026, 7, 3));
        insert("B", "프릳츠", date(2026, 7, 2));
        em.clear();

        NotePage first = page(NoteFilter.none(), null, 1);
        assertThat(first.nextCursor()).isNotNull();
        assertThat(page(NoteFilter.none(), first.nextCursor(), 1).nextCursor())
                .as("남은 것이 정확히 한 건이면 그 페이지가 마지막이다").isNull();
    }

    @Test
    @DisplayName("TΔ5a: 총수는 페이지 크기와 무관하고 필터를 따른다 — 헤더의 'N편의 기록'이 그 값이다")
    void totalFollowsTheFilterNotThePage() {
        insert("에티오피아 게뎁", "모모스커피", date(2026, 7, 2));
        insert("에티오피아 첼베사", "커피베라", date(2026, 7, 16));
        insert("케냐 AA", "커피베라", date(2026, 6, 1));
        em.clear();

        assertThat(page(NoteFilter.none(), null, 1).total()).isEqualTo(3);
        assertThat(page(roastery("커피베라"), null, 1).total())
                .as("필터를 걸면 헤더 숫자도 함께 줄어든다").isEqualTo(2);
    }

    // ────────────────────────────── 필터 ──────────────────────────────

    @Test
    @DisplayName("TΔ5a: 같은 축 안은 OR — '프릳츠 아니면 모모스'가 조건 하나로 표현된다")
    void sameAxisCombinesWithOr() {
        insert("A", "프릳츠", date(2026, 7, 3));
        insert("B", "모모스커피", date(2026, 7, 2));
        insert("C", "커피베라", date(2026, 7, 1));
        em.clear();

        assertThat(page(roastery("프릳츠", "모모스커피"), null, ALL).notes())
                .extracting(NoteListItem::coffeeName).containsExactly("A", "B");
    }

    @Test
    @DisplayName("TΔ5a: 축 간은 AND — 필터를 더할수록 좁아진다")
    void differentAxesCombineWithAnd() {
        insert("A", "프릳츠", List.of(bean("에티오피아 예가체프", "워시드")), entry(date(2026, 7, 3), Rating.GOOD));
        insert("B", "프릳츠", List.of(bean("콜롬비아 우일라", "내추럴")), entry(date(2026, 7, 2), Rating.GOOD));
        insert("C", "모모스커피", List.of(bean("에티오피아 게뎁", "워시드")), entry(date(2026, 7, 1), Rating.GOOD));
        em.clear();

        NoteFilter filter = new NoteFilter(null, List.of("프릳츠"), List.of("워시드"), null, List.of());
        assertThat(page(filter, null, ALL).notes()).extracting(NoteListItem::coffeeName).containsExactly("A");
    }

    @Test
    @DisplayName("TΔ5a: 가공방식은 원두 하나라도 걸리면 남는다 — 블렌드에서 구성 원두마다 가공이 다르다")
    void processMatchesAnyBean() {
        insert("블렌드", "프릳츠",
                List.of(bean("에티오피아 게뎁", "워시드"), bean("콜롬비아 우일라", "내추럴")),
                entry(date(2026, 7, 3), Rating.GOOD));
        insert("싱글", "프릳츠", List.of(bean("케냐 AA", "워시드")), entry(date(2026, 7, 2), Rating.GOOD));
        em.clear();

        assertThat(page(process("내추럴"), null, ALL).notes())
                .extracting(NoteListItem::coffeeName).containsExactly("블렌드");
    }

    @Test
    @DisplayName("TΔ5a: 원산지는 원두 설명의 부분일치다 — 원산지 컬럼이 없어 자유 텍스트로 근사한다(ADR-53)")
    void originIsAPartialMatchOnBeanDescription() {
        insert("A", "프릳츠", List.of(bean("에티오피아 예가체프 헤어룸", "워시드")), entry(date(2026, 7, 3), Rating.GOOD));
        insert("B", "프릳츠", List.of(bean("콜롬비아 우일라 카투라", "워시드")), entry(date(2026, 7, 2), Rating.GOOD));
        em.clear();

        assertThat(page(origin("에티오피아"), null, ALL).notes())
                .extracting(NoteListItem::coffeeName).containsExactly("A");
        // 부분일치라 품종·농장으로도 걸린다 — 표기에 의존하는 것이 이 축이 지는 대가다.
        assertThat(page(origin("우일라"), null, ALL).notes())
                .extracting(NoteListItem::coffeeName).containsExactly("B");
    }

    @Test
    @DisplayName("TΔ5a: 평가는 회차 하나라도 그 값이면 남는다 — 같은 날 두 번 내려 평가가 갈릴 수 있다")
    void ratingMatchesAnyBrew() {
        insert("A", "프릳츠", List.of(), new Entry(LocalDate.of(2026, 7, 3), List.of(
                brew(Rating.BAD), brew(Rating.PERFECT)), IGNORED));
        insert("B", "프릳츠", List.of(), entry(date(2026, 7, 2), Rating.BAD));
        em.clear();

        assertThat(page(rating(Rating.PERFECT), null, ALL).notes())
                .extracting(NoteListItem::coffeeName).containsExactly("A");
    }

    @Test
    @DisplayName("TΔ5a: 검색어는 커피명·로스터리·별칭을 정규화 기준으로 본다 — 후보 시트와 같은 축이다(V-13)")
    void queryMatchesTheSameAxesAsCandidateSearch() {
        insertWithAliases("예가체프 G1", "커피베라", new Aliases(List.of("예가체프 지1"), List.of()));
        insert("에티오피아 게뎁", "모모스커피", date(2026, 7, 2));
        em.clear();

        // 갤러리와 후보 시트가 다른 규칙을 쓰면 같은 앱 안에서 검색이 두 벌이 된다.
        assertThat(page(query("예가체프 지1"), null, ALL).notes())
                .extracting(NoteListItem::coffeeName).containsExactly("예가체프 G1");
        assertThat(page(query("모모스"), null, ALL).notes())
                .extracting(NoteListItem::coffeeName).containsExactly("에티오피아 게뎁");
        // 표기 흔들림 흡수도 같다 — 정규화 컬럼과 같은 함수를 지나기 때문이다.
        assertThat(page(query("예가체프g1"), null, ALL).notes()).hasSize(1);
    }

    @Test
    @DisplayName("TΔ5a: 필터가 전부 걸러낸 결과는 빈 목록이다 — 오류가 아니라 정상 조작이다")
    void filteredToNothingIsAnEmptyPage() {
        insert("에티오피아 게뎁", "모모스커피", date(2026, 7, 2));
        em.clear();

        NotePage page = page(roastery("프릳츠"), null, ALL);
        assertThat(page.notes()).isEmpty();
        assertThat(page.total()).isZero();
        assertThat(page.nextCursor()).isNull();
    }

    // ────────────────────────────── facet ──────────────────────────────

    @Test
    @DisplayName("TΔ5a: facet은 저장분 전체다 — 필터로 좁히면 고른 것을 되돌릴 수 없다(칩이 잠긴다)")
    void facetsIgnoreTheFilter() {
        insert("A", "프릳츠", List.of(bean("에티오피아 게뎁", "워시드")), entry(date(2026, 7, 3), Rating.GOOD));
        insert("B", "모모스커피", List.of(bean("콜롬비아 우일라", "내추럴")), entry(date(2026, 7, 2), Rating.GOOD));
        em.clear();

        NotePage filtered = page(roastery("프릳츠"), null, ALL);

        assertThat(filtered.notes()).hasSize(1);
        // 결과에는 프릳츠뿐이지만 칩에는 둘 다 있어야 사용자가 필터를 바꿔 갈 수 있다.
        assertThat(filtered.facets().roastery()).containsExactly("모모스커피", "프릳츠");
        assertThat(filtered.facets().process()).containsExactly("내추럴", "워시드");
    }

    @Test
    @DisplayName("TΔ5a: facet에 중복도 null도 없다 — 로스터리 미상 노트는 선택지를 만들지 않는다")
    void facetsAreDistinctAndSkipNulls() {
        insert("A", "프릳츠", date(2026, 7, 3));
        insert("B", "프릳츠", date(2026, 7, 2));
        insert("C", null, date(2026, 7, 1));
        em.clear();

        assertThat(page(NoteFilter.none(), null, ALL).facets().roastery()).containsExactly("프릳츠");
    }

    @Test
    @DisplayName("TΔ5a: 빈 저장소는 빈 페이지 — 첫 실사용(노트 0건)에서 갤러리가 넘어지지 않는다")
    void emptyRepositoryYieldsAnEmptyPage() {
        NotePage page = page(NoteFilter.none(), null, ALL);

        assertThat(page.notes()).isEmpty();
        assertThat(page.total()).isZero();
        assertThat(page.nextCursor()).isNull();
        assertThat(page.facets().roastery()).isEmpty();
        assertThat(page.facets().process()).isEmpty();
    }

    // ────────────────────────────── 썸네일 ──────────────────────────────

    @Test
    @DisplayName("TΔ5a: 썸네일은 가장 최근 날짜의 첫 장 — 상세의 히어로와 같은 사진이라 맥락이 끊기지 않는다")
    void thumbnailIsTheFirstPhotoOfTheLatestDate() {
        long noteId = insert("에티오피아 게뎁", "프릳츠", date(2026, 6, 28), date(2026, 7, 2));
        repo.attachPhotos(noteId, LocalDate.of(2026, 6, 28), List.of("photos/1-프릳츠-게뎁/2026-06-28/old.jpg"));
        repo.attachPhotos(noteId, LocalDate.of(2026, 7, 2),
                List.of("photos/1-프릳츠-게뎁/2026-07-02/first.jpg", "photos/1-프릳츠-게뎁/2026-07-02/second.jpg"));
        em.clear();

        assertThat(page(NoteFilter.none(), null, ALL).notes()).singleElement()
                .extracting(NoteListItem::photoPath)
                .isEqualTo("photos/1-프릳츠-게뎁/2026-07-02/first.jpg");
    }

    @Test
    @DisplayName("TΔ5a: 사진 없는 노트는 썸네일이 null — 발화만으로 기록한 노트가 정상 상태다")
    void notesWithoutPhotosHaveNoThumbnail() {
        insert("에티오피아 게뎁", "프릳츠", date(2026, 7, 2));
        em.clear();

        assertThat(page(NoteFilter.none(), null, ALL).notes()).singleElement()
                .extracting(NoteListItem::photoPath).isNull();
    }

    // ────────────────────────────── 표본 ──────────────────────────────

    private NotePage page(NoteFilter filter, NoteCursor cursor, int pageSize) {
        return repo.findNotes(filter, cursor, pageSize);
    }

    private static NoteFilter roastery(String... values) {
        return new NoteFilter(null, List.of(values), List.of(), null, List.of());
    }

    private static NoteFilter process(String... values) {
        return new NoteFilter(null, List.of(), List.of(values), null, List.of());
    }

    private static NoteFilter origin(String value) {
        return new NoteFilter(null, List.of(), List.of(), value, List.of());
    }

    private static NoteFilter rating(Rating... values) {
        return new NoteFilter(null, List.of(), List.of(), null, List.of(values));
    }

    private static NoteFilter query(String value) {
        return new NoteFilter(value, List.of(), List.of(), null, List.of());
    }

    private long insert(String coffeeName, String roastery, LocalDate... dates) {
        return insert(coffeeName, roastery, List.of(),
                Arrays.stream(dates).map(date -> entry(date, Rating.GOOD)).toArray(Entry[]::new));
    }

    private long insert(String coffeeName, String roastery, List<Bean> beans, Entry... entries) {
        return insert(coffeeName, roastery, beans, Aliases.empty(), entries);
    }

    private void insertWithAliases(String coffeeName, String roastery, Aliases aliases) {
        insert(coffeeName, roastery, List.of(), aliases, entry(date(2026, 7, 1), Rating.GOOD));
    }

    private long insert(String coffeeName, String roastery, List<Bean> beans, Aliases aliases, Entry... entries) {
        return repo.insert(new Note(
                null,
                new Sourced<>(coffeeName, Source.USER),
                roastery == null ? null : new Sourced<>(roastery, Source.USER),
                beans,
                null,
                null,
                aliases,
                List.of(),
                List.of(entries),
                IGNORED,
                IGNORED)).id();
    }

    private static Bean bean(String description, String process) {
        return new Bean(new Sourced<>(description, Source.SEARCH), new Sourced<>(process, Source.SEARCH));
    }

    private static LocalDate date(int year, int month, int day) {
        return LocalDate.of(year, month, day);
    }

    private static Entry entry(LocalDate date, Rating rating) {
        return new Entry(date, List.of(brew(rating)), IGNORED);
    }

    private static Brew brew(Rating rating) {
        return new Brew(null, new Review("맛있었음", "맛있었음", rating));
    }
}
