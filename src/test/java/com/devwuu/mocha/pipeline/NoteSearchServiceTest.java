package com.devwuu.mocha.pipeline;

import com.devwuu.mocha.domain.Aliases;
import com.devwuu.mocha.domain.Entry;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.Rating;
import com.devwuu.mocha.domain.SearchSession;
import com.devwuu.mocha.domain.Sourced;
import com.devwuu.mocha.json.MochaObjectMapper;
import com.devwuu.mocha.llm.LlmClient;
import com.devwuu.mocha.llm.LlmRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TΔ5(changes/0011): NoteSearchService 계약 검증 — data-model §4.2 요청 조립, 3결과 분기(단일/복수/무후보),
 * 세션 누적 단서, 재질문 상한({@code mocha.search-session.max-requery}, 0=무제한)을 fake LlmClient로
 * 결정론적으로 확인한다(CLAUDE.md §5.3). (ref: spec FR-20/AC-31~33, plan ADR-25.)
 */
class NoteSearchServiceTest {

    /** 요청을 포착하고 준비된 선정 결과를 돌려주는 fake — SDK/실 API를 쓰지 않는다(§5.2). */
    private static final class CapturingLlmClient implements LlmClient {
        LlmRequest<?> captured;
        SearchSelection response = new SearchSelection(List.of());
        RuntimeException failure;
        int calls = 0;

        @Override
        @SuppressWarnings("unchecked")
        public <T> T complete(LlmRequest<T> request) {
            calls++;
            captured = request;
            if (failure != null) {
                throw failure;
            }
            return (T) response;
        }
    }

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-07-11T02:00:00Z"), SEOUL);
    private static final OffsetDateTime SESSION_STARTED = OffsetDateTime.now(FIXED).minusMinutes(10);
    private static final LocalDate TODAY = LocalDate.now(FIXED); // 2026-07-11(Asia/Seoul) — 상대 날짜 해석 근거(TΔ8)

    private final CapturingLlmClient llm = new CapturingLlmClient();

    private NoteSearchService service(int maxRequery) {
        return new NoteSearchService(llm, MochaObjectMapper.create(), maxRequery, FIXED);
    }

    private static Note note(String slug, String coffeeName, String roastery, LocalDate... entryDates) {
        List<Entry> entries = java.util.Arrays.stream(entryDates)
                .map(date -> new Entry(date, "좋았다", Rating.GOOD, null, OffsetDateTime.now(FIXED)))
                .toList();
        return new Note(
                slug, coffeeName == null ? null : Sourced.user(coffeeName),
                roastery == null ? null : Sourced.user(roastery), Sourced.search("에티오피아"), null, null,
                Sourced.search(List.of("자스민")), List.of(),
                entries, OffsetDateTime.now(FIXED), OffsetDateTime.now(FIXED));
    }

    private static final List<Note> NOTES = List.of(
            note("coffeevera-yirgacheffe-g1", "커피베라 예가체프 G1", "커피베라",
                    LocalDate.of(2026, 6, 1), LocalDate.of(2026, 7, 1)),
            note("momos-waikiki", "모모스 와이키키", "모모스", LocalDate.of(2026, 5, 20)));

    // --- data-model §4.2: 요청 조립 ---

