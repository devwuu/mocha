package com.devwuu.mocha.smoke;

import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import com.openai.core.JsonValue;
import com.openai.models.responses.FunctionTool;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFunctionToolCall;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.WebSearchTool;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * TΔ0a(changes/0018) — openai-java SDK Responses API 에이전트 루프 실측 프로브(수동, 비용 발생).
 * <p>확인 대상 (ref: changes/0018-agent-rearchitecture/tasks.md TΔ0a, plan ADR-44·50):
 * ① function tool 정의(strict schema)·tool call 루프 왕복(요청·응답 타입, call_id 규약)
 * ② 내장 web_search(GA {@code WebSearchTool})와 function tool 병용
 * ③ strict schema 인자 검증 동작
 * ④ 모델별 web_search 가용성·다중 tool 호출 품질(경량 vs gpt-4o급) — {@code mocha.agent.model} 초기값 결정
 * <p>단언하지 않는다 — 판정은 관측(출력)과 findings-TΔ0.md §SDK로 한다(CLAUDE.md §5.3).
 * 기본 test 제외(@Tag("openai")), 실행은 온디맨드 Test 태스크로만.
 */
@Tag("openai")
class AgentLoopProbeSmokeTest {

    /** 후보 모델 — 모델 목록 프로브 후 확정. -Dmocha.probe.models로 재정의 가능. */
    private static final String DEFAULT_MODELS = "gpt-4o-mini,gpt-4o";

    /** 무과금 — 계정에서 실제 쓸 수 있는 모델 id를 나열해 후보(경량 vs gpt-4o급)를 확정한다. */
    @Test
    void printsAvailableModels() throws Exception {
        String apiKey = resolveApiKey();
        assumeTrue(apiKey != null && !apiKey.isBlank(), "OPENAI_API_KEY 미설정 — 스모크 스킵");

        OpenAIClient client = OpenAIOkHttpClient.builder().apiKey(apiKey).build();
        System.out.println("=== AVAILABLE MODELS (gpt/o-계열만) ===");
        client.models().list().autoPager().stream()
                .map(m -> m.id())
                .filter(id -> id.startsWith("gpt") || id.startsWith("o"))
                .sorted()
                .forEach(id -> System.out.println("  " + id));
        System.out.println("=== END MODELS ===");
    }

    /**
     * 에이전트 루프 왕복 — 모델별 1콜 체인: get_note(function tool) 호출 → 결과 반환 →
     * web_search(내장) → 최종 텍스트. previous_response_id로 루프 내 상태를 서버에 위임한다.
     */
    @Test
    void runsAgentLoopProbe() throws Exception {
        String apiKey = resolveApiKey();
        assumeTrue(apiKey != null && !apiKey.isBlank(), "OPENAI_API_KEY 미설정 — 스모크 스킵");

        OpenAIClient client = OpenAIOkHttpClient.builder().apiKey(apiKey).build();
        String[] models = System.getProperty("mocha.probe.models", DEFAULT_MODELS).split(",");
        for (String model : models) {
            probeAgentLoop(client, model.strip());
        }
    }

