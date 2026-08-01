package com.devwuu.mocha.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.openai.client.OpenAIClient;
import com.openai.core.JsonValue;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFormatTextJsonSchemaConfig;
import com.openai.models.responses.ResponseTextConfig;
import com.openai.models.responses.ToolChoiceOptions;
import com.openai.models.responses.WebSearchTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI web search(Responses API) 기반 {@link SearchClient} 구현 (ref: plan.md#ADR-5, spec FR-3;
 * changes/0029 delta.md#D-16, tasks TΔ24b).
 * <p>OpenAI SDK 타입은 이 클래스 안에만 존재한다(plan §4 POLICY, NFR-4). 검색 지침(instructions)에
 * fallback 규칙을 인코딩한다 — official_notes는 <b>로스터리 공식 출처 한정</b>이고, beans·roast_level은
 * 로스터리 공식 우선 + 신뢰할 일반 출처 fallback으로 찾으면 채운다. "추측 금지"(못 찾으면 공란)는
 * 유지한다(FR-3/AC-16, ADR-14 보강 fallback POLICY).
 * <p>보강은 Responses API 한 콜로 (a) 내장 {@code web_search} 도구를 {@code tool_choice}로 강제 실행하고,
 * (b) {@code text.format}에 strict JSON schema를 붙여 <b>스키마 보장 JSON</b>으로 받는다 — 응답에 산문·인용이
 * 섞여도 형식 때문에 검색 결과가 버려지지 않게(P1), 모델이 검색을 건너뛰지 못하게(P3) 한다(ADR-13).
 * <b>강제가 이 클래스의 존재 이유다</b> — 모델 재량으로 두었더니 조용히 안 부르게 된 것이 D-16이 되돌리는
 * 회귀다.
 * <p><b>구 판본의 2단계(공식 페이지 fetch → 동일성 가드 → 이미지 OCR, 구 ADR-15)는 되살리지 않았다</b> —
 * changes/0018이 폐기한 복잡도라 재현하지 않는 것이 D-16 ④의 명시적 결정이고, 그래서 응답 스키마에서
 * {@code official_page_url}도 함께 빠졌다(2단계의 유일한 입력이었다).
 * <p>호출/형식 실패(예외·비스키마 응답)와 진짜 무결과(정상 응답·채운 값 없음)는 서로 다른 로그로
 * 구분하되(P2, ADR-13, plan §6 관측), 둘 다 예외로 새지 않고 {@link SearchResult#empty()}로 수렴한다 —
 * 상위가 사용자 입력만으로 진행하게 하기 위함(AC-12, plan §7).
 */
public class OpenAiSearchClient implements SearchClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiSearchClient.class);

    // POLICY: official_notes는 로스터리 출처 한정(fallback 없음), beans·roast_level은 로스터리 공식 우선 +
    //         신뢰할 일반 출처 fallback 보강, 추측 금지
    //         (ref: spec FR-3/AC-16, plan#ADR-14 보강 fallback POLICY)
    // POLICY: 한국어 기록·동일성 가드(로스터리명+원두명 동시 확인)를 지침에 인코딩한다. 공식 도메인 탐색은
    //         유도하되(findings-TΔ0: 유도 없으면 공식 페이지 착지가 일관되게 실패), 동명의 타 대상은 동일성
    //         가드로 배제한다 (ref: delta 0006 ADR-16, spec FR-3, plan#ADR-15/16).
    // POLICY: roastery(고유명사)는 원문 표기 유지(음차·번역 금지) — 한국어 통일은 나머지 텍스트 필드만
    //         (coffee_name은 검색이 만들지 않는다, V-5). 언어 정책 인코딩 지점 3곳이 자구까지 동일해야 한다
    //         (ref: plan.md#ADR-38, spec FR-2/AC-57 — 동일성은 LanguagePolicyParityTest가 가드).
    // POLICY: beans는 원두별 요소 — 블렌드는 구성 원두마다 요소를 만들어 가공방식을 나눠 담고, 원산지를 한
    //         문자열에 쉼표로 나열하지 않는다. 구 판본의 "origin 쉼표 나열" 지시가 여기서 폐기됐다
    //         (ref: plan.md#ADR-53, spec FR-2/AC-64).
    // POLICY: sources에는 로스터리+원두명 동시 확인 출처만 담는다 — 값 채움과 동일 가드(참고 출처 ≠ 수록 출처).
    //         동명의 타 로스터리 상품 페이지가 sources에 섞이던 실데이터 회귀 방지
    //         (ref: plan.md#ADR-16 확장, spec FR-3/AC-58).
    private static final String INSTRUCTIONS = """
            너는 커피 원두 정보를 웹 검색으로 보강하는 도우미다. 주어진 커피에 대해 웹을 검색하고 아래 규칙을 반드시 지켜라.
            - 반드시 그 로스터리의 공식 웹사이트(공식 온라인 쇼핑몰)에서 이 원두의 상품 상세 페이지를 찾아라.
              블로그·리뷰 페이지가 먼저 나오면 거기서 멈추지 말고, '로스터리명 + 공식' '로스터리명 + 스토어' 등으로 추가 검색해 공식 도메인을 찾아라.
            - 로스터리명과 원두명이 함께 확인되는 출처만 사용한다(동일성 가드). 지명·동명의 다른 상품으로 새지 않도록, 같은 대상인지 확신할 수 없는 값은 채우지 말고 공란으로 둔다.
            - official_notes(로스터리 공식 테이스팅 노트)는 그 로스터리의 공식 웹/판매 페이지에서 확인된 경우에만 채운다.
              제3자 블로그·리뷰의 감상은 official_notes에 넣지 않는다. 공식 출처를 못 찾으면 official_notes는 빈 배열로 둔다.
            - beans·roast_level 같은 원두 일반 사실은 로스터리 공식 페이지를 우선으로, 없으면 신뢰할 만한 일반 출처(나무위키·판매몰 등)에서 찾으면 채운다.
            - beans에는 원두 구성을 원두별 요소로 담는다 — 요소의 description은 원산지·품종 등을 묶은 자유 텍스트("에티오피아 예가체프 헤어룸"), process는 그 원두의 가공방식이다. 단일 원두도 요소 1개짜리 배열로 담고, 블렌드는 구성 원두마다 요소를 만들어 가공방식이 원두별로 다르면 각 요소에 나눠 담는다(원산지를 한 문자열에 쉼표로 나열하지 않는다). 품종은 확인되면 그 원두의 description에 포함하고, 없으면 생략한다.
            - roastery는 원문 표기를 그대로 유지한다 — 음차·번역하지 않는다("Ethiopia Chelbesa"는 그대로). 한국어 음차·이표기는 내부에서 따로 만드니 여기서 만들지 마라.
            - beans의 description·process·roast_level·official_notes는 한국어로 기록한다(영문 표기는 한국어로 옮겨 적는다). description의 지명은 한국어 지명으로 통일(영문·한글 혼용 금지: "게데오, Gedeb" ❌ → "게데오"), process·roast_level은 한국어 관용 표기로 옮긴다(예: 가공방식 "워시드/내추럴/허니/무산소", 로스팅 "라이트/미디엄/다크" — 고정 목록 아님).
            - 확인되지 않은 값은 추측하지 말고 null(문자열 필드)·빈 배열(리스트)로 둔다.
            - sources에는 로스터리명과 원두명이 이 원두의 것으로 함께 확인된 출처 URL만 담는다(값 채움과 동일한 동일성 가드). 검색 중 참고했더라도 같은 대상인지 확신할 수 없는 페이지(동명의 다른 상품·지명 등)는 sources에 넣지 마라 — 참고한 출처와 수록할 출처는 다르다. 최대 %d개.
            - 출력은 아래 JSON 객체 하나만, 다른 설명 없이 반환한다:
              {"roastery": string|null, "beans": [{"description": string, "process": string|null}],
               "roast_level": string|null, "official_notes": string[], "sources": string[]}
            """;

    private final OpenAIClient client;
    private final String model;
    private final int maxResults;
    private final ObjectMapper mapper;

    /**
     * @param model      검색 보강 모델 — 전용 키 {@code mocha.search.model}(plan §5, ADR-50).
     * @param maxResults 보강 검색 출처 상한({@code mocha.search.max-results}) — 비용/지연 통제(plan §5).
     */
    public OpenAiSearchClient(OpenAIClient client, String model, int maxResults, ObjectMapper mapper) {
        this.client = client;
        this.model = model;
        this.maxResults = maxResults;
        this.mapper = mapper;
    }

    @Override
    public SearchResult search(SearchQuery query) {
        String json;
        try {
            json = rawSearch(query);
        } catch (RuntimeException e) {
            // POLICY: 호출 실패는 무결과와 구분해 로깅하되 empty()로 수렴 (ADR-13, AC-12, plan §7).
            // 호출/타임아웃 실패(a) — API 장애가 무결과로 은폐되던 P2 회귀 방지.
            log.warn("웹 검색 보강 호출 실패 — 빈 결과로 진행: coffee={}", query.coffeeName(), e);
            return SearchResult.empty();
        }
        return parse(json, query);
    }

    // 응답(JSON)을 SearchResult로 매핑한다. 형식 실패(비JSON·파싱 불가, a)와 진짜 무결과(정상 응답·채운 값
    // 없음, b)는 서로 다른 로그로 구분하되 둘 다 empty()로 수렴한다 (ADR-13, AC-12).
    private SearchResult parse(String json, SearchQuery query) {
        String body = extractJsonObject(json);
        if (body == null) {
            // 형식 실패(a): structured output이면 스키마 JSON이 와야 하는데 JSON 객체가 없다 — 비스키마 응답.
            log.warn("웹 검색 보강 형식 실패(비JSON 응답) — 빈 결과로 진행: coffee={}", query.coffeeName());
            return SearchResult.empty();
        }
        SearchResult result;
        try {
            SearchPayload payload = mapper.readValue(body, SearchPayload.class);
            result = new SearchResult(
                    payload.roastery(), toBeans(payload.beans()),
                    payload.roastLevel(), payload.officialNotes(), payload.sources());
        } catch (RuntimeException e) {
            // 형식 실패(a): 스키마 위반·파싱 불가.
            log.warn("웹 검색 보강 형식 실패(파싱 불가) — 빈 결과로 진행: coffee={}", query.coffeeName(), e);
            return SearchResult.empty();
        }
        if (result.isEmpty()) {
            // 진짜 무결과(b): 정상 응답인데 채운 값이 없음 — 실패와 구분해 관측한다(plan §6).
            log.info("웹 검색 보강 무결과(채운 값 없음) — 빈 결과로 진행: coffee={}", query.coffeeName());
        }
        return result;
    }

    /**
     * SDK 호출 경계 — Responses API에 web search tool을 붙여 보강 요청을 보내고 응답 텍스트를 돌려준다.
     * <p>테스트는 이 메서드를 override해 결정론적 응답으로 대체한다(실 API 스모크는 수동, CLAUDE.md §5.2).
     */
    protected String rawSearch(SearchQuery query) {
        Response response = client.responses().create(buildParams(query));
        return OpenAiResponseTexts.outputText(response);
    }

    // POLICY: 웹 검색 보강은 web_search를 tool_choice로 강제 + structured output(strict JSON)으로 수신 (ADR-13)
    // 파라미터 조립을 분리해 테스트가 SDK 호출 없이 도구·tool_choice·strict schema 배선을 검사할 수 있게 한다.
    ResponseCreateParams buildParams(SearchQuery query) {
        return ResponseCreateParams.builder()
                .model(model)
                .addTool(WebSearchTool.builder()
                        .type(WebSearchTool.Type.WEB_SEARCH)
                        // ADR-16: 검색을 KR로 지역화해 영어권 기본 착지(→영어 기록)를 막는다. search_context_size=high로
                        // 텍스트 컨텍스트를 넓힌다(공식 상세 페이지가 긴 통짜 문서인 경우가 많다).
                        .userLocation(WebSearchTool.UserLocation.builder()
                                .type(WebSearchTool.UserLocation.Type.APPROXIMATE)
                                .country("KR")
                                .timezone("Asia/Seoul")
                                .build())
                        .searchContextSize(WebSearchTool.SearchContextSize.HIGH)
                        .build())
                // P3: 검색을 강제해 모델이 건너뛰지 못하게 한다(AC-Δ2). 장착한 tool이 web_search 하나뿐이라
                //     tool_choice=required가 곧 "web_search를 부르라"이다 — GA 타입 상수가 ToolChoiceTypes에
                //     아직 없어(SDK 4.42: WEB_SEARCH_PREVIEW만) 타입 지목 대신 이 형태를 쓴다.
                .toolChoice(ToolChoiceOptions.REQUIRED)
                // P1: strict JSON schema로 받아 산문·인용 혼입에도 형식이 보장된다(AC-Δ1).
                .text(ResponseTextConfig.builder()
                        .format(searchSchemaFormat())
                        .build())
                .instructions(INSTRUCTIONS.formatted(maxResults))
                .input(buildInput(query))
                .build();
    }

    // SearchResult 5필드(roastery/beans/roast_level/official_notes/sources)의 strict JSON schema.
    // 문자열류는 미확인 시 null 허용(["string","null"]), beans는 원두별 요소 배열(요소도 strict —
    // description string·process nullable, ADR-53), notes/sources는 문자열 배열.
    // 전 필드 required·additionalProperties=false. 구 official_page_url은 2단계와 함께 사라졌다(D-16 ④).
    private static ResponseFormatTextJsonSchemaConfig searchSchemaFormat() {
        Map<String, Object> nullableString = Map.of("type", List.of("string", "null"));
        Map<String, Object> stringArray = Map.of("type", "array", "items", Map.of("type", "string"));

        Map<String, Object> beanProperties = new LinkedHashMap<>();
        beanProperties.put("description", Map.of("type", "string"));
        beanProperties.put("process", nullableString);
        Map<String, Object> beanElement = new LinkedHashMap<>();
        beanElement.put("type", "object");
        beanElement.put("properties", beanProperties);
        beanElement.put("required", List.of("description", "process"));
        beanElement.put("additionalProperties", false);

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("roastery", nullableString);
        properties.put("beans", Map.of("type", "array", "items", beanElement));
        properties.put("roast_level", nullableString);
        properties.put("official_notes", stringArray);
        properties.put("sources", stringArray);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("roastery", "beans", "roast_level", "official_notes", "sources"));
        schema.put("additionalProperties", false);

        ResponseFormatTextJsonSchemaConfig.Schema.Builder builder =
                ResponseFormatTextJsonSchemaConfig.Schema.builder();
        schema.forEach((key, value) -> builder.putAdditionalProperty(key, JsonValue.from(value)));
        return ResponseFormatTextJsonSchemaConfig.builder()
                .name("coffee_enrichment")
                .strict(true)
                .schema(builder.build())
                .build();
    }

    // POLICY: web_search 검색 앵커는 "커피 이름:"/"로스터리:" 필드 라벨 없이 로스터리명+커피명을 자연
    //         검색어로 넘긴다 — 라벨 통짜가 쿼리로 나가 공식 페이지 착지를 막던 회귀를 제거(ref: delta 0005 D4/AC-Δ4, plan#ADR-14).
    // POLICY: 도메인 키워드 '원두'를 앵커에 더해 커피 문맥을 고정한다 — 문맥 없는 원두명이 지명·동명 상품으로
    //         새던 문제를 막는다. 필드 라벨이 아니라 자연 검색어라 ADR-14와 양립한다(ref: delta 0006 ADR-16).
    String buildInput(SearchQuery query) {
        String anchor;
        if (query.roastery() != null && !query.roastery().isBlank()) {
            anchor = query.roastery().strip() + " " + query.coffeeName().strip();
        } else {
            anchor = query.coffeeName().strip();
        }
        return anchor + " 원두";
    }

    // 응답에 코드펜스·인용 문구가 섞여도 첫 '{' ~ 마지막 '}' 구간만 취한다. 없으면 null(형식 실패 취급).
    private static String extractJsonObject(String text) {
        if (text == null) {
            return null;
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return text.substring(start, end + 1);
    }

    // 요소 DTO → SearchResult.Bean 변환 — 빈 description 드롭 등 위생은 SearchResult 생성자가 맡는다.
    private static List<SearchResult.Bean> toBeans(List<BeanPayload> beans) {
        if (beans == null) {
            return null;
        }
        return beans.stream()
                .map(b -> b == null ? null : new SearchResult.Bean(b.description(), b.process()))
                .toList();
    }

    /** 검색 응답 매핑용 관대한 DTO — 알 수 없는 필드는 무시(LLM 출력의 잉여 필드가 전체 결과를 깨지 않게). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SearchPayload(
            String roastery,
            List<BeanPayload> beans,
            String roastLevel,
            List<String> officialNotes,
            List<String> sources) {
    }

    /** beans 요소 매핑용 관대한 DTO (ADR-53). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BeanPayload(String description, String process) {
    }
}
