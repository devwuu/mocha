package com.devwuu.mocha.agent.prompt;

import java.util.List;
import java.util.Objects;

/**
 * 에이전트 턴 1회의 입력 컨텍스트 (ref: specs/coffee-note-agent/plan.md#ADR-44).
 * <p>조립(트랜스크립트 + pending draft + OCR 결과 + today → instructions·messages)은 호출부의 몫이고,
 * 드라이버는 조립된 결과만 받는다 — 이 경계로 SDK 무관 타입이 유지된다(NFR-4).
 *
 * @param instructions 시스템 프롬프트(페르소나·정책 — ADR-47·49)
 * @param messages     대화 메시지(트랜스크립트 재구성 + 이번 발화 — 마지막이 이번 사용자 발화)
 */
public record AgentTurnInput(String instructions, List<AgentInputMessage> messages) {

    public AgentTurnInput {
        Objects.requireNonNull(instructions, "instructions");
        Objects.requireNonNull(messages, "messages");
        if (messages.isEmpty()) {
            throw new IllegalArgumentException("messages가 비어 있음 — 에이전트 턴에는 최소 1개의 입력 메시지가 필요");
        }
        messages = List.copyOf(messages);
    }
}
