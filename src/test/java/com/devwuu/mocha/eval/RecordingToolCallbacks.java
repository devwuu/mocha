package com.devwuu.mocha.eval;

import com.devwuu.mocha.agent.tool.ToolCallback;
import com.devwuu.mocha.agent.tool.ToolCallbackProvider;
import com.devwuu.mocha.agent.turn.TurnUserMessage;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * tool 호출 기록 데코레이터 — 실 {@link ToolCallbackProvider}가 장착한 tool 목록을 그대로 감싸,
 * 호출명·인자 JSON·결과 JSON을 순서대로 적재한다 (ref: plan.md#ADR-68, changes/0026 TΔ2).
 *
 * <p><b>새 인터페이스를 만들지 않는다</b>(REVIEW.md §2 표 불변, delta UNCHANGED 행). {@link ToolCallback}은
 * {@code record(name, description, parametersSchema, executor)}라 같은 3필드 + 래핑 executor로 재구성하면
 * 끝이고, 모델이 보는 tool 정의는 한 글자도 달라지지 않는다 — 하네스가 관측만 얹고 계약은 건드리지 않는다.
 *
 * <p>기록이 판정의 절반이다: {@code onMessage}가 예외를 삼키는 구조라(findings-TΔ0 §1.1) "무엇이 왜
 * 거부됐나"는 tool 결과에만 남는다. 거부는 프로덕션이 한 형태로만 내보내므로({@code {"error": 사유}} —
 * ADR-67 단일 지점) 여기서 사유를 그대로 회수할 수 있다.
 */
final class RecordingToolCallbacks extends ToolCallbackProvider {

    private final ToolCallbackProvider delegate;
    private final ObjectMapper mapper;
    private final List<Invocation> invocations = new ArrayList<>();

    RecordingToolCallbacks(ToolCallbackProvider delegate, ObjectMapper mapper) {
        // 상위 조립물은 쓰지 않는다 — 전 호출을 delegate로 넘긴다. 미접촉을 null로 정직하게 남기는
        // 픽스처 관례와 같은 정신(협력자를 조용히 끼워 넣어 경로를 가리지 않는다).
        super(null, null, null, null, null, null, null, null, null, null, null);
        this.delegate = delegate;
        this.mapper = mapper;
    }

    @Override
    public List<ToolCallback> forTurn(String userId, String channelId, TurnUserMessage utterance) {
        return delegate.forTurn(userId, channelId, utterance).stream()
                .map(this::recording)
                .toList();
    }

    /** 지금까지 이 케이스 실행에서 기록된 호출 — 발화 시퀀스 전체에 걸쳐 누적된다(멀티턴 판정 단위). */
    List<Invocation> invocations() {
        return List.copyOf(invocations);
    }

    private ToolCallback recording(ToolCallback original) {
        return new ToolCallback(original.name(), original.description(), original.parametersSchema(),
                args -> {
                    String result;
                    try {
                        result = original.executor().execute(args);
                    } catch (RuntimeException e) {
                        // tool 구현이 예외로 새는 것은 그 자체로 관측 대상이다 — 기록하고 그대로 올려보낸다.
                        invocations.add(new Invocation(original.name(), args, "<예외> " + e, null));
                        throw e;
                    }
                    invocations.add(new Invocation(original.name(), args, result, rejectionReason(result)));
                    return result;
                });
    }

    // 거부 = 모델 대면 오류 결과 {"error": 사유}. 프로덕션 정의가 ToolSupport.errorOutput 한 곳뿐이라
    // (ADR-67) 이 한 형태만 보면 된다. 파싱 불가·형태 불일치는 거부가 아니다(정상 결과로 센다).
    private String rejectionReason(String resultJson) {
        try {
            JsonNode node = mapper.readTree(resultJson);
            JsonNode error = node.get("error");
            return error != null && !error.isNull() ? error.asString() : null;
        } catch (JacksonException e) {
            return null;
        }
    }

    /**
     * tool 호출 1회 기록.
     *
     * @param name            tool 이름.
     * @param argumentsJson   모델이 보낸 인자 JSON — 거부된 호출은 pending에 흔적이 없어 여기서만 볼 수 있다.
     * @param resultJson      tool이 돌려준 결과 JSON.
     * @param rejectionReason 검증 거부 사유. 거부가 아니면 null.
     */
    record Invocation(String name, String argumentsJson, String resultJson, String rejectionReason) {

        boolean rejected() {
            return rejectionReason != null;
        }
    }
}
