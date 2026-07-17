package com.devwuu.mocha.smoke;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFormatTextJsonSchemaConfig;
import com.openai.models.responses.ResponseInputContent;
import com.openai.models.responses.ResponseInputImage;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseInputText;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseTextConfig;
import com.openai.models.responses.ToolChoiceTypes;
import com.openai.models.responses.WebSearchPreviewTool;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * TΔ0 실측 프로브(수동, 비용 발생) — change 0006의 설계 입력 3가지를 실 API로 관측한다.
 * `OpenAiSearchSmokeTest` 변형. 결과는 findings-TΔ0.md로 기록한다
 * (ref: specs/coffee-note-agent/changes/0006-official-page-image-ocr/tasks.md#TΔ0).
 * <p>① 1단계 strict schema에 official_page_url 추가 시 모델이 실제 공식 상품 페이지 URL을 반환하는가
 * <p>② gpt-4o + web_search_preview에서 userLocation(KR)·searchContextSize(high)가 정상 동작하는가
 * <p>③ 모모스 상세 이미지 URL을 Responses API 이미지 입력으로 직접 넘기면 fetch·판독되는가(핫링크 차단, OQ-Δ1)
 * <p>단언 없이 원 응답을 출력만 한다 — 프로브는 관측이 산출물이고, 판정은 findings 문서에서 한다.
 * 기본 test 제외(@Tag("openai")), 실행: {@code ./gradlew openaiTest --tests '*OfficialPageOcrProbeSmokeTest*'}
 */
@Tag("openai")
class OfficialPageOcrProbeSmokeTest {

    private static final String MODEL = "gpt-4o";

    // 2026-07-12 모모스 "원두 시즈널 블렌드 와이키키" 상품 페이지(#prdDetail)에서 수집한 실제 상세 이미지 URL 2장.
    // Referer 없는 curl 직접 접근은 200 확인 — ③은 OpenAI 측 fetcher가 같은 URL을 읽을 수 있는지를 본다.
    private static final String WAIKIKI_PAGE =
            "https://momos.co.kr/product/%EC%9B%90%EB%91%90-%EC%8B%9C%EC%A6%88%EB%84%90-%EB%B8%94%EB%A0%8C%EB%93%9C-%EC%99%80%EC%9D%B4%ED%82%A4%ED%82%A4/2323/";
    private static final List<String> WAIKIKI_DETAIL_IMAGES = List.of(
            "https://momos.co.kr/web/upload/NNEditor/20260608/347fcc076fdfc6d4b65fe4ddd4e2971e.jpg",
            "https://momos.co.kr/web/upload/NNEditor/20260608/c11fb74c447aff4ef1fb47f5a6ecd905.jpg");

    /** ① strict schema + official_page_url — 공식 상품 페이지 URL이 실제로 반환되는지(와이키키/모모스). */
    @Test
    void probe1_officialPageUrlViaStrictSchema() throws Exception {
        OpenAIClient client = client();

        String instructions = """
                너는 커피 원두 정보를 웹 검색으로 보강하는 도우미다. 주어진 커피에 대해 웹을 검색하고 아래 규칙을 반드시 지켜라.
                - official_page_url에는 그 로스터리 공식 웹사이트의 이 원두 상품 상세 페이지 URL만 담는다.
                  로스터리명과 원두명이 함께 확인되는 페이지만 인정하고, 확인 못 하면 null로 둔다.
                - official_notes(로스터리 공식 테이스팅 노트)는 로스터리 공식 페이지에서 확인된 경우에만 채운다.
                - origin/process/roast_level은 로스터리 공식 페이지 우선, 없으면 신뢰할 일반 출처로 채운다.
                - 확인되지 않은 값은 추측하지 말고 null(문자열)·빈 배열(리스트)로 둔다.
                - sources에는 실제로 참고한 출처 URL만 담는다. 최대 3개.
                """;

        ResponseCreateParams params = ResponseCreateParams.builder()
                .model(MODEL)
                .addTool(WebSearchPreviewTool.builder()
                        .type(WebSearchPreviewTool.Type.WEB_SEARCH_PREVIEW)
                        .build())
                .toolChoice(ToolChoiceTypes.builder()
                        .type(ToolChoiceTypes.Type.WEB_SEARCH_PREVIEW)
                        .build())
                .text(ResponseTextConfig.builder()
                        .format(schemaWithOfficialPageUrl())
                        .build())
                .instructions(instructions)
                .input("모모스 커피 와이키키 원두")
                .build();

        run("probe1: official_page_url in strict schema", client, params);
    }

    /** ①+② 결합 — TΔ4 최종 형태(스키마+지역화)에서 official_page_url이 실제로 채워지는지. ① 단독(비지역화)은 null이었다. */
    @Test
    void probe4_officialPageUrlWithKrLocalization() throws Exception {
        OpenAIClient client = client();

        String instructions = """
                너는 커피 원두 정보를 웹 검색으로 보강하는 도우미다. 주어진 커피에 대해 웹을 검색하고 아래 규칙을 반드시 지켜라.
                - official_page_url에는 그 로스터리 공식 웹사이트의 이 원두 상품 상세 페이지 URL만 담는다.
                  로스터리명과 원두명이 함께 확인되는 페이지만 인정하고, 확인 못 하면 null로 둔다.
                - official_notes(로스터리 공식 테이스팅 노트)는 로스터리 공식 페이지에서 확인된 경우에만 채운다.
                - origin/process/roast_level은 로스터리 공식 페이지 우선, 없으면 신뢰할 일반 출처로 채운다.
                - 모든 문자열 값은 한국어로 기록한다(영문 출처는 번역).
                - 확인되지 않은 값은 추측하지 말고 null(문자열)·빈 배열(리스트)로 둔다.
                - sources에는 실제로 참고한 출처 URL만 담는다. 최대 3개.
                """;

        ResponseCreateParams params = ResponseCreateParams.builder()
                .model(MODEL)
                .addTool(WebSearchPreviewTool.builder()
                        .type(WebSearchPreviewTool.Type.WEB_SEARCH_PREVIEW)
                        .userLocation(WebSearchPreviewTool.UserLocation.builder()
                                .type(JsonValue.from("approximate"))
                                .country("KR")
                                .timezone("Asia/Seoul")
                                .build())
                        .searchContextSize(WebSearchPreviewTool.SearchContextSize.HIGH)
                        .build())
                .toolChoice(ToolChoiceTypes.builder()
                        .type(ToolChoiceTypes.Type.WEB_SEARCH_PREVIEW)
                        .build())
                .text(ResponseTextConfig.builder()
                        .format(schemaWithOfficialPageUrl())
                        .build())
                .instructions(instructions)
                .input("모모스 커피 와이키키 원두")
                .build();

        run("probe4: official_page_url + KR localization (TΔ4 최종 형태)", client, params);
    }

    /** ① 지침 강화 변형 — 공식 도메인 탐색을 명시 유도(추가 검색 허용)하면 official_page_url이 채워지는지. probe4는 일관 null. */
    @Test
    void probe5_officialPageUrlWithExplicitOfficialSiteSearch() throws Exception {
        OpenAIClient client = client();

        String instructions = """
                너는 커피 원두 정보를 웹 검색으로 보강하는 도우미다. 주어진 커피에 대해 웹을 검색하고 아래 규칙을 반드시 지켜라.
                - 반드시 그 로스터리의 공식 웹사이트(공식 온라인 쇼핑몰)에서 이 원두의 상품 상세 페이지를 찾아라.
                  블로그·리뷰 페이지가 먼저 나오면 거기서 멈추지 말고, '로스터리명 + 공식' '로스터리명 + 스토어' 등으로 추가 검색해 공식 도메인을 찾아라.
                - official_page_url에는 공식 도메인의 이 원두 상품 상세 페이지 URL만 담는다. 끝내 확인 못 하면 null로 둔다.
                - official_notes(로스터리 공식 테이스팅 노트)는 로스터리 공식 페이지에서 확인된 경우에만 채운다.
                - origin/process/roast_level은 로스터리 공식 페이지 우선, 없으면 신뢰할 일반 출처로 채운다.
                - 모든 문자열 값은 한국어로 기록한다(영문 출처는 번역).
                - 확인되지 않은 값은 추측하지 말고 null(문자열)·빈 배열(리스트)로 둔다.
                - sources에는 실제로 참고한 출처 URL만 담는다. 최대 3개.
                """;

        ResponseCreateParams params = ResponseCreateParams.builder()
                .model(MODEL)
                .addTool(WebSearchPreviewTool.builder()
                        .type(WebSearchPreviewTool.Type.WEB_SEARCH_PREVIEW)
                        .userLocation(WebSearchPreviewTool.UserLocation.builder()
                                .type(JsonValue.from("approximate"))
                                .country("KR")
                                .timezone("Asia/Seoul")
                                .build())
                        .searchContextSize(WebSearchPreviewTool.SearchContextSize.HIGH)
                        .build())
                .toolChoice(ToolChoiceTypes.builder()
                        .type(ToolChoiceTypes.Type.WEB_SEARCH_PREVIEW)
                        .build())
                .text(ResponseTextConfig.builder()
                        .format(schemaWithOfficialPageUrl())
                        .build())
                .instructions(instructions)
                .input("모모스 커피 와이키키 원두")
                .build();

        run("probe5: official_page_url + 공식 도메인 탐색 유도", client, params);
    }

    /** ① URL 환각 가드 변형 — probe5의 URL이 404(조합 추정)라, "검색 결과에 실제로 나타난 URL만" 지침이 유효 URL을 만드는지. */
    @Test
    void probe6_officialPageUrlWithAntiHallucinationRule() throws Exception {
        OpenAIClient client = client();

        String instructions = """
                너는 커피 원두 정보를 웹 검색으로 보강하는 도우미다. 주어진 커피에 대해 웹을 검색하고 아래 규칙을 반드시 지켜라.
                - 반드시 그 로스터리의 공식 웹사이트(공식 온라인 쇼핑몰)에서 이 원두의 상품 상세 페이지를 찾아라.
                  블로그·리뷰 페이지가 먼저 나오면 거기서 멈추지 말고, '로스터리명 + 공식' '로스터리명 + 스토어' 등으로 추가 검색해 공식 도메인을 찾아라.
                - official_page_url에는 공식 도메인의 이 원두 상품 상세 페이지 URL만 담는다.
                  검색 결과에 실제로 나타난 URL을 그대로 담아라. 기억이나 추측으로 URL을 만들거나 일부를 생략·변형하지 마라.
                  검색 결과에서 그런 URL을 끝내 확인 못 하면 null로 둔다.
                - official_notes(로스터리 공식 테이스팅 노트)는 로스터리 공식 페이지에서 확인된 경우에만 채운다.
                - origin/process/roast_level은 로스터리 공식 페이지 우선, 없으면 신뢰할 일반 출처로 채운다.
                - 모든 문자열 값은 한국어로 기록한다(영문 출처는 번역).
                - 확인되지 않은 값은 추측하지 말고 null(문자열)·빈 배열(리스트)로 둔다.
                - sources에는 실제로 참고한 출처 URL만 담는다. 최대 3개.
                """;

        ResponseCreateParams params = ResponseCreateParams.builder()
                .model(MODEL)
                .addTool(WebSearchPreviewTool.builder()
                        .type(WebSearchPreviewTool.Type.WEB_SEARCH_PREVIEW)
                        .userLocation(WebSearchPreviewTool.UserLocation.builder()
                                .type(JsonValue.from("approximate"))
                                .country("KR")
                                .timezone("Asia/Seoul")
                                .build())
                        .searchContextSize(WebSearchPreviewTool.SearchContextSize.HIGH)
                        .build())
                .toolChoice(ToolChoiceTypes.builder()
                        .type(ToolChoiceTypes.Type.WEB_SEARCH_PREVIEW)
                        .build())
                .text(ResponseTextConfig.builder()
                        .format(schemaWithOfficialPageUrl())
                        .build())
                .instructions(instructions)
                .input("모모스 커피 와이키키 원두")
                .build();

        run("probe6: official_page_url + URL 환각 금지", client, params);
    }

    /** ② userLocation(KR)·searchContextSize(high) — gpt-4o + web_search_preview에서 파라미터 수용·정상 응답 여부만 격리 관측. */
    @Test
    void probe2_userLocationKrAndHighContextSize() throws Exception {
        OpenAIClient client = client();

        ResponseCreateParams params = ResponseCreateParams.builder()
                .model(MODEL)
                .addTool(WebSearchPreviewTool.builder()
                        .type(WebSearchPreviewTool.Type.WEB_SEARCH_PREVIEW)
                        .userLocation(WebSearchPreviewTool.UserLocation.builder()
                                .type(JsonValue.from("approximate"))
                                .country("KR")
                                .timezone("Asia/Seoul")
                                .build())
                        .searchContextSize(WebSearchPreviewTool.SearchContextSize.HIGH)
                        .build())
                .toolChoice(ToolChoiceTypes.builder()
                        .type(ToolChoiceTypes.Type.WEB_SEARCH_PREVIEW)
                        .build())
                .instructions("주어진 커피 원두를 웹 검색해 무엇을 찾았는지 참고한 페이지 URL과 함께 한국어로 짧게 요약하라.")
                .input("모모스 커피 와이키키 원두")
                .build();

        run("probe2: userLocation(KR) + searchContextSize(high)", client, params);
    }

    /** ③ 상세 이미지 URL 직접 vision 입력 — OpenAI가 로스터리 서버의 이미지를 fetch·판독하는가(OQ-Δ1 핫링크 관측). */
    @Test
    void probe3_visionReadsDetailImageUrls() throws Exception {
        OpenAIClient client = client();

        String instructions = """
                너는 커피 상품 상세 이미지에서 원두 정보를 읽어 구조화하는 도우미다.
                - 이미지에 실제로 적힌 내용만 채운다. 확인되지 않은 값은 추측하지 말고 null(문자열)·빈 배열(리스트)로 둔다.
                - 모든 값은 한국어로 기록한다(영문 표기는 번역).
                """;

        List<ResponseInputContent> content = new java.util.ArrayList<>();
        content.add(ResponseInputContent.ofInputText(ResponseInputText.builder()
                .text("로스터리 '모모스커피'의 커피 '시즈널 블렌드 와이키키' 공식 상품 상세 이미지다. 원산지·가공방식·로스팅 정도·공식 테이스팅 노트를 읽어라.")
                .build()));
        for (String url : WAIKIKI_DETAIL_IMAGES) {
            content.add(ResponseInputContent.ofInputImage(ResponseInputImage.builder()
                    .imageUrl(url)
                    .detail(ResponseInputImage.Detail.HIGH)
                    .build()));
        }

        ResponseCreateParams params = ResponseCreateParams.builder()
                .model(MODEL)
                .inputOfResponse(List.of(ResponseInputItem.ofEasyInputMessage(EasyInputMessage.builder()
                        .role(EasyInputMessage.Role.USER)
                        .contentOfResponseInputMessageContentList(content)
                        .build())))
                .text(ResponseTextConfig.builder()
                        .format(visionSchema())
                        .build())
                .instructions(instructions)
                .build();

        System.out.println("source page   = " + WAIKIKI_PAGE);
        System.out.println("image inputs  = " + WAIKIKI_DETAIL_IMAGES);
        run("probe3: vision reads detail image URLs (OQ-Δ1)", client, params);
    }

    // 프로브 공통 실행 — 호출 실패(핫링크 차단·파라미터 거부 등)도 관측 대상이므로 예외를 삼키고 원문을 출력한다.
    private static void run(String title, OpenAIClient client, ResponseCreateParams params) {
        System.out.println("=== " + title + " ===");
        try {
            Response response = client.responses().create(params);
            System.out.println("status         = " + response.status());
            System.out.println("error          = " + response.error());
            System.out.println("incompleteInfo = " + response.incompleteDetails());
            System.out.println("output items   = " + response.output().size());
            int i = 0;
            for (ResponseOutputItem item : response.output()) {
                String kind = item.isMessage() ? "message"
                        : item.isWebSearchCall() ? "web_search_call"
                        : item.isReasoning() ? "reasoning"
                        : "other";
                System.out.println("  [" + (i++) + "] kind=" + kind);
                if (item.isMessage()) {
                    for (ResponseOutputMessage.Content c : item.asMessage().content()) {
                        if (c.isOutputText()) {
                            System.out.println("      output_text: " + c.asOutputText().text());
                        } else if (c.isRefusal()) {
                            System.out.println("      refusal: " + c.asRefusal().refusal());
                        }
                    }
                } else if (item.isWebSearchCall()) {
                    System.out.println("      action=" + item.asWebSearchCall().action()
                            + " status=" + item.asWebSearchCall().status());
                }
            }
        } catch (RuntimeException e) {
            System.out.println("CALL FAILED: " + e.getClass().getName());
            System.out.println("  message: " + e.getMessage());
        }
        System.out.println("=== END " + title + " ===");
    }

    // 기존 검색 스키마(6필드) + official_page_url(nullable) — TΔ4가 도입할 1단계 스키마의 프로브 버전.
    private static ResponseFormatTextJsonSchemaConfig schemaWithOfficialPageUrl() {
        Map<String, Object> nullableString = Map.of("type", List.of("string", "null"));
        Map<String, Object> stringArray = Map.of("type", "array", "items", Map.of("type", "string"));

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("roastery", nullableString);
        properties.put("origin", nullableString);
        properties.put("process", nullableString);
        properties.put("roast_level", nullableString);
        properties.put("official_notes", stringArray);
        properties.put("official_page_url", nullableString);
        properties.put("sources", stringArray);

        return strictSchema("coffee_enrichment_probe", properties);
    }

    // TΔ2가 도입할 VisionExtraction 필드의 프로브 버전(5필드, sources 없음 — 출처는 공식 페이지로 고정이므로).
    private static ResponseFormatTextJsonSchemaConfig visionSchema() {
        Map<String, Object> nullableString = Map.of("type", List.of("string", "null"));
        Map<String, Object> stringArray = Map.of("type", "array", "items", Map.of("type", "string"));

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("roastery", nullableString);
        properties.put("origin", nullableString);
        properties.put("process", nullableString);
        properties.put("roast_level", nullableString);
        properties.put("official_notes", stringArray);

        return strictSchema("coffee_vision_probe", properties);
    }

    private static ResponseFormatTextJsonSchemaConfig strictSchema(String name, Map<String, Object> properties) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.copyOf(properties.keySet()));
        schema.put("additionalProperties", false);

        ResponseFormatTextJsonSchemaConfig.Schema.Builder builder =
                ResponseFormatTextJsonSchemaConfig.Schema.builder();
        schema.forEach((key, value) -> builder.putAdditionalProperty(key, JsonValue.from(value)));
        return ResponseFormatTextJsonSchemaConfig.builder()
                .name(name)
                .strict(true)
                .schema(builder.build())
                .build();
    }

    private static OpenAIClient client() throws Exception {
        String apiKey = resolveApiKey();
        assumeTrue(apiKey != null && !apiKey.isBlank(), "OPENAI_API_KEY 미설정 — 스모크 스킵");
        return OpenAIOkHttpClient.builder().apiKey(apiKey).build();
    }

    // .env.local(KEY=VALUE properties)에서 OPENAI_API_KEY를 읽는다. 없으면 환경변수 폴백. (OpenAiSearchSmokeTest와 동일)
    private static String resolveApiKey() throws Exception {
        Path envLocal = Path.of(".env.local");
        if (Files.exists(envLocal)) {
            Properties props = new Properties();
            try (InputStream in = Files.newInputStream(envLocal)) {
                props.load(in);
            }
            String key = props.getProperty("OPENAI_API_KEY");
            if (key != null && !key.isBlank()) {
                return key;
            }
        }
        return System.getenv("OPENAI_API_KEY");
    }
}
