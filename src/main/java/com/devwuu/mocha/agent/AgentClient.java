package com.devwuu.mocha.agent;

import com.devwuu.mocha.agent.prompt.AgentTurnInput;
import com.devwuu.mocha.agent.tool.AgentTool;

import java.util.List;

/**
 * 에이전트 루프 드라이버 경계 (ref: specs/coffee-note-agent/plan.md#ADR-44, NFR-4).
 * <p>계약: {@code runTurn(context, tools): String} — 모델 호출↔tool 실행 루프를 상한
 * ({@code mocha.agent.max-tool-calls})까지 구동하고 모델의 최종 텍스트를 돌려준다.
 * 최종 텍스트가 곧 Slack 응답이다 — 미리보기·카드 등 구조화 송신은 tool 구현체의 몫.
 * <p>POLICY: 루프 밖 코드는 OpenAI SDK 타입을 직접 참조하지 않는다 — AgentClient/VisionClient/AliasGenerator(보조 콜) 뒤에만
 * (ref: specs/coffee-note-agent/plan.md#ADR-44 POLICY, NFR-4). 구현: {@link OpenAiAgentClient}.
 */
public interface AgentClient {

    /**
     * 에이전트 턴 1회 실행 — tool 호출이 없는 응답이 나올 때까지 루프를 돌리고 최종 텍스트를 반환한다.
     *
     * @throws AgentException 모델 호출 실패, tool 호출 상한 도달 등 턴 미완결 시(ADR-48 폴백 대상)
     */
    String runTurn(AgentTurnInput context, List<AgentTool> tools);
}
