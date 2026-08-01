package com.devwuu.mocha.agent;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.devwuu.mocha.agent.prompt.TurnPrompt;
import com.devwuu.mocha.agent.tool.ToolCallback;
import com.devwuu.mocha.json.MochaObjectMapper;
import com.openai.core.RequestOptions;
import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseError;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseOutputText;
import com.openai.models.responses.ResponseStatus;
import com.openai.models.responses.ResponseUsage;
import com.openai.models.responses.Tool;
import com.openai.models.responses.ToolChoiceOptions;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
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

    /**
     * {@code send} 시임을 미리 준비한 응답 시퀀스로 대체하는 스텁 — 보낸 요청 params·요청 옵션을 캡처한다.
     * 시계를 받는 생성자는 잔여 예산 파생(ADR-70 ①) 검증용이다.
     */
    static class ScriptedChatClient extends OpenAiChatClient {
        private final Deque<Response> script;
        final List<ResponseCreateParams> sent = new ArrayList<>();
        final List<RequestOptions> sentOptions = new ArrayList<>();
        /** 호출 1건이 소비하는 시간 — SteppingClock과 함께 "늘어지는 모델 호출"을 결정론으로 흉내낸다. */
        Runnable onSend = () -> { };

        ScriptedChatClient(int maxToolCalls, List<Response> script) {
            // 토큰·타임아웃 상한은 운영 기본값 — 상한 무관 테스트가 걸리지 않게 넉넉히 둔다.
            this(maxToolCalls, 100_000, Duration.ofSeconds(60), script);
        }

        ScriptedChatClient(int maxToolCalls, int maxTurnTokens, Duration turnTimeout, List<Response> script) {
            this(maxToolCalls, maxTurnTokens, turnTimeout, script, Clock.systemUTC());
        }

        ScriptedChatClient(int maxToolCalls, int maxTurnTokens, Duration turnTimeout, List<Response> script,
                           Clock clock) {
            // 출력 상한은 운영 기본값(ADR-70 ②) — 값 전달 자체는 buildParams 단언이 본다.
            super(null, "test-model", maxToolCalls, maxTurnTokens, turnTimeout, 4_000,
                    MochaObjectMapper.create(), clock);
            this.script = new ArrayDeque<>(script);
        }

        @Override
        protected Response send(ResponseCreateParams params, RequestOptions options) {
            sent.add(params);
            sentOptions.add(options);
            onSend.run();
            // 마지막 응답은 반복 반환 — 상한 중단 테스트가 "끝없이 tool을 부르는 모델"을 흉내낸다.
            return script.size() > 1 ? script.pop() : script.peek();
        }

        /** 캡처된 요청 타임아웃(= 그 호출 시점의 턴 잔여 예산). */
        Duration requestTimeoutOf(int callIndex) {
            return sentOptions.get(callIndex).getTimeout().request();
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
                response("resp_1", functionCall("call_1", "get_note", "{\"note_id\":7}")),
                response("resp_2", message("와이키키 노트 찾았다멍"))));

        String result = client.runTurn(context("와이키키 보여줘"), List.of(tool));

        assertThat(result).isEqualTo("와이키키 노트 찾았다멍");
        assertThat(receivedArgs).containsExactly("{\"note_id\":7}");

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
                .allSatisfy(t -> assertThat(t.isFunction()).isTrue());
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
                response("resp_loop", functionCall("call_n", "get_note", "{\"note_id\":7}"))));

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
                responseWithUsage("resp_1", 80, 30, functionCall("call_1", "get_note", "{\"note_id\":7}"))));

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
        // 첫 호출은 예산(30s) 안에서 시작하지만 그 호출이 40초를 쓴다 → 이터레이션 경계에서 도달.
        // (changes/0027 TΔ5: 종전 timeout=0 조립은 이제 호출 전 판정에 걸리므로 경계 판정 커버가
        //  사라진다 — 시계를 흘려 "늦게 끝난 호출"로 바꿔 ADR-62 경로를 그대로 검증한다.)
        SteppingClock clock = new SteppingClock();
        ScriptedChatClient client = new ScriptedChatClient(8, 100_000, Duration.ofSeconds(30), List.of(
                response("resp_1", functionCall("call_1", "get_note", "{\"note_id\":7}"))), clock);
        client.onSend = () -> clock.advance(Duration.ofSeconds(40));

        assertThatThrownBy(() -> client.runTurn(context("기록해줘"), List.of(tool)))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("경과 시간 상한");
        assertThat(client.sent).hasSize(1);
        assertThat(executions).hasValue(0);
        assertThat(logs.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                .contains("에이전트 턴 관측")
                .contains("턴 타임아웃 도달"));
    }

    @Test
    @DisplayName("AC-Δ6 (ADR-70 ①): 요청 타임아웃이 turn-timeout 잔여 예산에서 파생된다 — 경과에 따라 줄어든다")
    void derivesRequestTimeoutFromRemainingTurnBudget() {
        SteppingClock clock = new SteppingClock();
        ScriptedChatClient client = new ScriptedChatClient(8, 100_000, Duration.ofSeconds(60), List.of(
                response("resp_1", functionCall("call_1", "get_note", "{\"note_id\":7}")),
                response("resp_2", message("찾았다멍"))), clock);
        // 첫 호출이 15초, tool 실행이 5초를 쓴다 → 두 번째 호출의 잔여는 60 - 20 = 40초.
        client.onSend = () -> clock.advance(Duration.ofSeconds(15));

        String result = client.runTurn(context("와이키키 보여줘"), List.of(getNoteTool(args -> {
            clock.advance(Duration.ofSeconds(5));
            return "{}";
        })));

        assertThat(result).isEqualTo("찾았다멍");
        // 별도 설정 키가 아니라 파생값이다 — 첫 호출은 예산 전체, 이후는 경과분만큼 줄어든다.
        assertThat(client.requestTimeoutOf(0)).isEqualTo(Duration.ofSeconds(60));
        assertThat(client.requestTimeoutOf(1)).isEqualTo(Duration.ofSeconds(40));
    }

    @Test
    @DisplayName("AC-Δ6 (ADR-70 ①): 잔여 예산이 없으면 모델을 호출하지 않고 턴 타임아웃 경로로 수렴한다")
    void skipsModelCallWhenBudgetExhausted() {
        // timeout=0 — 첫 호출 시점에 이미 잔여가 없다. 예산 없는 호출을 내보내지 않는 것이 요점이다.
        ScriptedChatClient client = new ScriptedChatClient(8, 100_000, Duration.ZERO, List.of(
                response("resp_1", message("안녕하다멍"))));

        assertThatThrownBy(() -> client.runTurn(context("안녕"), List.of()))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("경과 시간 상한");
        assertThat(client.sent).isEmpty();
        // 사유는 기존 턴 타임아웃과 동일 축에 남는다 — 새 사유를 만들지 않는다(ADR-62 판정 로직 불변).
        assertThat(logs.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                .contains("에이전트 턴 관측")
                .contains("턴 타임아웃 도달"));
    }

    @Test
    @DisplayName("AC-Δ9 (ADR-70 ①): 요청 타임아웃 절단은 기존 턴 상한 3종과 구분되는 사유로 관측된다")
    void reportsRequestTimeoutTruncationAsDistinctOutcome() {
        SteppingClock clock = new SteppingClock();
        ScriptedChatClient client = new ScriptedChatClient(8, 100_000, Duration.ofSeconds(60),
                List.of(response("resp_1", message("안녕하다멍"))), clock);
        // 호출이 잔여 예산을 전부 소진하고 끊긴다 — OkHttp callTimeout 초과의 실제 전파 형태를
        // 흉내낸다(SDK가 IOException을 감싸 올린다, findings-TΔ4 §1.2).
        client.onSend = () -> {
            clock.advance(Duration.ofSeconds(60));
            throw new AgentException("에이전트 모델 호출 실패", new InterruptedIOException("timeout"));
        };

        assertThatThrownBy(() -> client.runTurn(context("안녕"), List.of()))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("잔여 예산")
                .hasMessageContaining("절단");
        // 턴 타임아웃 도달·누적 토큰 상한과 갈려야 어느 상한을 조정할지 로그만으로 판별된다.
        assertThat(logs.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                .contains("에이전트 턴 관측")
                .contains("요청 타임아웃 절단"));
        assertThat(logs.list).noneSatisfy(event ->
                assertThat(event.getFormattedMessage()).contains("턴 타임아웃 도달"));
    }

    @Test
    @DisplayName("AC-Δ9 (ADR-70 ①): 예산이 남은 채 온 타임아웃은 절단이 아니라 호출 실패로 남는다 — 오분류 금지")
    void doesNotLabelNetworkTimeoutAsBudgetTruncationWhileBudgetRemains() {
        // connect 타임아웃은 잔여 예산에서 파생되지 않고 SDK 기본 1분 고정이다(findings-TΔ4 §1.2) —
        // turn-timeout을 1분 위로 올리면 예산이 남은 채로 SocketTimeoutException이 올라올 수 있다.
        // 그것을 절단으로 찍으면 "turn-timeout 상향"이라는 잘못된 조정 신호가 된다(plan §6).
        SteppingClock clock = new SteppingClock();
        ScriptedChatClient client = new ScriptedChatClient(8, 100_000, Duration.ofMinutes(2),
                List.of(response("resp_1", message("안녕하다멍"))), clock);
        client.onSend = () -> {
            clock.advance(Duration.ofSeconds(60));  // 예산 2분 중 1분만 소진 — 아직 남아 있다
            throw new AgentException("에이전트 모델 호출 실패", new SocketTimeoutException("connect timed out"));
        };

        assertThatThrownBy(() -> client.runTurn(context("안녕"), List.of()))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("모델 호출 실패")
                .hasMessageNotContaining("절단");
        assertThat(logs.list).noneSatisfy(event ->
                assertThat(event.getFormattedMessage()).contains("요청 타임아웃 절단"));
    }

    @Test
    @DisplayName("AC-Δ6 (ADR-70 ①): 잔여 예산이 하한 미만이면 확실히 실패할 호출을 내보내지 않는다")
    void skipsModelCallWhenRemainingBudgetIsBelowFloor() {
        SteppingClock clock = new SteppingClock();
        ScriptedChatClient client = new ScriptedChatClient(8, 100_000, Duration.ofSeconds(5), List.of(
                response("resp_1", functionCall("call_1", "get_note", "{\"note_id\":7}")),
                response("resp_2", message("찾았다멍"))), clock);
        // 첫 호출 4.5초 → 잔여 500ms. 이터레이션 경계 판정(경과 4.5s < 5s)은 통과하지만 호출할 예산은 아니다.
        client.onSend = () -> clock.advance(Duration.ofMillis(4_500));

        assertThatThrownBy(() -> client.runTurn(context("와이키키 보여줘"), List.of(getNoteTool(args -> "{}"))))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("경과 시간 상한");
        assertThat(client.sent).hasSize(1);
        assertThat(logs.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                .contains("에이전트 턴 관측")
                .contains("턴 타임아웃 도달"));
    }

    @Test
    @DisplayName("AC-Δ3 (ADR-62): 상한 미달 시 동작 불변 — 턴 완료 + 관측 로그에 이터레이션별 usage 합산")
    void accumulatesUsageAcrossIterationsWhenUnderCaps() {
        ScriptedChatClient client = new ScriptedChatClient(8, 100_000, Duration.ofSeconds(60), List.of(
                responseWithUsage("resp_1", 80, 30, functionCall("call_1", "get_note", "{\"note_id\":7}")),
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
                response("resp_1", functionCall("call_1", "get_note", "{\"note_id\":7}")),
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
    @DisplayName("buildParams: strict function tool만 장착(내장 도구 없음) + instructions·메시지 매핑 (ADR-44, delta 0029 D-16 ③)")
    void buildsParamsWithStrictFunctionToolsOnly() {
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

        // 내장 도구는 장착하지 않는다 — 보강 주체는 TurnProposalEnricher 하나다(D-16 ③, TΔ24c).
        // 종전에는 여기서 GA web_search + KR 지역화를 단언했다. 모델이 검색을 부를 경로가 없어야
        // "이중 보강·출처 혼선 없음"이 구조로 성립하므로, 이 단언은 그 성질의 가드다.
        assertThat(tools).allSatisfy(t -> assertThat(t.isFunction()).isTrue());
    }

    @Test
    @DisplayName("AC-Δ7 (ADR-70 ②): 요청에 max_output_tokens가 실린다 — 주입된 설정값이 그대로 전달된다")
    void carriesMaxOutputTokensOnRequest() {
        // 상한 없는 루프 모델 호출 금지(ADR-70 POLICY) — 종전에는 요청에 이 필드가 없어 누적 상한(ADR-62 ②)의
        // 사후 판정만 남았다. 설정 키 값이 요청까지 흐르는지를 보려 운영 기본값(4000)과 다른 값을 주입한다.
        OpenAiChatClient client = new OpenAiChatClient(null, "test-model", 8, 100_000,
                Duration.ofSeconds(60), 1_234, MochaObjectMapper.create(), Clock.systemUTC());

        ResponseCreateParams params = client.buildParams("시스템 프롬프트", List.of(), List.of(), null);

        assertThat(params.maxOutputTokens()).contains(1_234L);
    }

    @Test
    @DisplayName("AC-Δ10 (ADR-71): 요청에 store가 명시된다 — 기본값 암묵 의존 없이 보존 유지")
    void carriesExplicitStoreOnRequest() {
        // covers POLICY: LLM 전송 원문의 서버측 보존은 ADR-71 단일 소유, 기본값 의존 금지.
        // 값(true)은 현행 동작과 동일 — 이 task는 동작 변경이 아니라 암묵 의존 제거다. SDK는 미지정 시
        // 필드 자체를 요청에서 빼므로(findings-TΔ4 §1.4) "실렸는가"가 곧 명시 여부다.
        OpenAiChatClient client = new OpenAiChatClient(null, "test-model", 8, 100_000,
                Duration.ofSeconds(60), 4_000, MochaObjectMapper.create(), Clock.systemUTC());

        ResponseCreateParams params = client.buildParams("시스템 프롬프트", List.of(), List.of(), null);

        assertThat(params.store()).contains(true);
    }

    @Test
    @DisplayName("AC-Δ8 (ADR-70 ②): 출력 상한에 걸려 잘린 응답은 최종 텍스트로 반환되지 않고 폴백으로 수렴한다")
    void fallsBackWhenResponseIsTruncatedByOutputCap() {
        // 잘린 텍스트가 **있어도** 내보내지 않는다 — 불완전한 답변보다 재요청 안내가 낫다(plan §7).
        ScriptedChatClient client = new ScriptedChatClient(8, List.of(
                incompleteResponse("resp_1", Response.IncompleteDetails.Reason.MAX_OUTPUT_TOKENS,
                        message("와이키키는 산미가 좋은 커피로 케냐 산지의"))));

        assertThatThrownBy(() -> client.runTurn(context("와이키키 설명해줘"), List.of()))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("출력 상한")
                // 빈 텍스트로 끝난 턴과 뒤섞이지 않는다 — 원인이 다르면 메시지도 갈려야 한다(AC-Δ9).
                .hasMessageNotContaining("최종 텍스트");
        // AC-Δ9: 출력 상한 절단은 기존 턴 상한 3종·요청 타임아웃 절단과 구분되는 사유로 남는다
        // (잦으면 max-output-tokens 상향 근거 — plan §6).
        assertThat(logs.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                .contains("에이전트 턴 관측")
                .contains("outcome=출력 상한 절단"));
        assertThat(logs.list).noneSatisfy(event ->
                assertThat(event.getFormattedMessage()).contains("outcome=완료"));
    }

    @Test
    @DisplayName("AC-Δ9 (ADR-70 ②): 출력 상한이 아닌 미완결 사유는 절단과 갈려 남는다 — 상한 조정 신호 오염 금지")
    void separatesNonOutputCapIncompleteReasonFromOutputCapTruncation() {
        // content_filter는 max-output-tokens와 무관하다 — 같은 사유로 묶으면 "상한을 올려라"는 잘못된
        // 조정 신호가 된다(plan §6, TΔ5의 타임아웃 오분류와 같은 종류의 함정).
        ScriptedChatClient client = new ScriptedChatClient(8, List.of(
                incompleteResponse("resp_1", Response.IncompleteDetails.Reason.CONTENT_FILTER,
                        message("음"))));

        assertThatThrownBy(() -> client.runTurn(context("이상한 요청"), List.of()))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("미완결")
                .hasMessageContaining("content_filter")
                .hasMessageNotContaining("출력 상한");
        assertThat(logs.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                .contains("에이전트 턴 관측")
                .contains("outcome=미완결 응답(사유=content_filter)"));
        assertThat(logs.list).noneSatisfy(event ->
                assertThat(event.getFormattedMessage()).contains("출력 상한 절단"));
    }

    @Test
    @DisplayName("AC-Δ8 (ADR-70 ②): 미완결 응답의 function_call은 실행되지 않는다 — 잘린 인자로 tool 디스패치 금지")
    void neverDispatchesToolCallsFromTruncatedResponse() {
        // 판정이 output 순회 **위**에 있다는 데 전적으로 의존하는 보호다 — 판정이 아래로 내려가면
        // 잘린 JSON 인자로 tool이 돌고도 스위트는 그린으로 남는다(0027 TΔ6 리뷰 반영). 여기서 박는다.
        AtomicInteger executions = new AtomicInteger();
        ScriptedChatClient client = new ScriptedChatClient(8, List.of(
                incompleteResponse("resp_1", Response.IncompleteDetails.Reason.MAX_OUTPUT_TOKENS,
                        functionCall("call_1", "get_note", "{\"note_id\":7"))));

        assertThatThrownBy(() -> client.runTurn(context("와이키키 보여줘"),
                List.of(getNoteTool(args -> {
                    executions.incrementAndGet();
                    return "{}";
                }))))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("출력 상한");
        assertThat(executions).hasValue(0);
    }

    @Test
    @DisplayName("AC-Δ9 (ADR-70 ②): 사유 없는 미완결 응답도 '미상'으로 수렴한다 — 사유 부재가 판정을 흔들지 않는다")
    void convergesWhenIncompleteResponseCarriesNoReason() {
        // reason 없는 INCOMPLETE도 가능하다(findings-TΔ4 §1.5-2) — 사유 미상까지 폴백 문구가 커버해야
        // 한다는 요구를 테스트로 고정한다.
        ScriptedChatClient client = new ScriptedChatClient(8, List.of(
                incompleteResponse("resp_1", null, message("와이키키는"))));

        assertThatThrownBy(() -> client.runTurn(context("와이키키 설명해줘"), List.of()))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("미완결")
                .hasMessageContaining("미상")
                // 사유를 모르는 것과 출력 상한 도달은 다른 조정 신호다.
                .hasMessageNotContaining("출력 상한");
        assertThat(logs.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                .contains("outcome=미완결 응답(사유=미상)"));
    }

    @Test
    @DisplayName("AC-Δ9 (0027 TΔ6 리뷰): status=failed 응답은 완료로 관측되지 않고 error 사유와 함께 폴백한다")
    void reportsFailedResponseAsDistinctOutcomeWithError() {
        // 종전에는 이 응답이 outcome=완료로 찍힌 뒤 "최종 텍스트 없이 끝남"으로 수렴해, 실패한 턴이 완료로
        // 기록되고 원인(error)은 로그 어디에도 남지 않았다 — REVIEW.md 관측 축이 금지하는 형태다.
        ScriptedChatClient client = new ScriptedChatClient(8, List.of(
                failedResponse("resp_1", ResponseError.Code.SERVER_ERROR, "internal error")));

        assertThatThrownBy(() -> client.runTurn(context("안녕"), List.of()))
                .isInstanceOf(AgentException.class)
                .hasMessageContaining("status=failed")
                .hasMessageContaining("server_error")
                // 빈 응답(최종 텍스트 없음)과 원인이 다르므로 메시지도 갈린다.
                .hasMessageNotContaining("최종 텍스트");
        assertThat(logs.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                .contains("에이전트 턴 관측")
                .contains("outcome=응답 실패(status=failed)"));
        assertThat(logs.list).noneSatisfy(event ->
                assertThat(event.getFormattedMessage()).contains("outcome=완료"));
    }

    // 종전 이 자리에 «절단된 응답의 web_search도 tool 시퀀스에 남는다»(0027 TΔ7 리뷰)가 있었다 —
    // 루프가 내장 도구를 장착하지 않게 되며(TΔ24c) 관측 대상이 소멸했다. 절단 사유 구분 자체(AC-Δ9)는
    // 위 abortsIncompleteResponseWithOutputCap 계열이 그대로 가드한다.

    @Test
    @DisplayName("AC-Δ8 (findings-TΔ4 §1.5): status 없는 응답은 실패로 오판되지 않고 정상 완료된다")
    void treatsMissingStatusAsCompleted() {
        // 판정이 "status가 있고 COMPLETED가 아닐 때"인 이유 — SDK 타입이 Optional이라 부재가 성립하고,
        // 부재를 실패로 읽으면 정상 턴이 폴백으로 죽는다. 완료 판정 쪽(status=completed)은 기본 fake가 본다.
        ScriptedChatClient client = new ScriptedChatClient(8, List.of(
                statuslessResponse("resp_1", message("괜찮은 커피다멍"))));

        assertThat(client.runTurn(context("안녕"), List.of())).isEqualTo("괜찮은 커피다멍");
        assertThat(logs.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                .contains("에이전트 턴 관측")
                .contains("outcome=완료"));
    }

    @Test
    @DisplayName("AC-Δ9: 턴 관측 로그에 tool 시퀀스·호출 수가 남는다 (plan §6)")
    void logsTurnObservationWithToolSequence() {
        // 시퀀스에 실리는 것은 function tool뿐이다 — 내장 도구를 장착하지 않으므로(TΔ24c) 그 밖의
        // 출력 항목은 관측 대상이 아니다.
        ScriptedChatClient client = new ScriptedChatClient(8, List.of(
                response("resp_1", functionCall("call_1", "get_note", "{\"note_id\":7}")),
                response("resp_2", message("찾았다멍"))));

        client.runTurn(context("와이키키 보여줘"), List.of(getNoteTool(args -> "{}")));

        assertThat(logs.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                .contains("에이전트 턴 관측")
                .contains("toolSequence=[get_note]")
                .contains("functionCalls=1"));
    }

    @Test
    @DisplayName("ADR-48: 모델 호출 실패는 AgentException으로 수렴한다(폴백 대상)")
    void wrapsModelCallFailureAsAgentException() {
        // client=null인 실 send 경로 — SDK 호출 시도가 RuntimeException으로 실패한다.
        OpenAiChatClient client = new OpenAiChatClient(null, "test-model", 8,
                100_000, Duration.ofSeconds(60), 4_000, MochaObjectMapper.create(), Clock.systemUTC());

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

    /**
     * 테스트에서 시간을 임의로 흘리는 시계 — 잔여 예산 파생·경과 판정을 실제 대기 없이 결정론화한다
     * (FoldingChatMemoryTest.SteppingClock 선례, CLAUDE.md §5.2).
     */
    static class SteppingClock extends Clock {
        private Instant current = Instant.parse("2026-07-30T10:00:00Z");

        void advance(Duration step) {
            current = current.plus(step);
        }

        @Override
        public Instant instant() {
            return current;
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("Asia/Seoul");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }
    }

    private static TurnPrompt context(String userText) {
        return new TurnPrompt("시스템 프롬프트", List.of(TurnPrompt.Message.user(userText)));
    }

    private static ToolCallback getNoteTool(ToolCallback.Executor executor) {
        return new ToolCallback("get_note", "저장된 커피 노트 1건을 조회한다.", """
                {"type":"object","properties":{"note_id":{"type":"integer"}},\
                "required":["note_id"],"additionalProperties":false}""", executor);
    }

    /**
     * 정상 응답 fake — 실 Responses API는 {@code status}를 <b>항상</b> 주고 완결이면 {@code completed}이므로
     * 기본 fake도 그렇게 맞춘다(0027 TΔ7 리뷰 2차, 사용자 확정 2026-07-30). 종전에는 비워 뒀는데, 그러면
     * {@code OpenAiChatClient}의 미완결 판정에서 <b>정상 경로를 통과시키는 쪽</b>이 어느 테스트로도 커버되지
     * 않아 판정 술어가 틀려도(예: {@code status().isPresent()}) 전 케이스 그린이었다 — 프로덕션 턴 100%가
     * 폴백으로 죽는 변조가 통과했다.
     */
    private static Response response(String id, ResponseOutputItem... items) {
        return statuslessResponse(id, items).toBuilder()
                .status(ResponseStatus.COMPLETED)
                .build();
    }

    /**
     * {@code status} 없는 응답 — SDK 타입이 {@code Optional}이라 성립하는 경계다. 이것을 실패로 오판하지
     * 않는 것이 판정의 조건이므로(findings-TΔ4 §1.5) 전용 fake로 남긴다.
     */
    private static Response statuslessResponse(String id, ResponseOutputItem... items) {
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

    /**
     * 미완결 응답 — 출력 상한 절단(AC-Δ8) 조립. {@code reason=null}이면 사유 없는 미완결
     * (findings-TΔ4 §1.5-2가 요구한 경계)이다.
     */
    private static Response incompleteResponse(String id, Response.IncompleteDetails.Reason reason,
                                               ResponseOutputItem... items) {
        Response.IncompleteDetails.Builder details = Response.IncompleteDetails.builder();
        if (reason != null) {
            details.reason(reason);
        }
        return response(id, items).toBuilder()
                .status(ResponseStatus.INCOMPLETE)
                .incompleteDetails(details.build())
                .build();
    }

    /** 서버측 생성 실패 응답 — status=failed + error(0027 TΔ6 리뷰: 완료로 오관측되던 경로). */
    private static Response failedResponse(String id, ResponseError.Code code, String message) {
        return response(id).toBuilder()
                .status(ResponseStatus.FAILED)
                .error(ResponseError.builder().code(code).message(message).build())
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
}
