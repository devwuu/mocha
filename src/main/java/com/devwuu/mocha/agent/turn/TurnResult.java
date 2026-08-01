package com.devwuu.mocha.agent.turn;

/**
 * 에이전트 턴 1회의 결과 — 모델의 최종 텍스트와 이번 턴의 제안(있으면)
 * (ref: specs/coffee-note-agent/changes/0029-app-interface/tasks.md TΔ6a).
 * <p>{@code POST /api/agent/turn}의 응답 본문이 이 형태다 — {@code contract/agent-turn.contract.json}의
 * {@code response}가 {@code {reply, draft}}이고, 여기 {@code proposal}이 그 {@code draft}가 된다.
 * <p><b>{@code proposal}이 null인 턴이 정상이다</b>(잡담·조회·검증 거부) — 그때 클라이언트는 폼을
 * <i>지우지 않는다</i>. "제안이 없었다"와 "폼을 비워라"는 다른 말이고, 계약이 그 구분을 nullable로 표현한다.
 *
 * @param reply    모델의 최종 텍스트 — 그대로 사용자에게 보인다(ADR-44).
 * @param proposal 이번 턴에 제안된 draft. 제안 tool이 성공하지 않은 턴은 null({@link TurnProposalSink}).
 */
public record TurnResult(String reply, TurnDraft proposal) {
}
