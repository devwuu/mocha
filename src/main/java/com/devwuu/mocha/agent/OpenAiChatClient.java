package com.devwuu.mocha.agent;

import com.devwuu.mocha.agent.prompt.TurnPrompt;
import com.devwuu.mocha.agent.tool.ToolCallback;
import com.devwuu.mocha.agent.tool.ToolSupport;
import com.openai.client.OpenAIClient;
import com.openai.core.JsonValue;
import com.openai.core.RequestOptions;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.io.InterruptedIOException;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * OpenAI Responses API 기반 {@link ChatClient} 구현 — 모델 호출↔tool 실행 루프 드라이버
 * (ref: specs/coffee-note-agent/plan.md#ADR-44, changes/0018 findings-TΔ0.md §SDK).
 * <p>루프 계약(TΔ0a 실측): 이터레이션 상태는 {@code previous_response_id} + function_call_output만 싣고,
 * tool 정의는 매 요청 재전송한다. 종료 = function_call 없는 응답.
 * <p><b>이 드라이버는 내장 도구를 장착하지 않는다</b> — 구 판본이 붙이던 web_search는 changes/0029 TΔ24c에서
 * 걷혔다. 보강을 모델 재량으로 둔 것이 조용한 회귀의 원인이었고(delta 0029 D-16 ①·②), 주체가
 * {@code TurnProposalEnricher}(결정론 후처리) 하나로 옮겨졌다 — 둘을 남기면 이중 보강·출처 혼선이 된다.
 * <p>OpenAI SDK 타입은 이 클래스 안에만 존재한다(plan §4 POLICY, NFR-4).
 */
public class OpenAiChatClient implements ChatClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiChatClient.class);

    /**
     * 요청을 내보낼 최소 잔여 예산 — 이보다 적게 남았으면 호출하지 않고 턴 타임아웃으로 수렴한다
     * (ADR-70 ①). 근거: 콜 1회 턴의 관측 최소 소요가 2,869ms(0026 findings-TΔ3 §2 {@code smalltalk-boundary})라
     * 1초 미만 예산으로는 성공 여지가 사실상 없고, 그런 호출은 SDK 재시도(2회)까지 붙어 백오프만 더한다.
     * 하한을 크게 잡으면 응답 가능한 호출을 선제 포기하므로 작게 둔다 — 목적은 확실히 실패할 호출의 차단뿐이다.
     * 별도 설정 키를 만들지 않는다(ADR-70 POLICY — 턴 예산 파생 강제).
     */
    private static final long MIN_REQUEST_BUDGET_MS = 1_000;

    private final OpenAIClient client;
    private final String model;
    private final int maxToolCalls;
    private final int maxTurnTokens;
    private final Duration turnTimeout;
    private final int maxOutputTokens;
    private final ObjectMapper mapper;
    private final Clock clock;

    /**
     * @param model           에이전트 루프 모델(mocha.agent.model — 다중 tool 호출 품질 필요, ADR-50)
     * @param maxToolCalls    function tool 실행 횟수 상한(mocha.agent.max-tool-calls, ADR-44)
     * @param maxTurnTokens   턴 누적 토큰 상한 — 이터레이션별 usage(in+out) 합산(mocha.agent.max-turn-tokens,
     *                        ADR-62)
     * @param turnTimeout     턴 경과 시간 상한 — 이터레이션 경계 판정 + 요청 타임아웃 파생원
     *                        (mocha.agent.turn-timeout, ADR-62·70 ①)
     * @param maxOutputTokens 응답 1건의 출력 토큰 상한 — 요청에 실어 폭주를 발생 전에 자른다
     *                        (mocha.agent.max-output-tokens, ADR-70 ②)
     * @param clock           턴 경과 시간의 시간원(ADR-63 공통 Clock 빈) — 잔여 예산 계산을 테스트에서 결정론화한다
     */
    public OpenAiChatClient(OpenAIClient client, String model, int maxToolCalls,
                            int maxTurnTokens, Duration turnTimeout, int maxOutputTokens,
                            ObjectMapper mapper, Clock clock) {
        this.client = client;
        this.model = model;
        this.maxToolCalls = maxToolCalls;
        this.maxTurnTokens = maxTurnTokens;
        this.turnTimeout = turnTimeout;
        this.maxOutputTokens = maxOutputTokens;
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public String runTurn(TurnPrompt context, List<ToolCallback> tools) {
        long started = clock.millis();
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
            // POLICY: 턴 예산(turn-timeout)의 잔여를 모델 호출마다 요청 타임아웃으로 파생 강제한다 —
            //         타임아웃 미설정(SDK 기본값 의존) 금지, 별도 요청 타임아웃 설정 키 금지
            //         (ref: specs/coffee-note-agent/plan.md#ADR-70 POLICY). 파생이므로 두 값이 어긋날 여지가
            //         구조적으로 없다. 종전에는 상한 판정이 이터레이션 경계에만 있어 진행 중인 단일 호출이
            //         SDK 기본값(10분 × 3시도 ≈ 30분 — changes/0027 findings-TΔ4 §2)까지 늘어졌다.
            long remainingMs = turnTimeout.toMillis() - (clock.millis() - started);
            if (remainingMs < MIN_REQUEST_BUDGET_MS) {
                // 예산이 없거나 성공 여지가 없으면 호출하지 않는다 — 사유는 아래 이터레이션 경계 판정(ADR-62)과 동일하다.
                throw turnTimeoutExceeded(toolSequence, executedToolCalls, started,
                        cumulativeInputTokens, cumulativeOutputTokens);
            }

            Response response;
            try {
                response = send(buildParams(context.instructions(), tools, pendingInput, previousResponseId),
                        RequestOptions.builder().timeout(Duration.ofMillis(remainingMs)).build());
            } catch (AgentException e) {
                // 절단 판정은 예외 타입 + **예산이 실제로 소진됐는지**를 함께 본다. 잔여 예산에서 파생되는 것은
                // Timeout.request뿐이고 connect는 SDK 기본 1분 고정이라(findings-TΔ4 §1.2), 예산이 남은 채로 온
                // 타임아웃은 절단이 아니라 네트워크 실패다 — 그것을 절단으로 찍으면 "turn-timeout을 올려라"는
                // 잘못된 신호가 되어 AC-Δ9의 판별력이 뒤집힌다(turn-timeout > 1분 설정에서 실제로 갈린다).
                if (!isRequestTimeout(e) || clock.millis() - started < turnTimeout.toMillis()) {
                    throw e;
                }
                // AC-Δ9: 요청 레벨 절단은 기존 턴 상한 3종(ADR-62)과 사유가 갈려야 어느 상한을 조정할지
                // 로그만으로 판별된다(plan §6 — 잦으면 turn-timeout 상향 근거).
                logTurnObservation("요청 타임아웃 절단", toolSequence, executedToolCalls, started,
                        cumulativeInputTokens, cumulativeOutputTokens);
                throw new AgentException("모델 호출이 턴 잔여 예산(" + remainingMs
                        + "ms) 안에 끝나지 않아 절단 — 턴 중단", e);
            }
            previousResponseId = response.id();
            pendingInput.clear();

            // 누적 usage 합산 — 이터레이션별 in+out을 합쳐 토큰 상한 판정·관측에 쓴다(ADR-62 튜닝 근거).
            if (response.usage().isPresent()) {
                cumulativeInputTokens += response.usage().get().inputTokens();
                cumulativeOutputTokens += response.usage().get().outputTokens();
            }

            // 종전에는 여기에 내장 web_search 관측 순회가 있었다 — 절단된 응답에서도 검색 사실이 시퀀스에
            // 남게 하려고 판정보다 위에 뒀던 자리다(0027 TΔ7 리뷰 2차). tool을 장착하지 않는 지금은 모델이
            // 그것을 실행할 경로 자체가 없어 도달 불가 코드였고, 검색 관측의 소유자는 보강 단계로 옮겨졌다
            // (OpenAiSearchClient·TurnProposalEnricher의 발동·무결과·실패 로그 — changes/0029 TΔ24c).
            // toolSequence에는 이제 function tool만 실린다.

            // AC-Δ8: 완료되지 않은 응답(잘림·실패·취소)은 최종 텍스트로 내보내지 않는다 — 불완전한 답변보다
            // 폴백 안내가 낫다(ADR-70 ② / plan §7). 판정이 아래 순회(function_call 디스패치·텍스트 수집) 전에
            // 있어 잘린 function_call 인자로 tool을 실행하는 것도 함께 막힌다.
            // status는 Optional이라 **값이 있고 COMPLETED가 아닐 때만** 걸린다 — status 없는 응답을 실패로
            // 오판하지 않는 조건이다(findings-TΔ4 §1.5).
            if (response.status().filter(status -> !ResponseStatus.COMPLETED.equals(status)).isPresent()) {
                throw unfinishedResponse(response, toolSequence, executedToolCalls, started,
                        cumulativeInputTokens, cumulativeOutputTokens);
            }

            List<ResponseFunctionToolCall> calls = new ArrayList<>();
            StringBuilder finalText = new StringBuilder();
            for (ResponseOutputItem item : response.output()) {
                if (item.isFunctionCall()) {
                    calls.add(item.asFunctionCall());
                } else if (item.isMessage()) {
                    appendMessageText(item.asMessage(), finalText);
                }
                // reasoning 등 나머지 출력 항목은 디스패치·응답 텍스트와 무관 — 무시
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
            if (clock.millis() - started >= turnTimeout.toMillis()) {
                throw turnTimeoutExceeded(toolSequence, executedToolCalls, started,
                        cumulativeInputTokens, cumulativeOutputTokens);
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
            return ToolSupport.errorOutput(mapper, "등록되지 않은 tool: " + call.name());
        }
        try {
            return tool.executor().execute(call.arguments());
        } catch (RuntimeException e) {
            log.warn("tool {} 실행 실패 — 오류 사유를 tool 결과로 반환", call.name(), e);
            return ToolSupport.errorOutput(mapper,
                    e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    /**
     * 턴 경과 시간 상한 도달(ADR-62 ③) — 예산 소진 시 호출 전 판정과 이터레이션 경계 판정이 같은 사유·같은
     * 관측으로 수렴하게 한 곳에 둔다.
     */
    private AgentException turnTimeoutExceeded(List<String> toolSequence, int executedToolCalls, long started,
                                               long cumulativeInputTokens, long cumulativeOutputTokens) {
        logTurnObservation("턴 타임아웃 도달", toolSequence, executedToolCalls, started,
                cumulativeInputTokens, cumulativeOutputTokens);
        return new AgentException("턴 경과 시간 상한(" + turnTimeout.toSeconds() + "s) 도달 — 경과 "
                + (clock.millis() - started) + "ms, 턴 중단");
    }

    /**
     * 완료되지 않은 응답의 수렴 — <b>사유를 갈라</b> 관측한다(AC-Δ9). 셋을 구분한다: 출력 상한 절단
     * (= {@code max-output-tokens} 상향 신호) / 그 외 미완결 사유({@code content_filter}·미상) /
     * 응답 실패·취소({@code status=failed|cancelled}, 서버측 생성 실패). 하나로 묶으면 상한과 무관한
     * 실패가 상한 조정 신호를 오염시켜 무엇을 고쳐야 하는지 로그로 판별되지 않는다(plan §6).
     * <p>최종 텍스트 없이 끝난 턴({@code runTurn}의 {@code finalText.isEmpty()})과도 별개다 — 빈 응답과
     * 잘린 응답은 원인이 다르고, 잘린 응답은 텍스트가 <b>있어도</b> 내보내지 않는다.
     */
    private AgentException unfinishedResponse(Response response, List<String> toolSequence,
                                              int executedToolCalls, long started,
                                              long cumulativeInputTokens, long cumulativeOutputTokens) {
        String responseRef = " — 턴 중단 (response.id=" + response.id() + ")";
        if (response.status().filter(ResponseStatus.INCOMPLETE::equals).isPresent()) {
            Optional<Response.IncompleteDetails.Reason> reason = response.incompleteDetails()
                    .flatMap(Response.IncompleteDetails::reason);
            boolean outputCapReached = reason
                    .filter(Response.IncompleteDetails.Reason.MAX_OUTPUT_TOKENS::equals).isPresent();
            String reasonText = reason.map(Response.IncompleteDetails.Reason::asString).orElse("미상");
            logTurnObservation(outputCapReached ? "출력 상한 절단" : "미완결 응답(사유=" + reasonText + ")",
                    toolSequence, executedToolCalls, started, cumulativeInputTokens, cumulativeOutputTokens);
            return new AgentException(outputCapReached
                    ? "응답이 출력 상한(" + maxOutputTokens + ") 도달로 잘림" + responseRef
                    : "응답이 미완결로 도착(사유=" + reasonText + ")" + responseRef);
        }
        // failed·cancelled 등 — 상한과 무관한 서버측 실패다. 종전에는 이 응답이 outcome=완료로 찍힌 뒤
        // "최종 텍스트 없이 끝남"으로 수렴해 실패한 턴이 완료로 관측됐고 error 내용도 남지 않았다
        // (0027 TΔ6 리뷰 반영, 사용자 확정 2026-07-30 — REVIEW.md 관측 축: 같은 폴백의 원인은 로그에서 갈려야 한다).
        String status = response.status().map(ResponseStatus::asString).orElse("미상");
        String error = response.error()
                .map(detail -> detail.code().asString() + ": " + detail.message())
                .orElse("사유 미상");
        logTurnObservation("응답 실패(status=" + status + ")", toolSequence, executedToolCalls, started,
                cumulativeInputTokens, cumulativeOutputTokens);
        return new AgentException("모델 응답이 완료되지 않음(status=" + status + ", error=" + error + ")"
                + responseRef);
    }

    /**
     * 잔여 예산 절단 판별 — OkHttp {@code callTimeout} 초과는 {@link InterruptedIOException}으로 올라와
     * SDK가 {@code OpenAIIoException}으로 감싼다(changes/0027 findings-TΔ4 §1.2). 일반 네트워크·API 실패와
     * 갈라야 상한 사유 구분(AC-Δ9)이 성립하므로 원인 체인에서 그 타입을 찾는다.
     */
    private static boolean isRequestTimeout(Throwable failure) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof InterruptedIOException) {
                return true;
            }
        }
        return false;
    }

    /**
     * SDK 호출 경계 — 테스트는 이 메서드를 override해 결정론적 응답으로 대체한다(CLAUDE.md §5.2,
     * 실 API 스모크는 수동). 호출 실패는 {@link AgentException}으로 수렴한다(ADR-48 폴백 대상).
     * <p>{@code options}는 호출 시점의 턴 잔여 예산을 담은 요청 타임아웃이다(ADR-70 ①) — 요청별 값이
     * 클라이언트 기본값을 덮으므로 SDK 기본 타임아웃에 의존하지 않는다. 시임은 이 메서드 하나로 유지한다.
     */
    protected Response send(ResponseCreateParams params, RequestOptions options) {
        try {
            return client.responses().create(params, options);
        } catch (RuntimeException e) {
            throw new AgentException("에이전트 모델 호출 실패", e);
        }
    }

    // 요청 조립 — tool 정의(function tool만)는 매 요청 재전송(findings-TΔ0 §SDK).
    ResponseCreateParams buildParams(String instructions, List<ToolCallback> tools,
                                     List<ResponseInputItem> input, String previousResponseId) {
        // POLICY: 응답 1건의 출력 토큰 상한을 요청에 싣는다 — 상한 없는 루프 모델 호출 금지
        //         (ref: specs/coffee-note-agent/plan.md#ADR-70 POLICY). 누적 상한(ADR-62 ②)은 응답 도착 후
        //         합산 판정이라 단일 응답 폭주의 비용은 이미 지불된 뒤였다. reasoning 토큰도 이 상한에
        //         포함된다(SDK 문서 — plan §5 max-output-tokens 비고).
        // POLICY: LLM API로 전송되는 사용자 원문의 서버측 보존은 ADR-71이 단일 소유한다 — 요청에 store를
        //         항상 명시하고 SDK·서버 기본값에 의존하지 않는다
        //         (ref: specs/coffee-note-agent/plan.md#ADR-71 POLICY, 루트 CLAUDE.md §5).
        //         true = 보존 유지(= 현행 동작). 이 루프는 이터레이션 상태를 previous_response_id로 잇고
        //         reasoning 연속성까지 서버측 보존에 전적으로 의존하므로(findings-TΔ4 §3), 값을 바꾸는 것은
        //         구조 변경이다 — 바꾸려면 코드가 아니라 ADR-71을 먼저 갱신한다.
        //         SDK는 미지정 시 요청 본문에서 필드를 아예 빼므로(findings-TΔ4 §1.4) 명시가 곧 의존 제거다.
        ResponseCreateParams.Builder builder = ResponseCreateParams.builder()
                .model(model)
                .instructions(instructions)
                .maxOutputTokens(maxOutputTokens)
                .store(true)
                .inputOfResponse(List.copyOf(input));
        // POLICY: 이 루프에 내장 도구를 장착하지 않는다 — 검색 보강의 주체는 결정론 후처리 하나뿐이다
        //         (ref: specs/coffee-note-agent/changes/0029-app-interface/delta.md#D-16 ③, TΔ24c).
        tools.forEach(tool -> builder.addTool(toFunctionTool(tool)));
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

    private static ResponseInputItem toInputItem(TurnPrompt.Message message) {
        return ResponseInputItem.ofEasyInputMessage(EasyInputMessage.builder()
                .role(message.role() == TurnPrompt.Message.Role.USER
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
                outcome, toolSequence, executedToolCalls, clock.millis() - started,
                cumulativeInputTokens, cumulativeOutputTokens);
    }
}
