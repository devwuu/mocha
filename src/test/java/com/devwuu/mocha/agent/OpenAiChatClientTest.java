package com.devwuu.mocha.agent;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.devwuu.mocha.agent.prompt.TurnPrompt;
import com.devwuu.mocha.agent.tool.ToolCallback;
import com.devwuu.mocha.json.MochaObjectMapper;
import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseFunctionWebSearch;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseOutputText;
import com.openai.models.responses.ResponseUsage;
import com.openai.models.responses.Tool;
import com.openai.models.responses.ToolChoiceOptions;
import com.openai.models.responses.WebSearchTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OpenAiChatClient 루프 드라이버의 계약 검증 — fake 모델 응답으로 루프 왕복·상한 중단·tool 오류 전달을
 * SDK 호출 없이 결정론적으로 확인한다 (ref: changes/0018 tasks.md TΔ2, plan.md#ADR-44·45,
 * findings-TΔ0.md §SDK, CLAUDE.md §5.2 — 실 API 스모크는 수동 AgentLoopProbeSmokeTest).
 */
class OpenAiChatClientTest {

    /** {@code send} 시임을 미리 준비한 응답 시퀀스로 대체하는 스텁 — 보낸 요청 params를 캡처한다. */
    static class ScriptedChatClient extends OpenAiChatClient {
        private final Deque<Response> script;
        final List<ResponseCreateParams> sent = new ArrayList<>();

        ScriptedChatClient(int maxToolCalls, List<Response> script) {
            // 토큰·타임아웃 상한은 운영 기본값 — 상한 무관 테스트가 걸리지 않게 넉넉히 둔다.
            this(maxToolCalls, 100_000, Duration.ofSeconds(60), script);
        }

        ScriptedChatClient(int maxToolCalls, int maxTurnTokens, Duration turnTimeout, List<Response> script) {
            super(null, "test-model", maxToolCalls, maxTurnTokens, turnTimeout, MochaObjectMapper.create());
            this.script = new ArrayDeque<>(script);
        }

        @Override
        protected Response send(ResponseCreateParams params) {
            sent.add(params);
            // 마지막 응답은 반복 반환 — 상한 중단 테스트가 "끝없이 tool을 부르는 모델"을 흉내낸다.
            return script.size() > 1 ? script.pop() : script.peek();
        }
    }

    private ListAppender<ILoggingEvent> logs;
    private Logger driverLogger;

    @BeforeEach
    void attachLogAppender() {
        driverLogger = (Logger) LoggerFactory.getLogger(OpenAiChatClient.class);
        logs = new ListAppender<>();
        logs.start();
        driverLogger.addAppender(logs);
    }

    @AfterEach
    void detachLogAppender() {
        driverLogger.detachAppender(logs);
    }

    @Test
    @DisplayName("루프 왕복: tool 실행 결과가 callId로 짝지어 다음 요청에 실리고 최종 텍스트를 반환한다 (findings-TΔ0 §SDK)")
    void roundTripsToolCallAndReturnsFinalText() {
        List<String> receivedArgs = new ArrayList<>();
        ToolCallback tool = getNoteTool(args -> {
            receivedArgs.add(args);
            return "{\"coffee_name\":\"와이키키\"}";
        });
        ScriptedChatClient client = new ScriptedChatClient(8, List.of(
                response("resp_1", functionCall("call_1", "get_note", "{\"slug\":\"waikiki\"}")),
                response("resp_2", message("와이키키 노트 찾았다멍"))));

        String result = client.runTurn(context("와이키키 보여줘"), List.of(tool));

        assertThat(result).isEqualTo("와이키키 노트 찾았다멍");
        assertThat(receivedArgs).containsExactly("{\"slug\":\"waikiki\"}");

        // 두 번째 요청 = previousResponseId + function_call_output(callId 짝)만, tool 정의는 재전송.
        ResponseCreateParams second = client.sent.get(1);
        assertThat(second.previousResponseId()).contains("resp_1");
        List<ResponseInputItem> input = second.input().orElseThrow().asResponse();
        assertThat(input).hasSize(1);
        assertThat(input.get(0).isFunctionCallOutput()).isTrue();
        assertThat(input.get(0).asFunctionCallOutput().callId()).isEqualTo("call_1");
        assertThat(input.get(0).asFunctionCallOutput().output().asString())
                .isEqualTo("{\"coffee_name\":\"와이키키\"}");
        assertThat(second.tools().orElseThrow())
                .anySatisfy(t -> assertThat(t.isFunction()).isTrue())
                .anySatisfy(t -> assertThat(t.isWebSearch()).isTrue());
    }

