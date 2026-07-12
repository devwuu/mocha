package com.devwuu.mocha.pipeline;

import com.devwuu.mocha.llm.LlmClient;
import com.devwuu.mocha.llm.LlmException;
import com.devwuu.mocha.llm.LlmRequest;
import tools.jackson.databind.ObjectMapper;

/**
 * 파이프라인 [1.5] — 상시 의도 게이트. 모든 수신 텍스트의 의도를 5분류한다
 * (ref: plan.md §1 [1.5], #ADR-24; spec FR-17; data-model.md#4.1; changes/0011 delta.md).
 * <p>확인 대기 유무와 무관하게 모든 텍스트가 통과한다. data-model §4.1 요청({@code message} 원문 +
 * {@code context} 힌트)을 조립해 {@link LlmClient}로 넘기고,
 * {@code record}/{@code revise}/{@code search}/{@code end}/{@code other} 5값 응답을 {@link IntentResult}로 받는다.
 * 추출과 같은 경량 모델(`mocha.llm.model`)을 공용하며 새 설정 키를 두지 않는다(right-sizing).
 * <p>POLICY: 컨텍스트 힌트는 분류 참고만 — 라우팅은 의도가 결정한다(ADR-24). 검색은 명시적 조회 신호가
 * 있을 때만 판정하고, 애매하면 폴백 우선순위(검색 세션 중→search, 대기 있음→revise, 없음→record)에 맡긴다.
 * 게이트 호출/스키마 실패 시 같은 폴백 우선순위로 라우팅하는 몫은 배선 단(ConversationRouter, TΔ3)에서 처리한다
 * (ref: plan §7, delta AC-Δ5). 진짜 감상의 소리 없는 유실이 오진입보다 나쁘다.
 */
public class IntentClassifier {

    private static final String SCHEMA_NAME = "message_intent_gate";

    // structured output 스키마(data-model.md#4.1). strict — intent 단일 필드, 5값 enum만 허용.
    private static final String JSON_SCHEMA = """
            {
              "type": "object",
              "additionalProperties": false,
              "required": ["intent"],
              "properties": {
                "intent": {"type": "string", "enum": ["record", "revise", "search", "end", "other"], "description": "record=새 커피 시음 기록 요청, revise=확인 대기 기록 수정 요청, search=저장된 기록 조회 요청, end=진행 중 검색 세션 종료, other=그 외(잡담·인사·질문 등)."}
              }
            }
            """;

    private static final String SYSTEM_PROMPT = """
            너는 사용자가 커피 봇에 보낸 메시지의 의도를 다섯 갈래로 분류하는 게이트다.
            - "record": 커피를 마신 감상·평가를 새 기록으로 남기려는 요청. 커피/로스터리 이름, 맛 감상, 만족도 표현 등이 담긴다.
            - "revise": 확인 대기 중인 기록의 내용을 고치려는 요청. "로스터리는 ~로 바꿔줘", "평가는 맛있다로" 등 기존 내용의 정정·보완.
            - "search": 이미 저장된 기록을 찾아 달라는 조회 요청. "저번에 마신 ~ 찾아줘", "그때 그 커피 뭐였지" 등. 명시적 조회 신호가 있을 때만 고른다.
            - "end": 진행 중인 검색 세션을 끝내려는 표현. "됐어", "그만 찾아도 돼" 등.
            - "other": 그 외 전부. 인사, 잡담, 봇 사용법 질문, 커피와 무관한 말 등.
            context는 분류의 참고 힌트일 뿐이다 — has_pending=확인 대기 기록 존재, search_session_active=검색 세션 진행 중.
            검색은 명시적 조회 신호가 있을 때만 "search"로 분류한다. 판정이 애매하면 폴백에 맡긴다 —
            search_session_active면 "search", 아니고 has_pending이면 "revise", 둘 다 아니면 "record"로 기울인다.
            입력은 message와 context를 담은 JSON으로 주어진다. 오직 intent 값만 판정한다.
            """;

    private final LlmClient llmClient;
    private final ObjectMapper mapper;

    public IntentClassifier(LlmClient llmClient, ObjectMapper mapper) {
        this.llmClient = llmClient;
        this.mapper = mapper;
    }

    /**
     * 원문 메시지와 컨텍스트 힌트를 의도 게이트 요청으로 조립해 LLM에 넘기고 §4.1 결과를 돌려준다.
     *
     * @param message     원문 그대로의 사용자 메시지.
     * @param contextHint 서버가 주입하는 분류 힌트(확인 대기·검색 세션 상태) — 라우팅 결정자가 아니다(ADR-24).
     * @throws LlmException 호출 실패, 또는 재시도 소진 후에도 스키마 위반이 남을 때(plan §7).
     *                      호출부는 이 예외를 폴백 우선순위 라우팅으로 흡수한다(delta AC-Δ5, TΔ3).
     */
    public IntentResult classify(String message, ContextHint contextHint) {
        String userPrompt = buildUserPrompt(message, contextHint);
        LlmRequest<IntentResult> request = new LlmRequest<>(
                SCHEMA_NAME, JSON_SCHEMA, SYSTEM_PROMPT, userPrompt, IntentResult.class);
        return llmClient.complete(request);
    }

    // data-model.md#4.1 요청 스키마를 그대로 사용자 프롬프트로 직렬화({ "message": 원문, "context": 힌트 }).
    private String buildUserPrompt(String message, ContextHint contextHint) {
        IntentRequest payload = new IntentRequest(message, contextHint);
        try {
            return mapper.writeValueAsString(payload);
        } catch (RuntimeException e) {
            throw new LlmException("의도 게이트 요청 직렬화 실패", e);
        }
    }

    /** data-model.md#4.1 요청 스키마 대응 내부 페이로드. message + context(snake_case 힌트)로 직렬화된다. */
    private record IntentRequest(String message, ContextHint context) {
    }
}
