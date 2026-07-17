package com.devwuu.mocha.llm;

import com.devwuu.mocha.domain.Aliases;
import com.devwuu.mocha.pipeline.AliasGenerator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.openai.client.OpenAIClient;
import com.openai.core.JsonValue;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFormatTextJsonSchemaConfig;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseTextConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI Responses API structured output 기반 {@link AliasGenerator} 구현
 * (ref: plan.md#ADR-37·ADR-50, data-model.md#4.1; changes/0018 TΔ8b — 구 공용 단발 콜
 * {@code OpenAiLlmClient} 폐기에 따른 보조 콜 전용 재배선).
 * <p>OpenAI SDK 타입은 이 클래스 안에만 존재한다(plan §4 POLICY, NFR-4 — {@code OpenAiVisionClient}와
 * 동일 규칙). {@code text.format}에 strict JSON schema를 붙여 스키마 보장 JSON으로
 * {@code coffee_name_aliases/roastery_aliases}를 받는다.
 * <p>POLICY: 별칭 생성 콜 실패·스키마 위반은 저장을 되돌리지 않는다 — 빈 배열({@link Aliases#empty()})로
 * 수렴하고 노트 저장은 유지한다 (ref: plan.md §7, V-13, ADR-37).
 */
public class OpenAiAliasGenerator implements AliasGenerator {

    private static final Logger log = LoggerFactory.getLogger(OpenAiAliasGenerator.class);

    private static final String SCHEMA_NAME = "coffee_note_aliases";

    private static final String INSTRUCTIONS = """
            너는 커피 커피명·로스터리의 한국어 음차·이표기를 생성하는 도우미다. 아래 규칙을 지켜라.
            - 입력으로 커피명(coffee_name)과 로스터리(roastery) 원문이 주어진다.
            - 각각에 대해 한국어로 읽었을 때 자연스러운 음차·이표기를 배열로 만든다(예: "Ethiopia Chelbesa" → "에티오피아 첼베사", "FroB" → "프롭","프로브").
            - 원문 표기 그 자체는 별칭에 넣지 않는다 — 서버가 이미 원문을 대조 집합에 포함한다. 음차·이표기만 담는다.
            - 뜻을 번역하지 말고 발음을 한글로 옮긴다. 여러 표기가 가능하면 모두 담는다.
            - 마땅한 이표기가 없으면(이미 한국어이거나 음차가 무의미) 해당 배열은 빈 배열로 둔다. 억지로 지어내지 않는다.
            입력은 coffee_name/roastery를 담은 JSON으로 주어진다.
            """;

    private final OpenAIClient client;
    private final String model;
    private final ObjectMapper mapper;

    public OpenAiAliasGenerator(OpenAIClient client, String model, ObjectMapper mapper) {
        this.client = client;
        this.model = model;
        this.mapper = mapper;
    }

    @Override
    public Aliases generate(String coffeeName, String roastery) {
        try {
            String userPrompt = mapper.writeValueAsString(new AliasRequest(coffeeName, roastery));
            AliasPayload payload = mapper.readValue(call(userPrompt), AliasPayload.class);
            // 생성 콜에서만 채워지는 음차·이표기 — 원문 표시값은 여기 담지 않는다(V-13, 대조 시 별도 포함).
            return new Aliases(payload.coffeeNameAliases(), payload.roasteryAliases());
        } catch (RuntimeException e) {
            // POLICY: 생성 실패는 저장을 되돌리지 않는다 — 빈 배열 수렴 + 로그, 관측 축적이 보완(plan §7, ADR-37).
            log.warn("별칭 생성 실패 — 빈 별칭으로 저장(노트 저장 유지): coffeeName={} roastery={}",
                    coffeeName, roastery, e);
            return Aliases.empty();
        }
    }

    /**
     * SDK 호출 경계 — Responses API에 strict schema로 별칭 생성을 요청하고 응답 텍스트를 돌려준다.
     * <p>테스트는 이 메서드를 override해 결정론적 응답으로 대체한다(실 API 스모크는 수동, CLAUDE.md §5.2).
     */
    protected String call(String userPrompt) {
        Response response = client.responses().create(buildParams(userPrompt));
        return outputText(response);
    }

    // 파라미터 조립을 분리해 테스트가 SDK 호출 없이 strict schema·모델 배선을 검사할 수 있게 한다.
    ResponseCreateParams buildParams(String userPrompt) {
        return ResponseCreateParams.builder()
                .model(model)
                .inputOfResponse(List.of(ResponseInputItem.ofEasyInputMessage(EasyInputMessage.builder()
                        .role(EasyInputMessage.Role.USER)
                        .content(userPrompt)
                        .build())))
                .text(ResponseTextConfig.builder()
                        .format(aliasSchemaFormat())
                        .build())
                .instructions(INSTRUCTIONS)
                .build();
    }

    // data-model §4.1 응답 스키마 — 한국어 음차·이표기 목록. 전 필드 required·additionalProperties=false.
    private static ResponseFormatTextJsonSchemaConfig aliasSchemaFormat() {
        Map<String, Object> stringArray = Map.of("type", "array", "items", Map.of("type", "string"));

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("coffee_name_aliases", stringArray);
        properties.put("roastery_aliases", stringArray);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("coffee_name_aliases", "roastery_aliases"));
        schema.put("additionalProperties", false);

        ResponseFormatTextJsonSchemaConfig.Schema.Builder builder =
                ResponseFormatTextJsonSchemaConfig.Schema.builder();
        schema.forEach((key, value) -> builder.putAdditionalProperty(key, JsonValue.from(value)));
        return ResponseFormatTextJsonSchemaConfig.builder()
                .name(SCHEMA_NAME)
                .strict(true)
                .schema(builder.build())
                .build();
    }

    // Responses 출력 아이템 중 메시지 텍스트만 이어붙인다.
    private static String outputText(Response response) {
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

    /** data-model §4.1 요청 스키마 대응 페이로드 — coffee_name/roastery로 직렬화(snake_case). */
    private record AliasRequest(String coffeeName, String roastery) {
    }

    /** data-model §4.1 응답 매핑용 관대한 DTO — 알 수 없는 필드는 무시. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record AliasPayload(List<String> coffeeNameAliases, List<String> roasteryAliases) {
    }
}