    @Test
    @DisplayName("ADR-44 POLICY: tool 호출 상한 도달 시 AgentException으로 턴을 중단한다 — 상한 없는 루프 금지")
    void abortsWhenToolCallCapReached() {
        AtomicInteger executions = new AtomicInteger();
        ToolCallback tool = getNoteTool(args -> {
            executions.incrementAndGet();
            return "{}";
        });
        ScriptedChatClient client = new ScriptedChatClient(2, List.of(
                response("resp_loop", functionCall("call_n", "get_note", "{\"slug\":\"x\"}"))));

        assertThatThrownBy(() -> client.runTurn(context("계속 조회해"), List.of(tool)))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("상한");
        // 상한(2)까지만 실행되고 초과분은 실행되지 않는다.
        assertThat(executions).hasValue(2);
        // AC-Δ9: 상한 도달이 관측 로그에서 구분된다.
        assertThat(logs.list).anySatisfy(event ->
                assertThat(event.getFormattedMessage()).contains("에이전트 턴 관측").contains("상한 도달"));
    }

    @Test
    @DisplayName("AC-Δ3 (ADR-62): 누적 토큰 상한 도달 시 AgentException 폴백 — tool 미실행·사유 구분 로그")
    void abortsWhenCumulativeTokenCapReached() {
        AtomicInteger executions = new AtomicInteger();
        ToolCallback tool = getNoteTool(args -> {
            executions.incrementAndGet();
            return "{}";
        });
        // 첫 이터레이션 usage(80+30=110)가 상한(100)을 넘고 턴이 계속되려 한다 → 이터레이션 경계에서 중단.
        ScriptedChatClient client = new ScriptedChatClient(8, 100, Duration.ofSeconds(60), List.of(
                responseWithUsage("resp_1", 80, 30, functionCall("call_1", "get_note", "{\"slug\":\"x\"}"))));

        assertThatThrownBy(() -> client.runTurn(context("기록해줘"), List.of(tool)))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("누적 토큰 상한");
        // 상한 초과 판정은 tool 실행 전 — 초과분 tool은 실행되지 않는다.
        assertThat(executions).hasValue(0);
        assertThat(logs.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                .contains("에이전트 턴 관측")
                .contains("누적 토큰 상한 도달")
                .contains("cumulativeUsage=in:80 out:30"));
    }

    @Test
    @DisplayName("AC-Δ3 (ADR-62): 턴 경과 시간 상한 도달 시 AgentException 폴백 — 이터레이션 경계 판정·사유 구분 로그")
    void abortsWhenTurnTimeoutReached() {
        AtomicInteger executions = new AtomicInteger();
        ToolCallback tool = getNoteTool(args -> {
            executions.incrementAndGet();
            return "{}";
        });
        // timeout=0 — 첫 이터레이션 경계에서 즉시 도달(실제 대기 없는 결정론 판정).
        ScriptedChatClient client = new ScriptedChatClient(8, 100_000, Duration.ZERO, List.of(
                response("resp_1", functionCall("call_1", "get_note", "{\"slug\":\"x\"}"))));

        assertThatThrownBy(() -> client.runTurn(context("기록해줘"), List.of(tool)))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("경과 시간 상한");
        assertThat(executions).hasValue(0);
        assertThat(logs.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                .contains("에이전트 턴 관측")
                .contains("턴 타임아웃 도달"));
    }

    @Test
    @DisplayName("AC-Δ3 (ADR-62): 상한 미달 시 동작 불변 — 턴 완료 + 관측 로그에 이터레이션별 usage 합산")
    void accumulatesUsageAcrossIterationsWhenUnderCaps() {
        ScriptedChatClient client = new ScriptedChatClient(8, 100_000, Duration.ofSeconds(60), List.of(
                responseWithUsage("resp_1", 80, 30, functionCall("call_1", "get_note", "{\"slug\":\"x\"}")),
                responseWithUsage("resp_2", 120, 50, message("찾았다멍"))));

        String result = client.runTurn(context("와이키키 보여줘"), List.of(getNoteTool(args -> "{}")));

        assertThat(result).isEqualTo("찾았다멍");
        // 누적 usage(in+out 이터레이션 합산)가 관측 로그에 남는다 — 상한 기본값 튜닝 근거(plan §6).
        assertThat(logs.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                .contains("에이전트 턴 관측")
                .contains("outcome=완료")
                .contains("cumulativeUsage=in:200 out:80"));
    }

