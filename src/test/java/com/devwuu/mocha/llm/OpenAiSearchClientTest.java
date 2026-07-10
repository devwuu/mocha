package com.devwuu.mocha.llm;

import com.devwuu.mocha.json.MochaObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OpenAiSearchClient의 계약 검증 — 응답 텍스트(JSON) → SearchResult 매핑과 실패/무결과 수렴(AC-12)을
 * SDK 호출 없이 결정론적으로 확인한다 (ref: CLAUDE.md §5.2 외부 연동 어댑터, 실 API 스모크는 수동).
 */
class OpenAiSearchClientTest {

    /** {@code rawSearch} 시임을 미리 준비한 응답 텍스트로 대체하는 스텁. SDK 클라이언트는 쓰지 않는다. */
    static class StubSearchClient extends OpenAiSearchClient {
        private final String response;
        private final RuntimeException toThrow;

        StubSearchClient(String response) {
            super(null, "test-model", 3, MochaObjectMapper.create());
            this.response = response;
            this.toThrow = null;
        }

        StubSearchClient(RuntimeException toThrow) {
            super(null, "test-model", 3, MochaObjectMapper.create());
            this.response = null;
            this.toThrow = toThrow;
        }

        @Override
        protected String rawSearch(SearchQuery query) {
            if (toThrow != null) {
                throw toThrow;
            }
            return response;
        }
    }

    private static SearchQuery query() {
        return new SearchQuery("커피베라 예가체프 G1", "커피베라");
    }

    @Test
    @DisplayName("응답 JSON이 snake_case 필드로 SearchResult에 매핑된다")
    void mapsJsonResponse() {
        String json = """
                {"roastery": null, "origin": "에티오피아", "process": "워시드", "roast_level": "라이트",
                 "official_notes": ["자몽","홍차"], "sources": ["https://roastery.example/y"]}
                """;

        SearchResult result = new StubSearchClient(json).search(query());

        assertThat(result.origin()).isEqualTo("에티오피아");
        assertThat(result.process()).isEqualTo("워시드");
        assertThat(result.roastLevel()).isEqualTo("라이트");
        assertThat(result.officialNotes()).containsExactly("자몽", "홍차");
        assertThat(result.sources()).containsExactly("https://roastery.example/y");
    }

    @Test
    @DisplayName("코드펜스·설명이 섞인 응답에서도 JSON 객체 구간만 추출해 매핑한다")
    void extractsJsonFromNoisyResponse() {
        String noisy = """
                검색 결과입니다:
                ```json
                {"roastery": null, "origin": "콜롬비아", "process": null, "roast_level": null,
                 "official_notes": [], "sources": []}
                ```
                도움이 되었길 바랍니다.
                """;

        SearchResult result = new StubSearchClient(noisy).search(query());

        assertThat(result.origin()).isEqualTo("콜롬비아");
        assertThat(result.officialNotes()).isEmpty();
    }

    @Test
    @DisplayName("AC-12: JSON이 없는 응답(무결과)은 빈 SearchResult로 수렴한다")
    void noJsonYieldsEmpty() {
        SearchResult result = new StubSearchClient("검색 결과를 찾지 못했습니다.").search(query());

        assertThat(result).isEqualTo(SearchResult.empty());
    }

    @Test
    @DisplayName("AC-12: 파싱 불가한 깨진 JSON도 빈 SearchResult로 수렴한다")
    void brokenJsonYieldsEmpty() {
        SearchResult result = new StubSearchClient("{\"origin\": ").search(query());

        assertThat(result).isEqualTo(SearchResult.empty());
    }

    @Test
    @DisplayName("AC-12: 검색 호출이 예외를 던져도 빈 SearchResult로 수렴한다(예외 미전파)")
    void callFailureYieldsEmpty() {
        SearchResult result = new StubSearchClient(new RuntimeException("timeout")).search(query());

        assertThat(result).isEqualTo(SearchResult.empty());
    }
}
