package com.devwuu.mocha.llm;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.devwuu.mocha.json.MochaObjectMapper;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFormatTextJsonSchemaConfig;
import com.openai.models.responses.ToolChoiceOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OpenAiSearchClient의 계약 검증 — web_search 강제·strict schema 배선, 응답 텍스트(JSON) →
 * {@link SearchResult} 매핑, 호출/형식 실패의 빈 결과 수렴(AC-12)을 SDK 호출 없이 결정론적으로 확인한다
 * (ref: CLAUDE.md §5.2 외부 연동 어댑터, 실 API 스모크는 수동; plan.md#ADR-13,
 * changes/0029 delta.md#D-16 · tasks TΔ24b).
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

    private static OpenAiSearchClient client() {
        return new OpenAiSearchClient(null, "gpt-5.4", 3, MochaObjectMapper.create());
    }

    private static SearchQuery query() {
        return new SearchQuery("첼베사", "FroB Coffee");
    }

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
    @DisplayName("ADR-13/D-16: buildParams가 web_search를 강제(tool_choice)하고 strict JSON schema를 붙인다")
    void buildsParamsWithForcedWebSearchAndStrictSchema() {
        ResponseCreateParams params = client().buildParams(query());

        assertThat(params.model().orElseThrow().toString()).contains("gpt-5.4");
        // 장착 tool은 web_search 하나 — required가 곧 "그것을 부르라"이고, 이 강제가 이 클래스의 존재 이유다.
        assertThat(params.tools().orElseThrow()).hasSize(1);
        assertThat(params.tools().orElseThrow().get(0).isWebSearch()).isTrue();
        assertThat(params.toolChoice().orElseThrow().asOptions()).isEqualTo(ToolChoiceOptions.REQUIRED);

        ResponseFormatTextJsonSchemaConfig format =
                params.text().orElseThrow().format().orElseThrow().asJsonSchema();
        assertThat(format.strict()).contains(true);
        assertThat(format.schema()._additionalProperties())
                .containsKeys("type", "properties", "required", "additionalProperties");
    }

    // covers ADR-53(changes/0021): 검색 계약의 원두 축이 beans 배열로 개정됐다 — 구 origin/process 폐기.
    // covers D-16 ④: 2단계(공식 페이지 이미지 OCR)를 되살리지 않았으므로 official_page_url도 없다.
    @Test
    @DisplayName("ADR-53/D-16 ④: schema는 beans 배열을 요구하고 origin·official_page_url은 없다")
    void schemaUsesBeansArrayWithoutLegacyFields() {
        ResponseFormatTextJsonSchemaConfig format =
                client().buildParams(query()).text().orElseThrow().format().orElseThrow().asJsonSchema();

        String properties = format.schema()._additionalProperties().get("properties").toString();
        assertThat(properties).contains("beans").contains("description").contains("process");
        assertThat(properties).doesNotContain("origin");             // 노트 레벨 origin 폐기(ADR-53)
        assertThat(properties).doesNotContain("official_page_url");  // 2단계 미부활(D-16 ④)
        assertThat(format.schema()._additionalProperties().get("required").toString())
                .contains("beans").contains("official_notes").contains("sources");
    }

    @Test
    @DisplayName("ADR-14/ADR-16: 검색 앵커는 필드 라벨 없이 로스터리명+커피명+'원두'로 나간다")
    void buildInputUsesNaturalAnchor() {
        assertThat(client().buildInput(query())).isEqualTo("FroB Coffee 첼베사 원두");
        // 로스터리를 모르면 커피명만 앵커가 된다.
        assertThat(client().buildInput(new SearchQuery("첼베사", null))).isEqualTo("첼베사 원두");
    }

    @Test
    @DisplayName("FR-3/AC-16: INSTRUCTIONS가 official_notes 로스터리 출처 한정·추측 금지·동일성 가드를 강제한다")
    void instructionsEncodeEnrichmentPolicy() {
        String instructions = client().buildParams(query()).instructions().orElseThrow();

        assertThat(instructions).contains("공식 출처를 못 찾으면 official_notes는 빈 배열로 둔다");
        assertThat(instructions).contains("추측하지 말고");
        assertThat(instructions).contains("동일성 가드");
        // plan §5: 출처 상한이 지침에 실린다(비용/지연 통제).
        assertThat(instructions).contains("최대 3개");
    }

    @Test
    @DisplayName("AC-64/ADR-53: 응답 JSON이 beans 원두별 요소로 SearchResult에 매핑된다")
    void mapsJsonResponse() {
        String json = """
                {"roastery": "FroB Coffee",
                 "beans": [{"description": "에티오피아 게데오", "process": "내추럴"},
                           {"description": "콜롬비아", "process": null}],
                 "roast_level": "미디엄", "official_notes": ["블루베리","다크초콜릿"],
                 "sources": ["https://frob.example/chelbesa"]}
                """;

        SearchResult result = new StubSearchClient(json).search(query());

        assertThat(result.roastery()).isEqualTo("FroB Coffee");
        assertThat(result.beans()).containsExactly(
                new SearchResult.Bean("에티오피아 게데오", "내추럴"),
                new SearchResult.Bean("콜롬비아", null));
        assertThat(result.roastLevel()).isEqualTo("미디엄");
        assertThat(result.officialNotes()).containsExactly("블루베리", "다크초콜릿");
        assertThat(result.sources()).containsExactly("https://frob.example/chelbesa");
    }

    @Test
    @DisplayName("코드펜스·설명이 섞인 응답에서도 JSON 객체 구간만 추출해 매핑한다")
    void extractsJsonFromNoisyResponse() {
        String noisy = """
                검색 결과입니다:
                ```json
                {"roastery": null, "beans": [], "roast_level": "라이트",
                 "official_notes": [], "sources": []}
                ```
                """;

        SearchResult result = new StubSearchClient(noisy).search(query());

        assertThat(result.roastLevel()).isEqualTo("라이트");
        assertThat(result.beans()).isEmpty();
    }

    @Test
    @DisplayName("AC-12: 진짜 무결과(정상 응답·채운 값 없음)는 실패와 구분해 관측하고 empty()로 수렴한다")
    void trulyEmptyResultIsLoggedApart() {
        String json = """
                {"roastery": null, "beans": [], "roast_level": null,
                 "official_notes": [], "sources": []}
                """;

        SearchResult result = new StubSearchClient(json).search(query());

        assertThat(result).isEqualTo(SearchResult.empty());
        assertThat(logs.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.INFO);
            assertThat(e.getFormattedMessage()).contains("무결과");
        });
    }

    @Test
    @DisplayName("AC-12: JSON이 없는 응답(형식 실패)은 빈 결과로 수렴한다 — 무결과와 다른 로그")
    void noJsonYieldsEmpty() {
        SearchResult result = new StubSearchClient("검색에 실패했습니다.").search(query());

        assertThat(result).isEqualTo(SearchResult.empty());
        assertThat(logs.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getFormattedMessage()).contains("형식 실패");
        });
    }

    @Test
    @DisplayName("AC-12: 검색 호출이 예외를 던져도 빈 결과로 수렴한다(예외 미전파)")
    void callFailureYieldsEmpty() {
        SearchResult result = new StubSearchClient(new RuntimeException("timeout")).search(query());

        assertThat(result).isEqualTo(SearchResult.empty());
        assertThat(logs.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getFormattedMessage()).contains("호출 실패");
        });
    }

    // covers V-14 준용: description 없는 요소는 후보 단계에서 이미 드롭 — 도메인 진입 전 위생.
    @Test
    @DisplayName("ADR-53: description이 빈 beans 요소는 드롭되고 빈 process는 null로 정규화된다")
    void dropsBlankBeanDescriptions() {
        String json = """
                {"roastery": null,
                 "beans": [{"description": "  ", "process": "워시드"},
                           {"description": "콜롬비아 핑크 버번", "process": " "}],
                 "roast_level": null, "official_notes": [], "sources": []}
                """;

        SearchResult result = new StubSearchClient(json).search(query());

        assertThat(result.beans()).containsExactly(new SearchResult.Bean("콜롬비아 핑크 버번", null));
    }

    @Test
    @DisplayName("AC-12: 파싱 불가한 깨진 JSON도 빈 결과로 수렴한다")
    void brokenJsonYieldsEmpty() {
        SearchResult result = new StubSearchClient("{\"beans\": ").search(query());

        assertThat(result).isEqualTo(SearchResult.empty());
    }

    @Test
    @DisplayName("검색 앵커(coffee_name)는 필수다 — 없이는 질의를 만들 수 없다(V-5)")
    void queryRequiresCoffeeName() {
        assertThatThrownBy(() -> new SearchQuery(null, "FroB"))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    @DisplayName("ADR-13/P1: 응답에 잉여 필드가 섞여도 전체 결과가 깨지지 않는다(관대한 DTO)")
    void ignoresUnknownFields() {
        String json = """
                {"roastery": "FroB", "beans": [], "roast_level": null,
                 "official_notes": [], "sources": [], "official_page_url": "https://legacy.example",
                 "confidence": 0.9}
                """;

        SearchResult result = new StubSearchClient(json).search(query());

        assertThat(result.roastery()).isEqualTo("FroB");
        assertThat(result.sources()).isEmpty();
    }

    @Test
    @DisplayName("PhotoInfoExtractor와 동일 위생: null 배열은 빈 리스트로 수렴한다")
    void nullArraysNormalizeToEmptyLists() {
        SearchResult result = new SearchResult("FroB", null, null, null, null);

        assertThat(result.beans()).isEmpty();
        assertThat(result.officialNotes()).isEmpty();
        assertThat(result.sources()).isEmpty();
        assertThat(List.of(result.isEmpty())).containsExactly(false); // roastery가 차 있으므로 무결과 아님
    }
}