    @Test
    @DisplayName("ADR-45: tool 실행 오류는 사유가 tool 결과로 모델에 돌아가고 루프는 계속된다 — 조용한 드롭 금지")
    void deliversToolErrorAsToolResultAndContinues() {
        ToolCallback tool = getNoteTool(args -> {
            throw new IllegalArgumentException("rating은 4범주만 허용");
        });
        ScriptedChatClient client = new ScriptedChatClient(8, List.of(
                response("resp_1", functionCall("call_1", "get_note", "{\"slug\":\"x\"}")),
                response("resp_2", message("rating을 다시 알려달라멍"))));

        String result = client.runTurn(context("기록해줘"), List.of(tool));

        // 오류가 턴을 깨지 않고 최종 텍스트까지 도달한다(루프 내 정정 재료).
        assertThat(result).isEqualTo("rating을 다시 알려달라멍");
        String delivered = client.sent.get(1).input().orElseThrow().asResponse()
                .get(0).asFunctionCallOutput().output().asString();
        assertThat(delivered).contains("error").contains("rating은 4범주만 허용");
    }

    @Test
    @DisplayName("ADR-45: 등록되지 않은 tool 호출도 오류 결과로 돌아간다")
    void deliversUnknownToolAsError() {
        ScriptedChatClient client = new ScriptedChatClient(8, List.of(
                response("resp_1", functionCall("call_1", "save_note", "{}")),
                response("resp_2", message("그건 못 한다멍"))));

        String result = client.runTurn(context("바로 저장해"), List.of(getNoteTool(args -> "{}")));

        assertThat(result).isEqualTo("그건 못 한다멍");
        String delivered = client.sent.get(1).input().orElseThrow().asResponse()
                .get(0).asFunctionCallOutput().output().asString();
        assertThat(delivered).contains("error").contains("save_note");
    }

    @Test
    @DisplayName("buildParams: strict function tool + 내장 web_search(GA·KR 지역화) + instructions·메시지 매핑 (ADR-44, findings-TΔ0 §SDK)")
    void buildsParamsWithStrictToolsAndGaWebSearch() {
        ScriptedChatClient client = new ScriptedChatClient(8, List.of(
                response("resp_1", message("안녕하다멍"))));

        client.runTurn(new TurnPrompt("모카 시스템 프롬프트",
                        List.of(TurnPrompt.Message.mocha("이전 답"), TurnPrompt.Message.user("안녕"))),
                List.of(getNoteTool(args -> "{}")));

        ResponseCreateParams first = client.sent.get(0);
        assertThat(first.instructions()).contains("모카 시스템 프롬프트");
        assertThat(first.previousResponseId()).isEmpty();

        // 트랜스크립트 메시지가 role 그대로 매핑된다(ADR-46 재구성 계약).
        List<ResponseInputItem> input = first.input().orElseThrow().asResponse();
        assertThat(input).hasSize(2);
        assertThat(input.get(0).asEasyInputMessage().role()).isEqualTo(com.openai.models.responses.EasyInputMessage.Role.ASSISTANT);
        assertThat(input.get(1).asEasyInputMessage().role()).isEqualTo(com.openai.models.responses.EasyInputMessage.Role.USER);
        assertThat(input.get(1).asEasyInputMessage().content().asTextInput()).isEqualTo("안녕");

        List<Tool> tools = first.tools().orElseThrow();
        FunctionTool function = tools.stream().filter(Tool::isFunction).map(Tool::asFunction)
                .findFirst().orElseThrow();
        assertThat(function.name()).isEqualTo("get_note");
        assertThat(function.strict()).contains(true);
        assertThat(function.parameters().orElseThrow()._additionalProperties())
                .containsKeys("type", "properties", "required", "additionalProperties");

        // GA web_search(구 web_search_preview 아님) + KR 지역화(ADR-16 승계).
        WebSearchTool webSearch = tools.stream().filter(Tool::isWebSearch).map(Tool::asWebSearch)
                .findFirst().orElseThrow();
        assertThat(webSearch.type()).isEqualTo(WebSearchTool.Type.WEB_SEARCH);
        assertThat(webSearch.userLocation().orElseThrow().country()).contains("KR");
        assertThat(webSearch.userLocation().orElseThrow().timezone()).contains("Asia/Seoul");
    }

