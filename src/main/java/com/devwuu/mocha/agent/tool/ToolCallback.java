package com.devwuu.mocha.agent.tool;

import com.devwuu.mocha.agent.OpenAiChatClient;

import java.util.Objects;

/**
 * 에이전트에 등록하는 function tool 1종의 정의 + 실행기 (ref: specs/coffee-note-agent/plan.md#ADR-44, #ADR-45).
 * <p>SDK 무관 도메인 타입 — 드라이버({@link OpenAiChatClient})가 정의를 벤더 tool 스키마로 변환하고,
 * 모델의 tool 호출을 {@link Executor}로 디스패치한다. tool 구현체(ToolCallbackProvider — TΔ5·TΔ6)가
 * 도메인 협력자를 물고 executor를 구성한다.
 *
 * @param name             tool 이름(모델이 호출에 쓰는 식별자)
 * @param description      모델에게 주는 tool 용도 설명
 * @param parametersSchema 인자 JSON schema 문자열(strict — 전 필드 required·additionalProperties=false,
 *                         ref: findings-TΔ0.md §SDK)
 * @param executor         인자 JSON을 받아 결과 JSON을 돌려주는 실행기
 */
public record ToolCallback(String name, String description, String parametersSchema, Executor executor) {

    /**
     * tool 실행기 — 인자 JSON 문자열을 받아 결과 JSON 문자열을 돌려준다.
     * 검증 거부 등 오류도 사유를 담은 결과 문자열로 돌려준다(ADR-45 — 루프 내 정정 재료).
     */
    @FunctionalInterface
    public interface Executor {
        String execute(String argumentsJson);
    }

    public ToolCallback {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(description, "description");
        Objects.requireNonNull(parametersSchema, "parametersSchema");
        Objects.requireNonNull(executor, "executor");
    }
}
