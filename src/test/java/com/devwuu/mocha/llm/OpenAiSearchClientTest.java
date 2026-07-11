package com.devwuu.mocha.llm;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.devwuu.mocha.json.MochaObjectMapper;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFormatTextJsonSchemaConfig;
import com.openai.models.responses.Tool;
import com.openai.models.responses.ToolChoiceTypes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

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

    /** OpenAiSearchClient가 남긴 로그 이벤트를 캡처해 실패/무결과 경로가 구분되는지 단언하기 위한 appender. */
    private ListAppender<ILoggingEvent> logs;
    private Logger clientLogger;

    @BeforeEach
    void attachLogAppender() {
        clientLogger = (Logger) LoggerFactory.getLogger(OpenAiSearchClient.class);
        logs = new ListAppender<>();
        logs.start();
        clientLogger.addAppender(logs);
    }

    @AfterEach
    void detachLogAppender() {
        clientLogger.detachAppender(logs);
    }

    @Test
    @DisplayName("AC-Δ1/AC-Δ2: buildParams가 web_search 도구·tool_choice 강제·strict JSON schema를 붙인다")
    void buildsParamsWithForcedWebSearchAndStrictSchema() {
        OpenAiSearchClient client =
                new OpenAiSearchClient(null, "gpt-4o", 3, MochaObjectMapper.create());

        ResponseCreateParams params = client.buildParams(query());

        // web_search_preview 도구가 붙는다.
        List<Tool> tools = params.tools().orElseThrow();
        assertThat(tools).anySatisfy(tool -> assertThat(tool.isWebSearchPreview()).isTrue());

        // AC-Δ2(P3): tool_choice로 web_search를 강제한다.
        assertThat(params.toolChoice()).get().satisfies(choice ->
                assertThat(choice.asTypes().type()).isEqualTo(ToolChoiceTypes.Type.WEB_SEARCH_PREVIEW));

        // AC-Δ1(P1): text.format에 strict JSON schema가 붙고, 스키마가 SearchPayload 6필드를 요구한다.
        ResponseFormatTextJsonSchemaConfig format =
                params.text().orElseThrow().format().orElseThrow().asJsonSchema();
        assertThat(format.strict()).contains(true);
        assertThat(format.schema()._additionalProperties())
                .containsKeys("type", "properties", "required", "additionalProperties");
    }

    @Test
    @DisplayName("AC-Δ1/AC-Δ2/AC-Δ3: INSTRUCTIONS가 블렌드 공란 강제를 폐지하고 official_notes 로스터리 한정은 유지한다")
    void instructionsDropBlendBlankRuleKeepOfficialNotesRoasteryOnly() {
        OpenAiSearchClient client =
                new OpenAiSearchClient(null, "gpt-4o", 3, MochaObjectMapper.create());

        String instructions = client.buildParams(query()).instructions().orElseThrow();

        // AC-Δ1/AC-Δ2: 블렌드 origin/process 공란 강제 문구가 더 이상 없다.
        assertThat(instructions).doesNotContain("빈 값(null)으로 둔다");
        // AC-Δ2: 단일/블렌드 공통 보강 지침이 있다.
        assertThat(instructions).contains("단일 원산지든 블렌드든");
        // AC-Δ1(D2): 블렌드 여러 원산지를 origin에 쉼표로 나열하라는 지침이 있다.
        assertThat(instructions).contains("쉼표로 나열");
        // AC-Δ3: official_notes 로스터리 공식 출처 한정은 불변으로 유지된다.
        assertThat(instructions).contains("공식 웹/판매 페이지에서 확인된 경우에만 채운다");
        // 추측 금지 유지.
        assertThat(instructions).contains("추측하지 말고");
    }

    @Test
    @DisplayName("AC-Δ4: buildInput 검색 앵커에 필드 라벨이 없고 로스터리명+커피명이 자연 검색어로 담긴다")
    void buildInputHasNoFieldLabels() {
        OpenAiSearchClient client =
                new OpenAiSearchClient(null, "gpt-4o", 3, MochaObjectMapper.create());

        String input = client.buildInput(new SearchQuery("와이키키", "모모스 커피"));

        // 라벨 오염 제거: 필드 라벨 문자열이 섞이지 않는다.
        assertThat(input).doesNotContain("커피 이름:").doesNotContain("로스터리:");
        // 로스터리명·커피명 토큰이 자연 검색어로 함께 담긴다.
        assertThat(input).contains("모모스 커피").contains("와이키키");
    }

    @Test
    @DisplayName("AC-Δ4: 로스터리가 null/blank면 검색 앵커는 커피명만 담는다")
    void buildInputCoffeeNameOnlyWhenRoasteryBlank() {
        OpenAiSearchClient client =
                new OpenAiSearchClient(null, "gpt-4o", 3, MochaObjectMapper.create());

        assertThat(client.buildInput(new SearchQuery("예가체프 G1", null)))
                .isEqualTo("예가체프 G1");
        assertThat(client.buildInput(new SearchQuery("예가체프 G1", "  ")))
                .isEqualTo("예가체프 G1")
                .doesNotContain("로스터리:");
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

    @Test
    @DisplayName("AC-Δ3: ① 호출 예외 → 호출 실패 로그(WARN), 무결과 로그 아님. empty() 수렴")
    void callFailureLogsAsFailureNotEmptyResult() {
        SearchResult result = new StubSearchClient(new RuntimeException("timeout")).search(query());

        assertThat(result).isEqualTo(SearchResult.empty());
        assertThat(logs.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getFormattedMessage()).contains("호출 실패");
        });
        assertThat(logs.list).noneSatisfy(e ->
                assertThat(e.getFormattedMessage()).contains("무결과"));
    }

    @Test
    @DisplayName("AC-Δ3: ② 스키마 위반(비JSON/파싱 불가) 응답 → 형식 실패 로그(WARN), 무결과 로그 아님. empty() 수렴")
    void formatFailureLogsAsFailureNotEmptyResult() {
        // 비JSON 응답(structured output이면 스키마 JSON이 와야 함)
        SearchResult nonJson = new StubSearchClient("검색 결과를 찾지 못했습니다.").search(query());
        // 파싱 불가한 깨진 JSON
        SearchResult broken = new StubSearchClient("{\"origin\": ").search(query());

        assertThat(nonJson).isEqualTo(SearchResult.empty());
        assertThat(broken).isEqualTo(SearchResult.empty());
        assertThat(logs.list).filteredOn(e -> e.getFormattedMessage().contains("형식 실패"))
                .hasSize(2)
                .allSatisfy(e -> assertThat(e.getLevel()).isEqualTo(Level.WARN));
        assertThat(logs.list).noneSatisfy(e ->
                assertThat(e.getFormattedMessage()).contains("무결과"));
    }

    @Test
    @DisplayName("AC-Δ3: ③ 채운 값 없는 정상 응답 → 무결과 로그(INFO), 실패 로그 아님. empty() 수렴")
    void emptyValuesLogAsNoResultNotFailure() {
        String allEmpty = """
                {"roastery": null, "origin": null, "process": null, "roast_level": null,
                 "official_notes": [], "sources": []}
                """;

        SearchResult result = new StubSearchClient(allEmpty).search(query());

        assertThat(result).isEqualTo(SearchResult.empty());
        assertThat(logs.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.INFO);
            assertThat(e.getFormattedMessage()).contains("무결과");
        });
        assertThat(logs.list).noneSatisfy(e ->
                assertThat(e.getFormattedMessage()).contains("실패"));
    }
}
