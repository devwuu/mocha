package com.devwuu.mocha.agent;

/**
 * 제안 tool 서버 검증의 결과 타입 — 통과({@link Ok}) 또는 <b>사유를 담은 거부</b>({@link Rejected}).
 * <p>POLICY: 제안 tool의 서버 검증 실패는 오류 사유를 tool 결과로 반환 — 조용한 드롭·서버 대행 금지
 * (ref: specs/coffee-note-agent/plan.md#ADR-45, AC-9). 거부 사유는 에이전트가 루프 안에서 정정하거나
 * 사용자에게 안내하는 재료가 된다(AC-Δ5).
 */
public sealed interface ToolValidation<T> {

    /** 검증 통과 — 정규화된 도메인 값을 담는다. */
    record Ok<T>(T value) implements ToolValidation<T> {
    }

    /** 검증 거부 — 에이전트에게 돌려줄 사유. */
    record Rejected<T>(String reason) implements ToolValidation<T> {
    }

    static <T> ToolValidation<T> ok(T value) {
        return new Ok<>(value);
    }

    static <T> ToolValidation<T> rejected(String reason) {
        return new Rejected<>(reason);
    }
}
