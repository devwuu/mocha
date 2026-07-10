package com.devwuu.mocha.llm;

import com.openai.client.OpenAIClient;
import com.openai.core.JsonValue;
import com.openai.models.ResponseFormatJsonSchema;
import com.openai.models.chat.completions.ChatCompletion;
import com.openai.models.chat.completions.ChatCompletionCreateParams;
import tools.jackson.databind.ObjectMapper;

import java.util.Map;

/**
 * OpenAI Chat Completions structured output 기반 {@link LlmClient} 구현 (ref: plan.md#ADR-5, #ADR-6).
 * <p>OpenAI SDK 타입은 이 클래스 안에만 존재한다(plan §4 POLICY, NFR-4). 응답은 도메인 매퍼
 * ({@code MochaObjectMapper})로 역직렬화하며, rating 4범주 위반 등은 역직렬화 예외로 드러나
 * {@link #complete} 재시도 경로로 흡수된다(V-1).
 */
public class OpenAiLlmClient implements LlmClient {

    private final OpenAIClient client;
    private final String model;
    private final int maxRetries;
    private final ObjectMapper mapper;

    /**
     * @param maxRetries 스키마/도메인 위반 시 재추출 횟수(mocha.llm.max-retries). 총 시도 = 1 + maxRetries.
     *                   0이면 재시도 없이 첫 위반에서 실패(plan §7, V-1은 기본값 1로 "1회 재시도").
     */
    public OpenAiLlmClient(OpenAIClient client, String model, int maxRetries, ObjectMapper mapper) {
        this.client = client;
        this.model = model;
        this.maxRetries = maxRetries;
        this.mapper = mapper;
    }

    @Override
    public <T> T complete(LlmRequest<T> request) {
        LlmException lastViolation = null;
        // 최초 1회 + maxRetries회. 호출 실패(call 예외)는 재시도 대상이 아니라 즉시 전파된다(plan §7).
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            String json = call(request);
            try {
                return mapper.readValue(json, request.responseType());
            } catch (RuntimeException violation) {
                // V-1: 스키마·도메인(rating 4범주 등) 위반 → 재추출 대상.
                lastViolation = new LlmException("구조화 응답 스키마 위반: " + request.schemaName(), violation);
            }
        }
        throw lastViolation;
    }

    /**
     * SDK 호출 경계 — 벤더에 요청을 보내 응답 본문(JSON 문자열)을 돌려준다.
     * <p>테스트는 이 메서드를 override해 결정론적 응답으로 대체한다(실 API 스모크는 수동, CLAUDE.md §5.2).
     * 호출 실패/빈 응답은 재시도 대상이 아닌 하드 실패로 {@link LlmException}을 던진다(plan §7).
     */
    protected String call(LlmRequest<?> request) {
        try {
            ChatCompletionCreateParams.Builder params = ChatCompletionCreateParams.builder()
                    .model(model)
                    .responseFormat(jsonSchemaFormat(request));
            if (request.systemPrompt() != null) {
                params.addSystemMessage(request.systemPrompt());
            }
            params.addUserMessage(request.userPrompt());

            ChatCompletion completion = client.chat().completions().create(params.build());
            return completion.choices().stream()
                    .findFirst()
                    .flatMap(choice -> choice.message().content())
                    .orElseThrow(() -> new LlmException("LLM 응답이 비어 있음"));
        } catch (LlmException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new LlmException("LLM 호출 실패", e);
        }
    }

    private ResponseFormatJsonSchema jsonSchemaFormat(LlmRequest<?> request) {
        return ResponseFormatJsonSchema.builder()
                .jsonSchema(ResponseFormatJsonSchema.JsonSchema.builder()
                        .name(request.schemaName())
                        .strict(true)
                        .schema(toSdkSchema(request.jsonSchema()))
                        .build())
                .build();
    }

    // JSON schema 문자열 → SDK Schema. 최상위 키를 그대로 additionalProperties로 옮긴다.
    private ResponseFormatJsonSchema.JsonSchema.Schema toSdkSchema(String jsonSchema) {
        Map<String, Object> fields;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = mapper.readValue(jsonSchema, Map.class);
            fields = parsed;
        } catch (RuntimeException e) {
            throw new LlmException("잘못된 JSON schema", e);
        }
        ResponseFormatJsonSchema.JsonSchema.Schema.Builder schema =
                ResponseFormatJsonSchema.JsonSchema.Schema.builder();
        fields.forEach((key, value) -> schema.putAdditionalProperty(key, JsonValue.from(value)));
        return schema.build();
    }
}
