package com.devwuu.mocha.pipeline;

import com.devwuu.mocha.domain.NoteMeta;
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
 * T2-4: NoteEnricher — 검색 보강 병합 정책을 결정론적으로 검증한다(AC-3/AC-12/AC-2).
 * SearchClient는 fake로 대체해 외부 호출 없이 정책만 다룬다(CLAUDE.md §5.2).
 */
class NoteEnricherTest {

    /** 요청을 포착하고 준비된 결과를 돌려주는 fake SearchClient — SDK/실 API를 쓰지 않는다. */
    static class FakeSearchClient implements SearchClient {
        SearchQuery captured;
        SearchResult response = SearchResult.empty();

        @Override
        public SearchResult search(SearchQuery query) {
            this.captured = query;
            return response;
        }
    }

    private static NoteMeta draft(Sourced<String> roastery, Sourced<String> origin, Sourced<String> process) {
        return new NoteMeta("커피베라 예가체프 G1", roastery, origin, process, null, null, List.of());
    }

    @Test
    @DisplayName("AC-2: 검색으로 채운 빈 필드는 source=search로 마킹된다")
    void marksSearchFilledFieldsAsSearch() {
        FakeSearchClient search = new FakeSearchClient();
        search.response = new SearchResult(
                null, "에티오피아", "워시드", "라이트", List.of("자몽", "홍차"), List.of("https://roastery.example/y"));

        NoteMeta result = new NoteEnricher(search).enrich(
                draft(Sourced.user("커피베라"), null, null));

        assertThat(result.origin()).isEqualTo(new Sourced<>("에티오피아", Source.SEARCH));
        assertThat(result.process()).isEqualTo(new Sourced<>("워시드", Source.SEARCH));
        assertThat(result.roastLevel()).isEqualTo(new Sourced<>("라이트", Source.SEARCH));
        assertThat(result.officialNotes().source()).isEqualTo(Source.SEARCH);
        assertThat(result.officialNotes().value()).containsExactly("자몽", "홍차");
        assertThat(result.sources()).containsExactly("https://roastery.example/y");
        // 검색 질의에는 커피명·로스터리가 실린다.
        assertThat(search.captured.coffeeName()).isEqualTo("커피베라 예가체프 G1");
        assertThat(search.captured.roastery()).isEqualTo("커피베라");
    }

    @Test
    @DisplayName("AC-3: 사용자가 명시한 값은 검색 결과와 충돌해도 덮어쓰이지 않는다 (V-6)")
    void userValueSurvivesConflictingSearch() {
        FakeSearchClient search = new FakeSearchClient();
        // 사용자는 process=내추럴을 명시했는데 검색은 워시드라고 함 → 사용자 값이 이겨야 한다.
        search.response = new SearchResult(null, "에티오피아", "워시드", null, List.of(), List.of());

        NoteMeta result = new NoteEnricher(search).enrich(
                draft(Sourced.user("커피베라"), null, Sourced.user("내추럴")));

        assertThat(result.process()).isEqualTo(Sourced.user("내추럴")); // 덮어쓰기 드롭
        assertThat(result.roastery()).isEqualTo(Sourced.user("커피베라")); // user 유지
        assertThat(result.origin()).isEqualTo(new Sourced<>("에티오피아", Source.SEARCH)); // 빈 필드만 보강
    }

    @Test
    @DisplayName("AC-12: 검색 무결과면 draft가 그대로 통과한다(빈 필드는 null 유지)")
    void emptySearchPassesDraftThrough() {
        FakeSearchClient search = new FakeSearchClient();
        search.response = SearchResult.empty();

        NoteMeta input = draft(Sourced.user("커피베라"), null, Sourced.user("내추럴"));
        NoteMeta result = new NoteEnricher(search).enrich(input);

        assertThat(result.roastery()).isEqualTo(Sourced.user("커피베라"));
        assertThat(result.process()).isEqualTo(Sourced.user("내추럴"));
        assertThat(result.origin()).isNull();
        assertThat(result.roastLevel()).isNull();
        assertThat(result.officialNotes()).isNull();
        assertThat(result.sources()).isEmpty();
    }

    @Test
    @DisplayName("사용자가 불러준 official_notes는 검색으로 덮어쓰지 않는다")
    void userOfficialNotesNotOverwritten() {
        FakeSearchClient search = new FakeSearchClient();
        search.response = new SearchResult(null, null, null, null, List.of("검색노트"), List.of());

        NoteMeta input = new NoteMeta(
                "커피베라 예가체프 G1", Sourced.user("커피베라"), null, null, null,
                Sourced.user(List.of("내가부른노트")), List.of());
        NoteMeta result = new NoteEnricher(search).enrich(input);

        assertThat(result.officialNotes()).isEqualTo(Sourced.user(List.of("내가부른노트")));
    }

    @Test
    @DisplayName("sources는 기존 뒤에 검색 링크를 순서 유지·중복 제거로 병합한다 (FR-12)")
    void mergesSourcesDedup() {
        FakeSearchClient search = new FakeSearchClient();
        search.response = new SearchResult(
                null, null, null, null, List.of(), List.of("https://a.example", "https://b.example"));

        NoteMeta input = new NoteMeta(
                "커피", Sourced.user("로스터리"), null, null, null, null,
                List.of("https://a.example"));
        NoteMeta result = new NoteEnricher(search).enrich(input);

        assertThat(result.sources()).containsExactly("https://a.example", "https://b.example");
    }
}
