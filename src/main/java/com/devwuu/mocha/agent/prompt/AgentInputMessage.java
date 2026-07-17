package com.devwuu.mocha.agent.prompt;

import java.util.Objects;

/**
 * 에이전트 턴 입력의 대화 메시지 1건 — 트랜스크립트(ADR-46)를 모델 입력으로 재구성하는 단위
 * (ref: specs/coffee-note-agent/plan.md#ADR-44, findings-TΔ0.md §SDK — 턴과 턴 사이 문맥은
 * 메모리 트랜스크립트가 소유하고, 새 턴 시작 시 role user/mocha 메시지로 재구성한다).
 */
public record AgentInputMessage(Role role, String content) {

    public enum Role {
        USER, MOCHA
    }

    public AgentInputMessage {
        Objects.requireNonNull(role, "role");
        Objects.requireNonNull(content, "content");
    }

    public static AgentInputMessage user(String content) {
        return new AgentInputMessage(Role.USER, content);
    }

    public static AgentInputMessage mocha(String content) {
        return new AgentInputMessage(Role.MOCHA, content);
    }
}
