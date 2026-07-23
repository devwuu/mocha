package com.devwuu.mocha.agent;

import com.devwuu.mocha.agent.prompt.AgentInputMessage;
import com.devwuu.mocha.agent.prompt.AgentTurnInput;
import com.devwuu.mocha.agent.tool.ToolCallback;
import com.openai.client.OpenAIClient;
import com.openai.core.JsonValue;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.WebSearchTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI Responses API 기반 {@link AgentClient} 구현 — 모델 호출↔tool 실행 루프 드라이버
 * (ref: specs/coffee-note-agent/plan.md#ADR-44, changes/0018 findings-TΔ0.md §SDK).
 * <p>루프 계약(TΔ0a 실측): 이터레이션 상태는 {@code previous_response_id} + function_call_output만 싣고,
 * tool 정의는 매 요청 재전송한다. 내장 web_search는 서버측에서 실행 완료된 채 도착하므로
 * 클라이언트 왕복·상한 대상이 아니다(관측 로그만). 종료 = function_call 없는 응답.
 * <p>OpenAI SDK 타입은 이 클래스 안에만 존재한다(plan §4 POLICY, NFR-4).
 */
public class OpenAiAgentClient implements AgentClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiAgentClient.class);

    private final OpenAIClient client;
    private final String model;
    private final int maxToolCalls;
    private final int maxTurnTokens;
    private final Duration turnTimeout;
    private final ObjectMapper mapper;

    /**
     * @param model         에이전트 루프 모델(mocha.agent.model — web_search·다중 tool 호출 품질 필요, ADR-50)
     * @param maxToolCalls  function tool 실행 횟수 상한(mocha.agent.max-tool-calls, ADR-44)
     * @param maxTurnTokens 턴 누적 토큰 상한 — 이터레이션별 usage(in+out) 합산(mocha.agent.max-turn-tokens, ADR-62)
     * @param turnTimeout   턴 경과 시간 상한 — 이터레이션 경계 판정(mocha.agent.turn-timeout, ADR-62)
     */
    public OpenAiAgentClient(OpenAIClient client, String model, int maxToolCalls,
                             int maxTurnTokens, Duration turnTimeout, ObjectMapper mapper) {
        this.client = client;
        this.model = model;
        this.maxToolCalls = maxToolCalls;
        this.maxTurnTokens = maxTurnTokens;
        this.turnTimeout = turnTimeout;
        this.mapper = mapper;
    }

    @Override
    public String runTurn(AgentTurnInput context, List<ToolCallback> tools) {
        long started = System.currentTimeMillis();
        Map<String, ToolCallback> toolsByName = new LinkedHashMap<>();
        tools.forEach(tool -> toolsByName.put(tool.name(), tool));

        List<ResponseInputItem> pendingInput = new ArrayList<>();
        context.messages().forEach(message -> pendingInput.add(toInputItem(message)));
        String previousResponseId = null;
        int executedToolCalls = 0;
        long cumulativeInputTokens = 0;
        long cumulativeOutputTokens = 0;
        List<String> toolSequence = new ArrayList<>();

        while (true) {
            Response response = send(buildParams(context.instructions(), tools, pendingInput, previousResponseId));
            previousResponseId = response.id();
            pendingInput.clear();

            // 누적 usage 합산 — 이터레이션별 in+out을 합쳐 토큰 상한 판정·관측에 쓴다(ADR-62 튜닝 근거).
            if (response.usage().isPresent()) {
                cumulativeInputTokens += response.usage().get().inputTokens();
                cumulativeOutputTokens += response.usage().get().outputTokens();
            }

            List<ResponseFunctionToolCall> calls = new ArrayList<>();
            StringBuilder finalText = new StringBuilder();
            for (ResponseOutputItem item : response.output()) {
                if (item.isWebSearchCall()) {
                    // 서버측에서 이미 실행 완료 — 상한 비대상, 실제 검색 쿼리만 관측(findings-TΔ0 §SDK)
                    toolSequence.add("web_search");
                    log.info("에이전트 web_search: {}", item.asWebSearchCall().action());
                } else if (item.isFunctionCall()) {
                    calls.add(item.asFunctionCall());
                } else if (item.isMessage()) {
                    appendMessageText(item.asMessage(), finalText);
                }
                // reasoning 등 그 외 아이템은 디스패치·응답 텍스트와 무관 — 무시
            }

            if (calls.isEmpty()) {
                logTurnObservation("완료", toolSequence, executedToolCalls, started,
                        cumulativeInputTokens, cumulativeOutputTokens);
                if (finalText.isEmpty()) {
                    throw new AgentException("에이전트 턴이 최종 텍스트 없이 끝남 (response.id=" + response.id() + ")");
                }
                return finalText.toString();
            }

            // POLICY: 턴 상한 3종(tool 호출·누적 토큰·경과 시간) 도달 시 루프 중단 + 폴백 — 상한 없는 루프 금지
            // (ref: specs/coffee-note-agent/plan.md#ADR-44·ADR-62 POLICY)
            // 판정은 턴이 계속될 때만(이터레이션 경계) — 이미 완결된 응답은 위에서 반환됐다(비용은 기지불,
            // 상한의 목적은 폭주 차단이지 완결 턴 폐기가 아니다). 진행 중 HTTP 호출은 중단하지 않는다(ADR-62).
            long cumulativeTokens = cumulativeInputTokens + cumulativeOutputTokens;
            if (cumulativeTokens >= maxTurnTokens) {
                logTurnObservation("누적 토큰 상한 도달", toolSequence, executedToolCalls, started,
                        cumulativeInputTokens, cumulativeOutputTokens);
                throw new AgentException("턴 누적 토큰 상한(" + maxTurnTokens + ") 도달 — 누적 "
                        + cumulativeTokens + ", 턴 중단");
            }
            long elapsedMs = System.currentTimeMillis() - started;
            if (elapsedMs >= turnTimeout.toMillis()) {
                logTurnObservation("턴 타임아웃 도달", toolSequence, executedToolCalls, started,
                        cumulativeInputTokens, cumulativeOutputTokens);
                throw new AgentException("턴 경과 시간 상한(" + turnTimeout.toSeconds() + "s) 도달 — 경과 "
                        + elapsedMs + "ms, 턴 중단");
            }

            for (ResponseFunctionToolCall call : calls) {
                if (executedToolCalls >= maxToolCalls) {
                    logTurnObservation("tool 호출 상한 도달", toolSequence, executedToolCalls, started,
                            cumulativeInputTokens, cumulativeOutputTokens);
                    throw new AgentException("tool 호출 상한(" + maxToolCalls + ") 도달 — 턴 중단");
                }
                executedToolCalls++;
                toolSequence.add(call.name());
                // 결과 짝짓기는 callId(call_...) 기준 — id(fc_...)가 아니다(findings-TΔ0 §SDK).
                pendingInput.add(ResponseInputItem.ofFunctionCallOutput(
                        ResponseInputItem.FunctionCallOutput.builder()
                                .callId(call.callId())
                                .output(dispatch(toolsByName, call))
                                .build()));
            }
        }
    }

    /**
     * tool 호출 1건 디스패치. 실행 오류는 삼키지도 턴을 깨지도 않고 <b>사유를 tool 결과로</b> 돌려준다
     * — 에이전트가 루프 안에서 정정하거나 사용자에게 안내한다(ref: plan.md#ADR-45 POLICY, AC-Δ5).
     */
    private String dispatch(Map<String, ToolCallback> toolsByName, ResponseFunctionToolCall call) {
        ToolCallback tool = toolsByName.get(call.name());
        if (tool == null) {
            log.warn("에이전트가 등록되지 않은 tool 호출: {}", call.name());
            return errorOutput("등록되지 않은 tool: " + call.name());
        }
        try {
            return tool.executor().execute(call.arguments());
        } catch (RuntimeException e) {
            log.warn("tool {} 실행 실패 — 오류 사유를 tool 결과로 반환", call.name(), e);
            return errorOutput(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    private String errorOutput(String reason) {
        return mapper.writeValueAsString(Map.of("error", reason));
    }

    /**
     * SDK 호출 경계 — 테스트는 이 메서드를 override해 결정론적 응답으로 대체한다(CLAUDE.md §5.2,
     * 실 API 스모크는 수동). 호출 실패는 {@link AgentException}으로 수렴한다(ADR-48 폴백 대상).
     */
    protected Response send(ResponseCreateParams params) {
        try {
            return client.responses().create(params);
        } catch (RuntimeException e) {
            throw new AgentException("에이전트 모델 호출 실패", e);
        }
    }

    // 요청 조립 — tool 정의(function 5종 + 내장 web_search)는 매 요청 재전송(findings-TΔ0 §SDK).
    ResponseCreateParams buildParams(String instructions, List<ToolCallback> tools,
                                     List<ResponseInputItem> input, String previousResponseId) {
        ResponseCreateParams.Builder builder = ResponseCreateParams.builder()
                .model(model)
                .instructions(instructions)
                .inputOfResponse(List.copyOf(input));
        tools.forEach(tool -> builder.addTool(toFunctionTool(tool)));
        builder.addTool(webSearchTool());
        if (previousResponseId != null) {
            builder.previousResponseId(previousResponseId);
        }
        return builder.build();
    }

    // strict schema — 형태는 모델이 보장하고, 값 수준 규칙은 서버 검증(TΔ1)이 맡는다(findings-TΔ0 §SDK).
    private FunctionTool toFunctionTool(ToolCallback tool) {
        Map<String, Object> schemaFields;
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = mapper.readValue(tool.parametersSchema(), Map.class);
            schemaFields = parsed;
        } catch (RuntimeException e) {
            throw new AgentException("잘못된 tool 인자 스키마: " + tool.name(), e);
        }
        FunctionTool.Parameters.Builder parameters = FunctionTool.Parameters.builder();
        schemaFields.forEach((key, value) -> parameters.putAdditionalProperty(key, JsonValue.from(value)));
        return FunctionTool.builder()
                .name(tool.name())
                .description(tool.description())
                .parameters(parameters.build())
                .strict(true)
                .build();
    }

    // 내장 web_search는 GA 타입 + KR 지역화 — 영어권 기본 착지 방지(ADR-16 승계, findings-TΔ0 §SDK).
    private static WebSearchTool webSearchTool() {
        return WebSearchTool.builder()
                .type(WebSearchTool.Type.WEB_SEARCH)
                .userLocation(WebSearchTool.UserLocation.builder()
                        .type(WebSearchTool.UserLocation.Type.APPROXIMATE)
                        .country("KR")
                        .timezone("Asia/Seoul")
                        .build())
                .build();
    }

    private static ResponseInputItem toInputItem(AgentInputMessage message) {
        return ResponseInputItem.ofEasyInputMessage(EasyInputMessage.builder()
                .role(message.role() == AgentInputMessage.Role.USER
                        ? EasyInputMessage.Role.USER
                        : EasyInputMessage.Role.ASSISTANT)
                .content(message.content())
                .build());
    }

    private static void appendMessageText(ResponseOutputMessage message, StringBuilder finalText) {
        for (ResponseOutputMessage.Content content : message.content()) {
            if (content.isOutputText()) {
                finalText.append(content.asOutputText().text());
            } else if (content.isRefusal()) {
                // 거절도 모델의 최종 발화 — 사용자에게 그대로 전달할 텍스트로 취급한다.
                finalText.append(content.asRefusal().refusal());
            }
        }
    }

    // AC-Δ9·AC-Δ3: 턴 관측 — tool 시퀀스·호출 수·상한 도달(사유 구분)·누적 usage가 파일 로그에서
    // 확인된다(plan §6 — 상한 기본값 튜닝 근거, ADR-62).
    private void logTurnObservation(String outcome, List<String> toolSequence, int executedToolCalls,
                                    long started, long cumulativeInputTokens, long cumulativeOutputTokens) {
        log.info("에이전트 턴 관측: outcome={} toolSequence={} functionCalls={} elapsedMs={} cumulativeUsage=in:{} out:{}",
                outcome, toolSequence, executedToolCalls, System.currentTimeMillis() - started,
                cumulativeInputTokens, cumulativeOutputTokens);
    }
}
