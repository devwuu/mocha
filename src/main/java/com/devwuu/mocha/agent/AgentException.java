package com.devwuu.mocha.agent;

/**
 * 에이전트 턴 실패 신호 — 모델 호출 오류·타임아웃, tool 호출 상한 도달 등 턴을 완결하지 못한 경우
 * (ref: specs/coffee-note-agent/plan.md#ADR-44, #ADR-48).
 * <p>상위(라우터)는 이를 결정론 폴백으로 수렴시킨다 — pending·노트 무변화 + 재요청 안내 + 원문 로그(AC-63).
 * 이미 성공한 제안 tool의 pending·미리보기는 유효하게 남는다(부분 성공 존중).
 */
public class AgentException extends RuntimeException {

    public AgentException(String message) {
        super(message);
    }

    public AgentException(String message, Throwable cause) {
        super(message, cause);
    }
}
