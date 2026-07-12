package com.devwuu.mocha.pipeline;

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

    private final CapturingLlmClient llm = new CapturingLlmClient();

    private NoteSearchService service(int maxRequery) {
        return new NoteSearchService(llm, MochaObjectMapper.create(), maxRequery, FIXED);
    }

    private static Note note(String slug, String coffeeName, String roastery, LocalDate... entryDates) {
        List<Entry> entries = java.util.Arrays.stream(entryDates)
                .map(date -> new Entry(date, "좋았다", Rating.GOOD, null, List.of(), OffsetDateTime.now(FIXED)))
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

        service(0).handle("두 번째", Optional.of(session), NOTES);

        String userPrompt = llm.captured.userPrompt();
        assertThat(userPrompt).contains("\"message\":\"두 번째\"");
        assertThat(userPrompt).contains("저번에 마신 예가체프"); // 기존 단서 누적
        assertThat(userPrompt).contains("\"presented_candidates\":[\"coffeevera-yirgacheffe-g1\",\"momos-waikiki\"]");
        assertThat(userPrompt).contains("\"coffee_name\":\"커피베라 예가체프 G1\"");
        assertThat(userPrompt).contains("\"roastery\":\"커피베라\"");
        assertThat(userPrompt).contains("\"official_notes\":[\"자스민\"]");
        assertThat(userPrompt).contains("\"last_tasted\":\"2026-07-01\""); // 최신 엔트리 날짜
    }

    @Test
    @DisplayName("스키마: candidate_slugs 배열 단일 필드를 strict로 강제한다 (data-model §4.2)")
    void enforcesSelectionSchema() {
        llm.response = new SearchSelection(List.of());

        service(0).handle("예가체프", Optional.empty(), NOTES);

        String schema = llm.captured.jsonSchema();
        assertThat(schema).contains("\"candidate_slugs\"");
        assertThat(schema).contains("\"additionalProperties\": false");
    }

    // --- AC-31~33: 3결과 분기 ---

    @Test
    @DisplayName("AC-31 재료: 단일 매치 → SINGLE_MATCH + 최신 엔트리 날짜의 hit + 세션에 후보·단서가 반영된다")
    void singleMatchOutcome() {
        llm.response = new SearchSelection(List.of("coffeevera-yirgacheffe-g1"));

        SearchOutcome outcome = service(0).handle("저번에 마신 예가체프 찾아줘", Optional.empty(), NOTES);

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

        SearchOutcome outcome = service(0).handle("작년에 마신 거", Optional.empty(), NOTES);

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

        SearchOutcome outcome = service(0).handle("5월쯤이었어", Optional.of(session), NOTES);

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

        SearchOutcome outcome = service(0).handle("예가체프", Optional.empty(), NOTES);

        assertThat(outcome.type()).isEqualTo(SearchOutcome.Type.NO_MATCH);
    }

    @Test
    @DisplayName("환각 필터: 실존·비실존이 섞이면 실존 slug만 남는다(순서 보존)")
    void keepsOnlyExistingSlugs() {
        llm.response = new SearchSelection(List.of("ghost-coffee", "momos-waikiki"));

        SearchOutcome outcome = service(0).handle("와이키키", Optional.empty(), NOTES);

        assertThat(outcome.type()).isEqualTo(SearchOutcome.Type.SINGLE_MATCH);
        assertThat(outcome.hits().get(0).slug()).isEqualTo("momos-waikiki");
    }

    // --- 재질문 상한 (spec FR-20/AC-33, mocha.search-session.max-requery) ---

    @Test
    @DisplayName("AC-33: 상한 설정 시 재질문 횟수가 상한에 도달한 세션의 무후보는 LIMIT_REACHED(세션 폐기 신호)다")
    void limitReachedWhenMaxRequeryExhausted() {
        SearchSession session = new SearchSession(List.of("예가체프"), List.of(), 2, SESSION_STARTED);
        llm.response = new SearchSelection(List.of());

        SearchOutcome outcome = service(2).handle("몰라 그냥 찾아봐", Optional.of(session), NOTES);

        assertThat(outcome.type()).isEqualTo(SearchOutcome.Type.LIMIT_REACHED);
        assertThat(outcome.session()).isNull();
        assertThat(outcome.hits()).isEmpty();
    }

    @Test
    @DisplayName("AC-33: 상한 도달 전에는 재질문을 계속한다 (maxRequery=2, requeryCount=1 → NO_MATCH)")
    void requeriesBelowLimit() {
        SearchSession session = new SearchSession(List.of("예가체프"), List.of(), 1, SESSION_STARTED);
        llm.response = new SearchSelection(List.of());

        SearchOutcome outcome = service(2).handle("작년 겨울쯤", Optional.of(session), NOTES);

        assertThat(outcome.type()).isEqualTo(SearchOutcome.Type.NO_MATCH);
        assertThat(outcome.session().requeryCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("AC-33: 상한 미설정(0)이면 무제한 — 재질문이 아무리 쌓여도 세션이 종료되지 않는다")
    void unlimitedRequeryWhenMaxIsZero() {
        SearchSession session = new SearchSession(List.of("예가체프"), List.of(), 99, SESSION_STARTED);
        llm.response = new SearchSelection(List.of());

        SearchOutcome outcome = service(0).handle("음 잘 모르겠어", Optional.of(session), NOTES);

        assertThat(outcome.type()).isEqualTo(SearchOutcome.Type.NO_MATCH);
        assertThat(outcome.session().requeryCount()).isEqualTo(100);
    }

    // --- 경계 ---

    @Test
    @DisplayName("노트가 0건이면 LLM을 호출하지 않고 무후보로 수렴한다")
    void skipsLlmWhenNoNotes() {
        SearchOutcome outcome = service(0).handle("예가체프 찾아줘", Optional.empty(), List.of());

        assertThat(llm.calls).isZero();
        assertThat(outcome.type()).isEqualTo(SearchOutcome.Type.NO_MATCH);
        assertThat(outcome.session().requeryCount()).isEqualTo(1);
    }
}
