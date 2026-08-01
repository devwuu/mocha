package com.devwuu.mocha.agent.turn;

import com.devwuu.mocha.domain.Bean;
import com.devwuu.mocha.domain.MatchInfo;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.Source;
import com.devwuu.mocha.domain.Sourced;
import com.devwuu.mocha.llm.SearchClient;
import com.devwuu.mocha.llm.SearchQuery;
import com.devwuu.mocha.llm.SearchResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 검색 보강 정책 — 발동 조건(트리거)·빈 필드만 채움(V-6)·무결과 통과(AC-12)
 * (ref: changes/0029-app-interface delta.md#D-16, tasks TΔ24b; spec FR-3/AC-9).
 * <p><b>결정론 레이어라 여기가 판정처다</b>: D-16이 보강을 «모델 재량 tool»에서 되돌린 이유가
 * 정확히 이것이다 — 확률적 영역에 있던 동안은 안 돌아도 아무 테스트도 빨개지지 않았다(delta D-16 ②).
 * 외부 의존(검색)은 fake — 웹 미접촉(모듈 CLAUDE.md §5.2).
 */
class TurnProposalEnricherTest {

    private final FakeSearchClient searchClient = new FakeSearchClient();
    private final TurnProposalEnricher enricher = new TurnProposalEnricher(searchClient);

    @Test
    @DisplayName("AC-9/D-16 ③: official_notes·beans가 비면 검색이 돌고 빈 필드가 search 출처로 채워진다")
    void emptyOfficialNotesAndBeansTriggerSearch() {
        searchClient.canned = new SearchResult(
                null, List.of(new SearchResult.Bean("에티오피아 게데오", "내추럴")), "미디엄",
                List.of("블루베리"), List.of("https://frob.example/chelbesa"));

        TurnDraft enriched = enricher.enrich(draft(user("첼베사"), user("FroB"), List.of(), null, null));

        assertThat(searchClient.calls).isEqualTo(1);
        assertThat(searchClient.lastQuery).isEqualTo(new SearchQuery("첼베사", "FroB"));
        Note note = enriched.note();
        assertThat(note.beans()).hasSize(1);
        assertThat(note.beans().get(0).description()).isEqualTo(new Sourced<>("에티오피아 게데오", Source.SEARCH));
        assertThat(note.beans().get(0).process()).isEqualTo(new Sourced<>("내추럴", Source.SEARCH));
        assertThat(note.roastLevel()).isEqualTo(new Sourced<>("미디엄", Source.SEARCH));
        assertThat(note.officialNotes()).isEqualTo(new Sourced<>(List.of("블루베리"), Source.SEARCH));
        assertThat(note.sources()).containsExactly("https://frob.example/chelbesa");
        // 매칭 배지는 보강의 대상이 아니다 — 그대로 실려 나간다.
        assertThat(enriched.match()).isEqualTo(MatchInfo.newNote());
    }

    @Test
    @DisplayName("D-16 ③: 두 축이 이미 차 있으면 검색 콜 자체가 없다 — «채울 것이 있을 때만 돈다»")
    void filledOfficialNotesAndBeansSkipSearchEntirely() {
        TurnDraft draft = draft(user("첼베사"), user("FroB"),
                List.of(new Bean(user("에티오피아"), null)), null, notes("자몽"));

        TurnDraft result = enricher.enrich(draft);

        assertThat(searchClient.calls).isZero();
        assertThat(result).isSameAs(draft);
    }

    @Test
    @DisplayName("D-16 ③: roast_level만 비어 있는 것은 트리거가 아니다 — 콜을 줄이는 대가로 빈 채 남는다")
    void emptyRoastLevelAloneIsNotATrigger() {
        TurnDraft draft = draft(user("첼베사"), user("FroB"),
                List.of(new Bean(user("에티오피아"), null)), null, notes("자몽"));

        enricher.enrich(draft);

        assertThat(searchClient.calls).isZero();
    }

    @Test
    @DisplayName("D-16 ③: 한 축(beans)만 비어도 발동한다 — 트리거는 두 축의 OR다")
    void oneEmptyAxisIsEnoughToTrigger() {
        searchClient.canned = new SearchResult(
                null, List.of(new SearchResult.Bean("케냐 니에리", null)), null, List.of(), List.of());

        TurnDraft enriched = enricher.enrich(
                draft(user("첼베사"), user("FroB"), List.of(), null, notes("자몽")));

        assertThat(searchClient.calls).isEqualTo(1);
        assertThat(enriched.note().beans()).hasSize(1);
        // 이미 있던 공식 노트는 그대로다(출처도 보존).
        assertThat(enriched.note().officialNotes()).isEqualTo(notes("자몽"));
    }

    @Test
    @DisplayName("V-6: 값이 있는 필드는 검색이 덮지 않는다 — 출처가 아니라 값 유무로 가른다")
    void searchNeverOverwritesExistingValues() {
        searchClient.canned = new SearchResult(
                "다른로스터리", List.of(new SearchResult.Bean("콜롬비아", "워시드")), "다크",
                List.of("검색 노트"), List.of());

        TurnDraft enriched = enricher.enrich(draft(
                user("첼베사"),
                user("FroB"),                                      // source=user
                List.of(new Bean(new Sourced<>("에티오피아", Source.PHOTO), null)),  // source=photo
                new Sourced<>("라이트", Source.PHOTO),
                null));                                            // 비어 있는 축만 채워진다

        Note note = enriched.note();
        assertThat(note.roastery()).isEqualTo(user("FroB"));
        assertThat(note.beans().get(0).description()).isEqualTo(new Sourced<>("에티오피아", Source.PHOTO));
        assertThat(note.roastLevel()).isEqualTo(new Sourced<>("라이트", Source.PHOTO));
        assertThat(note.officialNotes()).isEqualTo(new Sourced<>(List.of("검색 노트"), Source.SEARCH));
    }

    @Test
    @DisplayName("AC-12: 검색 무결과는 draft 그대로 통과 — 검색 때문에 기록이 막히지 않는다")
    void emptySearchResultPassesDraftThrough() {
        searchClient.canned = SearchResult.empty();

        TurnDraft draft = draft(user("첼베사"), user("FroB"), List.of(), null, null);
        TurnDraft result = enricher.enrich(draft);

        assertThat(searchClient.calls).isEqualTo(1);
        assertThat(result).isSameAs(draft);
    }

    @Test
    @DisplayName("AC-12/plan §7: 검색이 예외로 터져도 제안은 살아남는다 — 이미 만들어진 draft를 잃지 않는다")
    void searchFailureKeepsProposal() {
        searchClient.failure = new IllegalStateException("검색 어댑터 계약 위반");

        TurnDraft draft = draft(user("첼베사"), user("FroB"), List.of(), null, null);
        TurnDraft result = enricher.enrich(draft);

        assertThat(result).isSameAs(draft);
    }

    @Test
    @DisplayName("V-5: 커피명이 없으면 검색 앵커가 없다 — 콜 없이 통과한다")
    void missingCoffeeNameSkipsSearch() {
        TurnDraft draft = draft(null, user("FroB"), List.of(), null, null);

        TurnDraft result = enricher.enrich(draft);

        assertThat(searchClient.calls).isZero();
        assertThat(result).isSameAs(draft);
    }

    @Test
    @DisplayName("0029 TΔ24b: 제안 없는 턴은 null이 그대로 통과한다 — 러너가 분기를 지지 않는다")
    void nullProposalPassesThrough() {
        assertThat(enricher.enrich(null)).isNull();
        assertThat(searchClient.calls).isZero();
    }

    @Test
    @DisplayName("FR-12: 검색 참조 링크는 기존 sources 뒤에 순서를 지켜 병합되고 중복은 제거된다")
    void sourcesAreMergedWithoutDuplicates() {
        searchClient.canned = new SearchResult(null, List.of(), null, List.of("자몽"),
                List.of("https://a.example", "https://b.example"));

        Note base = new Note(null, user("첼베사"), user("FroB"), List.of(), null, null,
                List.of("https://a.example"), List.of(), null, null);
        TurnDraft enriched = enricher.enrich(new TurnDraft(base, MatchInfo.newNote()));

        assertThat(enriched.note().sources())
                .containsExactly("https://a.example", "https://b.example");
    }

    // ---- 헬퍼 ----

    private static TurnDraft draft(Sourced<String> coffeeName, Sourced<String> roastery,
                                   List<Bean> beans, Sourced<String> roastLevel,
                                   Sourced<List<String>> officialNotes) {
        return new TurnDraft(
                new Note(null, coffeeName, roastery, beans, roastLevel, officialNotes,
                        List.of(), List.of(), null, null),
                MatchInfo.newNote());
    }

    private static Sourced<String> user(String value) {
        return new Sourced<>(value, Source.USER);
    }

    private static Sourced<List<String>> notes(String... values) {
        return new Sourced<>(List.of(values), Source.USER);
    }

    /** 지정된 결과·실패를 돌려주는 fake 검색 경계 — 웹 미접촉(모듈 CLAUDE.md §5.2). */
    private static final class FakeSearchClient implements SearchClient {
        SearchResult canned = SearchResult.empty();
        RuntimeException failure;
        int calls;
        SearchQuery lastQuery;

        @Override
        public SearchResult search(SearchQuery query) {
            calls++;
            lastQuery = query;
            if (failure != null) {
                throw failure;
            }
            return canned;
        }
    }
}