    @Test
    @DisplayName("요청 조립: message·누적 clues(기존 세션 단서 + 이번 텍스트)·presented_candidates·노트 메타가 snake_case로 실린다 (data-model §4.2)")
    void assemblesRequestPayload() {
        SearchSession session = new SearchSession(
                List.of("저번에 마신 예가체프"), List.of("coffeevera-yirgacheffe-g1", "momos-waikiki"),
                0, SESSION_STARTED);
        llm.response = new SearchSelection(List.of("coffeevera-yirgacheffe-g1"));

        service(0).handle("두 번째", TODAY, Optional.of(session), NOTES);

        String userPrompt = llm.captured.userPrompt();
        assertThat(userPrompt).contains("\"message\":\"두 번째\"");
        assertThat(userPrompt).contains("\"today\":\"2026-07-11\""); // 상대 날짜 해석 근거(TΔ8)
        assertThat(userPrompt).contains("저번에 마신 예가체프"); // 기존 단서 누적
        assertThat(userPrompt).contains("\"presented_candidates\":[\"coffeevera-yirgacheffe-g1\",\"momos-waikiki\"]");
        assertThat(userPrompt).contains("\"edit_date_options\":[]"); // 날짜 선택 대기 아님 → 빈 목록(changes/0012)
        assertThat(userPrompt).contains("\"coffee_name\":\"커피베라 예가체프 G1\"");
        assertThat(userPrompt).contains("\"roastery\":\"커피베라\"");
        assertThat(userPrompt).contains("\"official_notes\":[\"자스민\"]");
        assertThat(userPrompt).contains("\"last_tasted\":\"2026-07-01\""); // 최신 엔트리 날짜
    }

    @Test
    @DisplayName("TΔ8: notes 페이로드에 aliases(coffee_name·roastery 통합)가 실린다 (data-model §4.2, changes/0016)")
    void includesAliasesInPayload() {
        Note aliased = new Note(
                "ethiopia-chelbesa", Sourced.user("Ethiopia Chelbesa"), Sourced.user("FroB"),
                Sourced.search("에티오피아"), null, null, Sourced.search(List.of()),
                new Aliases(List.of("에티오피아 첼베사"), List.of("프롭", "프로브")),
                List.of(), List.of(new Entry(LocalDate.of(2026, 7, 13), "좋았다", Rating.GOOD, null, OffsetDateTime.now(FIXED))),
                OffsetDateTime.now(FIXED), OffsetDateTime.now(FIXED));
        llm.response = new SearchSelection(List.of());

        service(0).handle("첼베사 찾아줘", TODAY, Optional.empty(), List.of(aliased));

        String userPrompt = llm.captured.userPrompt();
        // coffee_name·roastery 별칭이 하나의 통합 목록으로(표시값 자체는 aliases에 미수록, V-13)
        assertThat(userPrompt).contains("\"aliases\":[\"에티오피아 첼베사\",\"프롭\",\"프로브\"]");
    }

    @Test
    @DisplayName("스키마: candidate_slugs + 수정 신호(edit_requested·edit_target_date)를 strict로 강제한다 (data-model §4.2, changes/0012)")
    void enforcesSelectionSchema() {
        llm.response = new SearchSelection(List.of());

        service(0).handle("예가체프", TODAY, Optional.empty(), NOTES);

        String schema = llm.captured.jsonSchema();
        assertThat(schema).contains("\"candidate_slugs\"");
        assertThat(schema).contains("\"edit_requested\"");
        assertThat(schema).contains("\"edit_target_date\"");
        assertThat(schema).contains("\"additionalProperties\": false");
    }

    // --- AC-31~33: 3결과 분기 ---

    @Test
    @DisplayName("AC-31 재료: 단일 매치 → SINGLE_MATCH + 최신 엔트리 날짜의 hit + 세션에 후보·단서가 반영된다")
    void singleMatchOutcome() {
        llm.response = new SearchSelection(List.of("coffeevera-yirgacheffe-g1"));

        SearchOutcome outcome = service(0).handle("저번에 마신 예가체프 찾아줘", TODAY, Optional.empty(), NOTES);

        assertThat(outcome.type()).isEqualTo(SearchOutcome.Type.SINGLE_MATCH);
        assertThat(outcome.hits()).hasSize(1);
        SearchHit hit = outcome.hits().get(0);
        assertThat(hit.slug()).isEqualTo("coffeevera-yirgacheffe-g1");
        assertThat(hit.coffeeName()).isEqualTo("커피베라 예가체프 G1");
        assertThat(hit.latestDate()).isEqualTo(LocalDate.of(2026, 7, 1)); // 기본 최신 엔트리(FR-20)
        assertThat(outcome.session().clues()).containsExactly("저번에 마신 예가체프 찾아줘");
        assertThat(outcome.session().candidateSlugs()).containsExactly("coffeevera-yirgacheffe-g1");
        assertThat(outcome.session().createdAt()).isEqualTo(OffsetDateTime.now(FIXED)); // 새 세션 시작 시각
    }

