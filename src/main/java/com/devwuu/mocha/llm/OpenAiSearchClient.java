package com.devwuu.mocha.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.openai.client.OpenAIClient;
import com.openai.core.JsonValue;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFormatTextJsonSchemaConfig;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseTextConfig;
import com.openai.models.responses.ToolChoiceTypes;
import com.openai.models.responses.WebSearchPreviewTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI web search(Responses API) 기반 {@link SearchClient} 구현 (ref: plan.md#ADR-5, spec FR-3).
 * <p>OpenAI SDK 타입은 이 클래스 안에만 존재한다(plan §4 POLICY, NFR-4). 검색 지침(instructions)에
 * fallback 규칙을 인코딩한다 — official_notes는 <b>로스터리 공식 출처 한정</b>이고, 공식 페이지를 못
 * 찾으면 단일 원산지 커피만 원두 일반 사실(origin 등)을 일반 출처에서 보강하고 블렌드는 공란으로 둔다
 * (FR-3/AC-16, ADR-5 보강 fallback POLICY). "추측 금지"는 유지한다.
 * <p>보강은 Responses API 한 콜로 (a) {@code web_search_preview} 도구를 {@code tool_choice}로 강제 실행하고,
 * (b) {@code text.format}에 strict JSON schema를 붙여 <b>스키마 보장 JSON</b>으로 받는다 — 응답에 산문·인용이
 * 섞여도 형식 때문에 검색 결과가 버려지지 않게(P1), 경량 모델이 검색을 건너뛰지 못하게(P3) 한다(ADR-13).
 * <p>검색 실패·무결과·응답 파싱 실패는 예외로 새지 않고 {@link SearchResult#empty()}로 수렴한다 —
 * 상위(NoteEnricher)가 사용자 입력만으로 진행하게 하기 위함(AC-12, plan §7).
 */
public class OpenAiSearchClient implements SearchClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiSearchClient.class);

    // POLICY: official_notes는 로스터리 출처 한정(fallback 없음), 단일 원산지만 일반 출처 fallback,
    //         블렌드는 공란, 추측 금지 (ref: spec FR-3/AC-16, plan#ADR-5 보강 fallback POLICY)
    private static final String INSTRUCTIONS = """
            너는 커피 원두 정보를 웹 검색으로 보강하는 도우미다. 주어진 커피에 대해 웹을 검색하고 아래 규칙을 반드시 지켜라.
            - official_notes(로스터리 공식 테이스팅 노트)는 그 로스터리의 공식 웹/판매 페이지에서 확인된 경우에만 채운다.
              제3자 블로그·리뷰의 감상은 official_notes에 넣지 않는다. 공식 출처를 못 찾으면 official_notes는 빈 배열로 둔다.
            - 로스터리 공식 페이지를 못 찾은 경우: 단일 원산지 커피면 origin/process/roast_level 같은 원두 일반 사실만
              신뢰할 만한 일반 출처에서 보강한다. 블렌드(여러 원산지 구성)면 origin/process 등을 빈 값(null)으로 둔다.
            - 확인되지 않은 값은 추측하지 말고 null(문자열 필드)·빈 배열(리스트)로 둔다.
            - sources에는 실제로 참고한 출처 URL만 담는다. 최대 %d개.
            - 출력은 아래 JSON 객체 하나만, 다른 설명 없이 반환한다:
              {"roastery": string|null, "origin": string|null, "process": string|null, "roast_level": string|null,
               "official_notes": string[], "sources": string[]}
            """;

    private final OpenAIClient client;
    private final String model;
    private final int maxResults;
    private final ObjectMapper mapper;

    /**
     * @param maxResults 보강 검색 출처 상한(mocha.search.max-results) — 비용/지연 통제(plan §5).
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
            // AC-12: 검색 실패/타임아웃 → 빈 결과로 진행(예외를 위로 던지지 않음). plan §7.
            log.warn("웹 검색 보강 실패 — 빈 결과로 진행: coffee={}", query.coffeeName(), e);
            return SearchResult.empty();
        }
        return parse(json, query);
    }

    // 응답 텍스트(JSON)를 SearchResult로 매핑. 무결과·비어있음·파싱 실패는 모두 empty()로 흡수(AC-12).
    private SearchResult parse(String json, SearchQuery query) {
        String body = extractJsonObject(json);
        if (body == null) {
            log.info("웹 검색 보강 무결과/비JSON 응답 — 빈 결과로 진행: coffee={}", query.coffeeName());
            return SearchResult.empty();
        }
        try {
            SearchPayload payload = mapper.readValue(body, SearchPayload.class);
            return new SearchResult(
                    payload.roastery(), payload.origin(), payload.process(), payload.roastLevel(),
                    payload.officialNotes(), payload.sources());
        } catch (RuntimeException e) {
            log.warn("웹 검색 응답 파싱 실패 — 빈 결과로 진행: coffee={}", query.coffeeName(), e);
            return SearchResult.empty();
        }
    }

    /**
     * SDK 호출 경계 — Responses API에 web search tool을 붙여 보강 요청을 보내고 응답 텍스트를 돌려준다.
     * <p>테스트는 이 메서드를 override해 결정론적 응답으로 대체한다(실 API 스모크는 수동, CLAUDE.md §5.2).
     */
    protected String rawSearch(SearchQuery query) {
        Response response = client.responses().create(buildParams(query));
        return outputText(response);
    }

    // POLICY: 웹 검색 보강은 web_search를 tool_choice로 강제 + structured output(strict JSON)으로 수신 (ADR-13)
    // 파라미터 조립을 분리해 테스트가 SDK 호출 없이 도구·tool_choice·strict schema 배선을 검사할 수 있게 한다.
    ResponseCreateParams buildParams(SearchQuery query) {
        return ResponseCreateParams.builder()
                .model(model)
                .addTool(WebSearchPreviewTool.builder()
                        .type(WebSearchPreviewTool.Type.WEB_SEARCH_PREVIEW)
                        .build())
                // P3: web_search를 강제해 모델이 검색을 건너뛰지 못하게 한다(AC-Δ2).
                .toolChoice(ToolChoiceTypes.builder()
                        .type(ToolChoiceTypes.Type.WEB_SEARCH_PREVIEW)
                        .build())
                // P1: strict JSON schema로 받아 산문·인용 혼입에도 형식이 보장된다(AC-Δ1).
                .text(ResponseTextConfig.builder()
                        .format(searchSchemaFormat())
                        .build())
                .instructions(INSTRUCTIONS.formatted(maxResults))
                .input(buildInput(query))
                .build();
    }

    // SearchPayload 6필드(roastery/origin/process/roast_level/official_notes/sources)의 strict JSON schema.
    // roastery류는 미확인 시 null 허용(["string","null"]), notes/sources는 문자열 배열. 전 필드 required·additionalProperties=false.
    private static ResponseFormatTextJsonSchemaConfig searchSchemaFormat() {
        return ResponseFormatTextJsonSchemaConfig.builder()
                .name("coffee_enrichment")
                .strict(true)
                .schema(searchSchema())
                .build();
    }

    private static ResponseFormatTextJsonSchemaConfig.Schema searchSchema() {
        Map<String, Object> nullableString = Map.of("type", List.of("string", "null"));
        Map<String, Object> stringArray = Map.of("type", "array", "items", Map.of("type", "string"));

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("roastery", nullableString);
        properties.put("origin", nullableString);
        properties.put("process", nullableString);
        properties.put("roast_level", nullableString);
        properties.put("official_notes", stringArray);
        properties.put("sources", stringArray);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of(
                "roastery", "origin", "process", "roast_level", "official_notes", "sources"));
        schema.put("additionalProperties", false);

        ResponseFormatTextJsonSchemaConfig.Schema.Builder builder =
                ResponseFormatTextJsonSchemaConfig.Schema.builder();
        schema.forEach((key, value) -> builder.putAdditionalProperty(key, JsonValue.from(value)));
        return builder.build();
    }

    private String buildInput(SearchQuery query) {
        StringBuilder sb = new StringBuilder("커피 이름: ").append(query.coffeeName());
        if (query.roastery() != null && !query.roastery().isBlank()) {
            sb.append("\n로스터리: ").append(query.roastery());
        }
        return sb.toString();
    }

    // Responses 출력 아이템 중 메시지 텍스트만 이어붙인다(web search 호출 아이템 등은 건너뜀).
    private String outputText(Response response) {
        StringBuilder sb = new StringBuilder();
        for (ResponseOutputItem item : response.output()) {
            if (!item.isMessage()) {
                continue;
            }
            for (ResponseOutputMessage.Content content : item.asMessage().content()) {
                if (content.isOutputText()) {
                    sb.append(content.asOutputText().text());
                }
            }
        }
        return sb.toString();
    }

    // 응답에 코드펜스·인용 문구가 섞여도 첫 '{' ~ 마지막 '}' 구간만 취한다. 없으면 null(무결과 취급).
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

    /** 검색 응답 매핑용 관대한 DTO — 알 수 없는 필드는 무시(LLM 출력의 잉여 필드가 전체 결과를 깨지 않게). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SearchPayload(
            String roastery,
            String origin,
            String process,
            String roastLevel,
            List<String> officialNotes,
            List<String> sources) {
    }
}