    private void probeAgentLoop(OpenAIClient client, String model) {
        System.out.println();
        System.out.println("########## AGENT LOOP PROBE: model=" + model + " ##########");
        long started = System.currentTimeMillis();
        try {
            // 턴 시작 입력 — 이후 이터레이션은 previous_response_id + function_call_output만 보낸다.
            List<ResponseInputItem> pendingInput = new ArrayList<>();
            pendingInput.add(ResponseInputItem.ofEasyInputMessage(
                    com.openai.models.responses.EasyInputMessage.builder()
                            .role(com.openai.models.responses.EasyInputMessage.Role.USER)
                            .content("'와이키키' 노트를 get_note로 조회하고, 그 노트의 로스터리 공식 웹사이트 주소를 "
                                    + "웹 검색으로 찾아서 한 줄로 알려줘.")
                            .build()));
            String previousResponseId = null;

            for (int turn = 1; turn <= 6; turn++) {
                ResponseCreateParams.Builder builder = ResponseCreateParams.builder()
                        .model(model)
                        .instructions("너는 커피 노트 도우미다. 노트 조회는 get_note tool, 웹 정보는 web_search로 얻는다. "
                                + "추측하지 말고 tool 결과에 근거해 한국어로 답한다.")
                        .addTool(getNoteTool())
                        .addTool(WebSearchTool.builder()
                                .type(WebSearchTool.Type.WEB_SEARCH)
                                .userLocation(WebSearchTool.UserLocation.builder()
                                        .type(JsonValue.from("approximate"))
                                        .country("KR")
                                        .timezone("Asia/Seoul")
                                        .build())
                                .build())
                        .inputOfResponse(pendingInput);
                if (previousResponseId != null) {
                    builder.previousResponseId(previousResponseId);
                }

                Response response = client.responses().create(builder.build());
                previousResponseId = response.id();
                pendingInput = new ArrayList<>();

                System.out.println("--- turn " + turn + " response.id=" + response.id()
                        + " status=" + response.status().map(Object::toString).orElse("?")
                        + " usage=" + response.usage().map(u -> "in:" + u.inputTokens() + " out:" + u.outputTokens()).orElse("?"));

                boolean hasFunctionCall = false;
                for (ResponseOutputItem item : response.output()) {
                    if (item.isReasoning()) {
                        System.out.println("    [reasoning]");
                    } else if (item.isWebSearchCall()) {
                        System.out.println("    [web_search_call] id=" + item.asWebSearchCall().id()
                                + " status=" + item.asWebSearchCall().status()
                                + " action=" + item.asWebSearchCall().action());
                    } else if (item.isFunctionCall()) {
                        hasFunctionCall = true;
                        ResponseFunctionToolCall call = item.asFunctionCall();
                        System.out.println("    [function_call] name=" + call.name()
                                + " callId=" + call.callId()
                                + " id=" + call.id().orElse("(none)")
                                + " arguments=" + call.arguments());
                        // 가짜 노트 페이로드 — 루프 왕복 계약(call_id 짝) 자체가 관측 대상.
                        pendingInput.add(ResponseInputItem.ofFunctionCallOutput(
                                ResponseInputItem.FunctionCallOutput.builder()
                                        .callId(call.callId())
                                        .output("{\"slug\":\"waikiki\",\"coffee_name\":\"와이키키\","
                                                + "\"roastery\":\"모모스커피\",\"origin\":\"블렌드\","
                                                + "\"aliases\":[\"waikiki\",\"모모스 와이키키\"]}")
                                        .build()));
                    } else if (item.isMessage()) {
                        for (ResponseOutputMessage.Content c : item.asMessage().content()) {
                            if (c.isOutputText()) {
                                System.out.println("    [message] " + c.asOutputText().text());
                            } else if (c.isRefusal()) {
                                System.out.println("    [refusal] " + c.asRefusal().refusal());
                            }
                        }
                    } else {
                        System.out.println("    [other] " + item);
                    }
                }
                if (!hasFunctionCall) {
                    System.out.println("--- loop end: function call 없음(최종 텍스트 턴), turns=" + turn
                            + " elapsedMs=" + (System.currentTimeMillis() - started));
                    return;
                }
            }
            System.out.println("--- loop end: 상한(6) 도달 elapsedMs=" + (System.currentTimeMillis() - started));
        } catch (RuntimeException e) {
            // 모델별 web_search 미지원 등은 여기로 온다 — 오류 본문이 가용성 판정 재료.
            System.out.println("!!! model=" + model + " 실패: " + e);
        }
    }

    // get_note function tool — strict schema(전 필드 required·additionalProperties=false) 정의 실측.
    private static FunctionTool getNoteTool() {
        FunctionTool.Parameters params = FunctionTool.Parameters.builder()
                .putAdditionalProperty("type", JsonValue.from("object"))
                .putAdditionalProperty("properties", JsonValue.from(Map.of(
                        "slug_or_name", Map.of(
                                "type", "string",
                                "description", "조회할 노트의 slug 또는 커피 이름"))))
                .putAdditionalProperty("required", JsonValue.from(List.of("slug_or_name")))
                .putAdditionalProperty("additionalProperties", JsonValue.from(false))
                .build();
        return FunctionTool.builder()
                .name("get_note")
                .description("저장된 커피 노트 1건을 조회한다.")
                .parameters(params)
                .strict(true)
                .build();
    }

    // .env.local(KEY=VALUE properties)에서 OPENAI_API_KEY를 읽는다. 없으면 환경변수 폴백. 파일 내용은 프로세스만 읽는다.
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