    @Test
    @DisplayName("AC-32 재료: 복수 후보 → MULTIPLE_CANDIDATES, 관련도순(LLM 반환 순서)이 보존된다")
    void multipleCandidatesOutcome() {
        llm.response = new SearchSelection(List.of("momos-waikiki", "coffeevera-yirgacheffe-g1"));

        SearchOutcome outcome = service(0).handle("작년에 마신 거", TODAY, Optional.empty(), NOTES);

        assertThat(outcome.type()).isEqualTo(SearchOutcome.Type.MULTIPLE_CANDIDATES);
        assertThat(outcome.hits()).extracting(SearchHit::slug)
                .containsExactly("momos-waikiki", "coffeevera-yirgacheffe-g1");
        assertThat(outcome.session().candidateSlugs())
                .containsExactly("momos-waikiki", "coffeevera-yirgacheffe-g1");
    }

    @Test
    @DisplayName("AC-33 재료: 무후보 → NO_MATCH + 재질문 횟수 증가, 단서는 누적되고 직전 제시 후보는 유지된다")
    void noMatchIncrementsRequeryCount() {
        SearchSession session = new SearchSession(
                List.of("예가체프"), List.of("momos-waikiki"), 1, SESSION_STARTED);
        llm.response = new SearchSelection(List.of());

        SearchOutcome outcome = service(0).handle("5월쯤이었어", TODAY, Optional.of(session), NOTES);

        assertThat(outcome.type()).isEqualTo(SearchOutcome.Type.NO_MATCH);
        assertThat(outcome.session().requeryCount()).isEqualTo(2);
        assertThat(outcome.session().clues()).containsExactly("예가체프", "5월쯤이었어");
        assertThat(outcome.session().candidateSlugs()).containsExactly("momos-waikiki");
        assertThat(outcome.session().createdAt()).isEqualTo(SESSION_STARTED); // TTL 기준 시각 보존
    }

    // --- 환각 필터 (data-model §4.2) ---

    @Test
    @DisplayName("환각 필터: 실존하지 않는 slug만 반환되면 무후보로 수렴한다 (FR-14 matched_slug 재검증과 동일 정신)")
    void filtersHallucinatedSlugs() {
        llm.response = new SearchSelection(List.of("ghost-coffee", "another-ghost"));

        SearchOutcome outcome = service(0).handle("예가체프", TODAY, Optional.empty(), NOTES);

        assertThat(outcome.type()).isEqualTo(SearchOutcome.Type.NO_MATCH);
    }

    @Test
    @DisplayName("환각 필터: 실존·비실존이 섞이면 실존 slug만 남는다(순서 보존)")
    void keepsOnlyExistingSlugs() {
        llm.response = new SearchSelection(List.of("ghost-coffee", "momos-waikiki"));

        SearchOutcome outcome = service(0).handle("와이키키", TODAY, Optional.empty(), NOTES);

        assertThat(outcome.type()).isEqualTo(SearchOutcome.Type.SINGLE_MATCH);
        assertThat(outcome.hits().get(0).slug()).isEqualTo("momos-waikiki");
    }

    // --- 재질문 상한 (spec FR-20/AC-33, mocha.search-session.max-requery) ---

    @Test
    @DisplayName("AC-33: 상한 설정 시 재질문 횟수가 상한에 도달한 세션의 무후보는 LIMIT_REACHED(세션 폐기 신호)다")
    void limitReachedWhenMaxRequeryExhausted() {
        SearchSession session = new SearchSession(List.of("예가체프"), List.of(), 2, SESSION_STARTED);
        llm.response = new SearchSelection(List.of());

        SearchOutcome outcome = service(2).handle("몰라 그냥 찾아봐", TODAY, Optional.of(session), NOTES);

        assertThat(outcome.type()).isEqualTo(SearchOutcome.Type.LIMIT_REACHED);
        assertThat(outcome.session()).isNull();
        assertThat(outcome.hits()).isEmpty();
    }

