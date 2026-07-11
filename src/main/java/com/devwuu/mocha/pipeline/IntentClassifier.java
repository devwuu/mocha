package com.devwuu.mocha.pipeline;

import com.devwuu.mocha.llm.LlmClient;
import com.devwuu.mocha.llm.LlmException;
import com.devwuu.mocha.llm.LlmRequest;
import tools.jackson.databind.ObjectMapper;

/**
 * 파이프라인 [1.5] — 입구 의도 게이트. 원문 메시지가 기록 요청인지 그 외인지 2분류한다
 * (ref: plan.md §1 [1.5], #ADR-18; spec FR-17; data-model.md#4.1; changes/0007 delta.md).
 * <p>pending 없음 + 텍스트 분기에서만 호출된다. data-model §4.1 요청({@code message} 원문)을
 * 조립해 {@link LlmClient}로 넘기고, {@code record}/{@code other} 2값 응답을 {@link IntentResult}로 받는다.
 * 추출과 같은 경량 모델(`mocha.llm.model`)을 공용하며 새 설정 키를 두지 않는다(right-sizing).
 * <p>POLICY: 판정이 애매하면 record로 기울인다 — 프롬프트 지침(fail-open의 절반).
 * 게이트 호출/스키마 실패 시에도 record로 간주해 진행하는 나머지 절반은 배선 단(DefaultConfirmationFlow, TΔ3)에서 처리한다
 * (ref: plan §7, delta AC-Δ4). 진짜 감상의 소리 없는 유실이 오진입보다 나쁘다.
 */
public class IntentClassifier {

    private static final String SCHEMA_NAME = "message_intent_gate";

    // structured output 스키마(data-model.md#4.1). strict — intent 단일 필드, record/other 2값 enum만 허용.
    private static final String JSON_SCHEMA = """
            {
              "type": "object",
              "additionalProperties": false,
              "required": ["intent"],
              "properties": {
                "intent": {"type": "string", "enum": ["record", "other"], "description": "record=커피 시음 기록 요청, other=그 외(잡담·인사·질문 등)."}
              }
            }
            """;

    private static final String SYSTEM_PROMPT = """
            너는 사용자가 커피 봇에 보낸 메시지의 의도를 두 갈래로만 분류하는 게이트다.
            - "record": 커피를 마신 감상·평가를 기록으로 남기려는 요청. 커피/로스터리 이름, 맛 감상, 만족도 표현 등이 담긴다.
            - "other": 그 외 전부. 인사, 잡담, 봇 사용법 질문, 커피와 무관한 말 등.
            애매하면 반드시 "record"로 분류한다 — 진짜 감상을 놓치는 것이 잡담을 잘못 들이는 것보다 나쁘다.
            입력은 message를 담은 JSON으로 주어진다. 오직 intent 값만 판정한다.
            """;

    private final LlmClient llmClient;
    private final ObjectMapper mapper;

    public IntentClassifier(LlmClient llmClient, ObjectMapper mapper) {
        this.llmClient = llmClient;
        this.mapper = mapper;
    }

    /**
     * 원문 메시지를 의도 게이트 요청으로 조립해 LLM에 넘기고 §4.1 결과를 돌려준다.
     *
     * @param message 원문 그대로의 사용자 메시지.
     * @throws LlmException 호출 실패, 또는 재시도 소진 후에도 스키마 위반이 남을 때(plan §7).
     *                      호출부는 이 예외를 fail-open(record)으로 흡수한다(delta AC-Δ4, TΔ3).
     */
    public IntentResult classify(String message) {
        String userPrompt = buildUserPrompt(message);
        LlmRequest<IntentResult> request = new LlmRequest<>(
                SCHEMA_NAME, JSON_SCHEMA, SYSTEM_PROMPT, userPrompt, IntentResult.class);
        return llmClient.complete(request);
    }

    // data-model.md#4.1 요청 스키마를 그대로 사용자 프롬프트로 직렬화({ "message": 원문 }).
    private String buildUserPrompt(String message) {
        IntentRequest payload = new IntentRequest(message);
        try {
            return mapper.writeValueAsString(payload);
        } catch (RuntimeException e) {
            throw new LlmException("의도 게이트 요청 직렬화 실패", e);
        }
    }

    /** data-model.md#4.1 요청 스키마 대응 내부 페이로드. message 단일 필드로 직렬화된다. */
    private record IntentRequest(String message) {
    }
}
