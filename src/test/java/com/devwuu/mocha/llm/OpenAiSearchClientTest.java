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
import com.openai.models.responses.WebSearchPreviewTool;
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

        // AC-Δ1(P1): text.format에 strict JSON schema가 붙고, 스키마가 SearchPayload 필드를 요구한다.
        ResponseFormatTextJsonSchemaConfig format =
                params.text().orElseThrow().format().orElseThrow().asJsonSchema();
        assertThat(format.strict()).contains(true);
        assertThat(format.schema()._additionalProperties())
                .containsKeys("type", "properties", "required", "additionalProperties");
        // AC-Δ5(ADR-15): strict schema에 official_page_url 필드가 properties·required에 추가된다(2단계 입력).
        assertThat(format.schema()._additionalProperties().get("properties").toString())
                .contains("official_page_url");
        assertThat(format.schema()._additionalProperties().get("required").toString())
                .contains("official_page_url");
    }

    @Test
    @DisplayName("AC-Δ5: buildParams가 web_search에 userLocation(KR)·searchContextSize(high)를 배선한다")
    void buildsParamsWithKrLocalizationAndHighContextSize() {
        OpenAiSearchClient client =
                new OpenAiSearchClient(null, "gpt-4o", 3, MochaObjectMapper.create());

        ResponseCreateParams params = client.buildParams(query());

        WebSearchPreviewTool webSearch = params.tools().orElseThrow().stream()
                .filter(Tool::isWebSearchPreview)
                .map(Tool::asWebSearchPreview)
                .findFirst()
                .orElseThrow();

        // ADR-16: KR 지역화 — 영어권 기본 착지(→영어 기록) 방지.
        assertThat(webSearch.userLocation()).get().satisfies(loc ->
                assertThat(loc.country()).contains("KR"));
        // ADR-16: search_context_size=high로 텍스트 컨텍스트를 넓힌다.
        assertThat(webSearch.searchContextSize())
                .contains(WebSearchPreviewTool.SearchContextSize.HIGH);
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
    @DisplayName("AC-Δ4/AC-Δ5(ADR-15·16): INSTRUCTIONS에 공식 페이지 URL 반환·한국어 기록·동일성 가드 규칙이 있다")
    void instructionsCarryOfficialUrlKoreanAndIdentityGuard() {
        OpenAiSearchClient client =
                new OpenAiSearchClient(null, "gpt-4o", 3, MochaObjectMapper.create());

        String instructions = client.buildParams(query()).instructions().orElseThrow();

        // AC-Δ5(ADR-15): 공식 도메인 탐색 유도 + 공식 페이지 URL 반환 규칙(2단계 입력).
        assertThat(instructions).contains("official_page_url");
        assertThat(instructions).contains("공식 도메인");
        // 환각 URL 가드: 검색 결과에 실제로 나타난 URL만.
        assertThat(instructions).contains("검색 결과에 실제로 나타난 URL");
        // AC-Δ4(ADR-16): 한국어 기록 규칙(영문 출처는 번역).
        assertThat(instructions).contains("한국어로 기록한다");
        // AC-Δ5(ADR-16): 동일성 가드 — 로스터리명+원두명 동시 확인, 미확인 시 공란.
        assertThat(instructions).contains("동일성 가드");
        assertThat(instructions).contains("로스터리명과 원두명이 함께 확인되는 출처만");
    }

    // covers AC-Δ8(ADR-38, FR-2/AC-57): 검색 보강이 만드는 roastery(고유명사)는 원문 유지·음차 금지,
    //        나머지 텍스트 필드는 한국어 통일 — 4계약 프롬프트 동일 인코딩을 검색 지침에서 확인한다.
    // covers AC-Δ9(ADR-16 확장, FR-3/AC-58): sources 동일성 가드 — 로스터리+원두명 동시 확인 출처만 수록,
    //        참고 출처와 수록 출처를 구분한다. 스키마 불변(지침 문구만 확인 — 파라미터 조립 검사).
    @Test
    @DisplayName("AC-Δ9: INSTRUCTIONS가 sources에 로스터리+원두명 동시 확인 출처만 수록하도록 가드한다")
    void instructionsGuardSourcesByIdentity() {
        OpenAiSearchClient client =
                new OpenAiSearchClient(null, "gpt-4o", 3, MochaObjectMapper.create());

        String instructions = client.buildParams(query()).instructions().orElseThrow();

        // sources에 값 채움과 동일한 동일성 가드 적용 — 참고 출처 ≠ 수록 출처.
        assertThat(instructions).contains("함께 확인된 출처 URL만 담는다");
        assertThat(instructions).contains("참고한 출처와 수록할 출처는 다르다");
    }

    @Test
    @DisplayName("AC-Δ8: INSTRUCTIONS가 roastery 원문 유지(음차·번역 금지)를 강제한다")
    void instructionsPreserveRoasteryVerbatim() {
        OpenAiSearchClient client =
                new OpenAiSearchClient(null, "gpt-4o", 3, MochaObjectMapper.create());

        String instructions = client.buildParams(query()).instructions().orElseThrow();

        assertThat(instructions).contains("roastery는 공식 출처의 원문 표기를 그대로 유지");
        assertThat(instructions).contains("음차·번역하지 않는다");
        // origin 한국어 지명 통일 + process·roast_level 표준 어휘 예시.
        assertThat(instructions).contains("한국어 지명으로 통일");
        assertThat(instructions).contains("워시드/내추럴/허니/무산소");
    }

    @Test
    @DisplayName("AC-Δ4/AC-Δ5: buildInput 검색 앵커에 필드 라벨이 없고 로스터리명+커피명+'원두' 키워드가 자연 검색어로 담긴다")
    void buildInputHasNoFieldLabels() {
        OpenAiSearchClient client =
                new OpenAiSearchClient(null, "gpt-4o", 3, MochaObjectMapper.create());

        String input = client.buildInput(new SearchQuery("와이키키", "모모스 커피"));

        // 라벨 오염 제거: 필드 라벨 문자열이 섞이지 않는다.
        assertThat(input).doesNotContain("커피 이름:").doesNotContain("로스터리:");
        // 로스터리명·커피명 토큰이 자연 검색어로 함께 담긴다.
        assertThat(input).contains("모모스 커피").contains("와이키키");
        // AC-Δ5(ADR-16): 도메인 키워드 '원두'가 커피 문맥 고정용으로 담긴다.
        assertThat(input).contains("원두");
    }

    @Test
    @DisplayName("AC-Δ4/AC-Δ5: 로스터리가 null/blank면 검색 앵커는 커피명+'원두'만 담는다")
    void buildInputCoffeeNameOnlyWhenRoasteryBlank() {
        OpenAiSearchClient client =
                new OpenAiSearchClient(null, "gpt-4o", 3, MochaObjectMapper.create());

        assertThat(client.buildInput(new SearchQuery("예가체프 G1", null)))
                .isEqualTo("예가체프 G1 원두");
        assertThat(client.buildInput(new SearchQuery("예가체프 G1", "  ")))
                .isEqualTo("예가체프 G1 원두")
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
    @DisplayName("AC-Δ1/AC-Δ5(D2): 블렌드 여러 원산지가 쉼표 문자열로 온 origin이 String 그대로 매핑된다")
    void mapsCommaSeparatedBlendOriginAsPlainString() {
        // 블렌드 보강 응답 — origin에 여러 구성 원산지가 쉼표 나열로 온다(List 구조화 아님, 타입 String 불변).
        String json = """
                {"roastery": null, "origin": "에티오피아, 콜롬비아, 브라질", "process": null,
                 "roast_level": "미디엄", "official_notes": ["패션프루트","베르가못"],
                 "sources": ["https://momos.co.kr/waikiki"]}
                """;

        SearchResult result = new StubSearchClient(json).search(query());

        // 쉼표 문자열이 파싱·분해 없이 origin() String에 그대로 실린다.
        assertThat(result.origin()).isEqualTo("에티오피아, 콜롬비아, 브라질");
        // origin 데이터 타입은 String 불변 — 배열화하지 않는다(회귀 가드).
        assertThat((Object) result.origin()).isInstanceOf(String.class);
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