    @Test
    @DisplayName("AC-33: 상한 도달 전에는 재질문을 계속한다 (maxRequery=2, requeryCount=1 → NO_MATCH)")
    void requeriesBelowLimit() {
        SearchSession session = new SearchSession(List.of("예가체프"), List.of(), 1, SESSION_STARTED);
        llm.response = new SearchSelection(List.of());

        SearchOutcome outcome = service(2).handle("작년 겨울쯤", TODAY, Optional.of(session), NOTES);

        assertThat(outcome.type()).isEqualTo(SearchOutcome.Type.NO_MATCH);
        assertThat(outcome.session().requeryCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("AC-33: 상한 미설정(0)이면 무제한 — 재질문이 아무리 쌓여도 세션이 종료되지 않는다")
    void unlimitedRequeryWhenMaxIsZero() {
        SearchSession session = new SearchSession(List.of("예가체프"), List.of(), 99, SESSION_STARTED);
        llm.response = new SearchSelection(List.of());

        SearchOutcome outcome = service(0).handle("음 잘 모르겠어", TODAY, Optional.of(session), NOTES);

        assertThat(outcome.type()).isEqualTo(SearchOutcome.Type.NO_MATCH);
        assertThat(outcome.session().requeryCount()).isEqualTo(100);
    }

    // --- TΔ4(changes/0012): 수정 전환 판정 (FR-21, ADR-27) ---

    @Test
    @DisplayName("FR-21: 수정 의도 + 단일 확정 + 엔트리 1건 → EDIT_TARGET_CONFIRMED(그 엔트리 date)")
    void editRequestWithSingleEntryConfirmsTarget() {
        SearchSession session = new SearchSession(
                List.of("모모스 와이키키 찾아줘"), List.of("momos-waikiki"), 0, SESSION_STARTED);
        llm.response = new SearchSelection(List.of("momos-waikiki"), true, null);

        SearchOutcome outcome = service(0).handle("그거 수정할래", TODAY, Optional.of(session), NOTES);

        assertThat(outcome.type()).isEqualTo(SearchOutcome.Type.EDIT_TARGET_CONFIRMED);
        assertThat(outcome.hits()).extracting(SearchHit::slug).containsExactly("momos-waikiki");
        assertThat(outcome.editDate()).isEqualTo(LocalDate.of(2026, 5, 20));
        assertThat(outcome.session().pendingEditSlug()).isEqualTo("momos-waikiki");
    }

    @Test
    @DisplayName("AC-42 재료: 수정 의도 + 단일 확정 + 엔트리 복수 → EDIT_DATE_CHOICES(오름차순 날짜 목록 + 선택 대기 세션)")
    void editRequestWithMultipleEntriesPresentsDateChoices() {
        llm.response = new SearchSelection(List.of("coffeevera-yirgacheffe-g1"), true, null);

        SearchOutcome outcome = service(0).handle("예가체프 기록 고쳐줘", TODAY, Optional.empty(), NOTES);

        assertThat(outcome.type()).isEqualTo(SearchOutcome.Type.EDIT_DATE_CHOICES);
        assertThat(outcome.editDateChoices())
                .containsExactly(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 7, 1)); // 제시 순서 = 선택 해석 기준
        assertThat(outcome.session().pendingEditSlug()).isEqualTo("coffeevera-yirgacheffe-g1");
        assertThat(outcome.session().candidateSlugs()).containsExactly("coffeevera-yirgacheffe-g1");
    }

    @Test
    @DisplayName("AC-42: 날짜 선택 대기 세션에서 목록 안 날짜가 해석되면 EDIT_TARGET_CONFIRMED — 요청에 edit_date_options가 실린다")
    void dateSelectionConfirmsEditTarget() {
        SearchSession session = new SearchSession(
                List.of("예가체프 고쳐줘"), List.of("coffeevera-yirgacheffe-g1"), 0, SESSION_STARTED,
                "coffeevera-yirgacheffe-g1");
        llm.response = new SearchSelection(List.of(), false, "2026-06-01");

        SearchOutcome outcome = service(0).handle("첫 번째 거", TODAY, Optional.of(session), NOTES);

        assertThat(llm.captured.userPrompt())
                .contains("\"edit_date_options\":[\"2026-06-01\",\"2026-07-01\"]"); // 제시 순서 그대로 컨텍스트 주입
        assertThat(outcome.type()).isEqualTo(SearchOutcome.Type.EDIT_TARGET_CONFIRMED);
        assertThat(outcome.editDate()).isEqualTo(LocalDate.of(2026, 6, 1));
        assertThat(outcome.hits()).extracting(SearchHit::slug).containsExactly("coffeevera-yirgacheffe-g1");
    }

    @Test
    @DisplayName("환각 필터 준용: 날짜 선택 답이 제시 목록 밖 날짜면 확정하지 않고 날짜 목록을 다시 제시한다")
    void rejectsDateOutsidePresentedOptions() {
        SearchSession session = new SearchSession(
                List.of("예가체프 고쳐줘"), List.of("coffeevera-yirgacheffe-g1"), 0, SESSION_STARTED,
                "coffeevera-yirgacheffe-g1");
        llm.response = new SearchSelection(List.of(), false, "2026-01-01"); // 목록에 없는 날짜(환각)

        SearchOutcome outcome = service(0).handle("1월 1일 거", TODAY, Optional.of(session), NOTES);

        assertThat(outcome.type()).isEqualTo(SearchOutcome.Type.EDIT_DATE_CHOICES);
        assertThat(outcome.editDateChoices())
                .containsExactly(LocalDate.of(2026, 6, 1), LocalDate.of(2026, 7, 1));
        assertThat(outcome.session().pendingEditSlug()).isEqualTo("coffeevera-yirgacheffe-g1"); // 선택 대기 유지
    }

    @Test
    @DisplayName("FR-21: 수정 의도라도 후보가 복수면 MULTIPLE_CANDIDATES — 대상 확정(단일)이 먼저다")
    void editRequestWithMultipleCandidatesKeepsListFlow() {
        llm.response = new SearchSelection(
                List.of("coffeevera-yirgacheffe-g1", "momos-waikiki"), true, null);

        SearchOutcome outcome = service(0).handle("저번에 마신 거 고치고 싶어", TODAY, Optional.empty(), NOTES);

        assertThat(outcome.type()).isEqualTo(SearchOutcome.Type.MULTIPLE_CANDIDATES);
        assertThat(outcome.session().pendingEditSlug()).isNull();
    }

    @Test
    @DisplayName("방어: 선택 대기 대상 slug가 소실(비실존)이면 일반 검색 턴으로 복귀한다")
    void fallsBackToNormalSearchWhenPendingEditSlugVanished() {
        SearchSession session = new SearchSession(
                List.of("예가체프 고쳐줘"), List.of("ghost-coffee"), 0, SESSION_STARTED, "ghost-coffee");
        llm.response = new SearchSelection(List.of());

        SearchOutcome outcome = service(0).handle("음", TODAY, Optional.of(session), NOTES);

        assertThat(outcome.type()).isEqualTo(SearchOutcome.Type.NO_MATCH); // 날짜 재제시가 아니라 일반 재질문
        assertThat(outcome.session().pendingEditSlug()).isNull();
    }

    // --- 경계 ---

    @Test
    @DisplayName("노트가 0건이면 LLM을 호출하지 않고 무후보로 수렴한다")
    void skipsLlmWhenNoNotes() {
        SearchOutcome outcome = service(0).handle("예가체프 찾아줘", TODAY, Optional.empty(), List.of());

        assertThat(llm.calls).isZero();
        assertThat(outcome.type()).isEqualTo(SearchOutcome.Type.NO_MATCH);
        assertThat(outcome.session().requeryCount()).isEqualTo(1);
    }
}