    @Test
    @DisplayName("AC-Δ9: 턴 관측 로그에 tool 시퀀스(web_search 포함)·호출 수가 남는다 (plan §6)")
    void logsTurnObservationWithToolSequence() {
        ScriptedChatClient client = new ScriptedChatClient(8, List.of(
                response("resp_1", functionCall("call_1", "get_note", "{\"slug\":\"x\"}")),
                response("resp_2", webSearchCall("ws_1"), message("찾았다멍"))));

        client.runTurn(context("와이키키 공식 페이지 찾아줘"), List.of(getNoteTool(args -> "{}")));

        assertThat(logs.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                .contains("에이전트 턴 관측")
                .contains("get_note")
                .contains("web_search")
                .contains("functionCalls=1"));
    }

    @Test
    @DisplayName("ADR-48: 모델 호출 실패는 AgentException으로 수렴한다(폴백 대상)")
    void wrapsModelCallFailureAsAgentException() {
        // client=null인 실 send 경로 — SDK 호출 시도가 RuntimeException으로 실패한다.
        OpenAiChatClient client = new OpenAiChatClient(null, "test-model", 8,
                100_000, Duration.ofSeconds(60), MochaObjectMapper.create());

        assertThatThrownBy(() -> client.runTurn(context("안녕"), List.of()))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("모델 호출 실패");
    }

    @Test
    @DisplayName("ADR-48: 최종 텍스트 없이 끝난 턴은 AgentException으로 수렴한다(폴백 대상)")
    void failsWhenTurnEndsWithoutFinalText() {
        ScriptedChatClient client = new ScriptedChatClient(8, List.of(response("resp_1")));

        assertThatThrownBy(() -> client.runTurn(context("안녕"), List.of()))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("최종 텍스트");
    }

    // ---- fake 조립 헬퍼 ----

    private static TurnPrompt context(String userText) {
        return new TurnPrompt("시스템 프롬프트", List.of(TurnPrompt.Message.user(userText)));
    }

    private static ToolCallback getNoteTool(ToolCallback.Executor executor) {
        return new ToolCallback("get_note", "저장된 커피 노트 1건을 조회한다.", """
                {"type":"object","properties":{"slug":{"type":"string"}},\
                "required":["slug"],"additionalProperties":false}""", executor);
    }

    private static Response response(String id, ResponseOutputItem... items) {
        return Response.builder()
                .id(id)
                .createdAt(0.0)
                .error(Optional.empty())
                .incompleteDetails(Optional.empty())
                .instructions("시스템 프롬프트")
                .metadata(Optional.empty())
                .model("test-model")
                .output(List.of(items))
                .parallelToolCalls(false)
                .temperature(Optional.empty())
                .toolChoice(ToolChoiceOptions.AUTO)
                .tools(List.of())
                .topP(Optional.empty())
                .build();
    }

    private static Response responseWithUsage(String id, long inputTokens, long outputTokens,
                                              ResponseOutputItem... items) {
        return response(id, items).toBuilder()
                .usage(ResponseUsage.builder()
                        .inputTokens(inputTokens)
                        .inputTokensDetails(ResponseUsage.InputTokensDetails.builder()
                                .cachedTokens(0)
                                .cacheWriteTokens(0)
                                .build())
                        .outputTokens(outputTokens)
                        .outputTokensDetails(ResponseUsage.OutputTokensDetails.builder()
                                .reasoningTokens(0)
                                .build())
                        .totalTokens(inputTokens + outputTokens)
                        .build())
                .build();
    }

    private static ResponseOutputItem functionCall(String callId, String name, String arguments) {
        return ResponseOutputItem.ofFunctionCall(ResponseFunctionToolCall.builder()
                .callId(callId)
                .name(name)
                .arguments(arguments)
                .build());
    }

    private static ResponseOutputItem message(String text) {
        return ResponseOutputItem.ofMessage(ResponseOutputMessage.builder()
                .id("msg_1")
                .addContent(ResponseOutputText.builder()
                        .text(text)
                        .annotations(List.of())
                        .build())
                .status(ResponseOutputMessage.Status.COMPLETED)
                .build());
    }

    private static ResponseOutputItem webSearchCall(String id) {
        return ResponseOutputItem.ofWebSearchCall(ResponseFunctionWebSearch.builder()
                .id(id)
                .action(ResponseFunctionWebSearch.Action.Search.builder()
                        .addQuery("모모스커피 와이키키")
                        .build())
                .status(ResponseFunctionWebSearch.Status.COMPLETED)
                .build());
    }
}
