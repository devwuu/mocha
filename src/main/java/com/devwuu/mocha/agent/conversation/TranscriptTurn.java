package com.devwuu.mocha.agent.conversation;

import com.devwuu.mocha.agent.prompt.TurnPrompt;

import java.util.Objects;

/**
 * 작업 트랜스크립트의 턴 1건 — 사용자 발화와 그에 대한 에이전트 응답 요지의 쌍
 * (ref: specs/coffee-note-agent/data-model.md#2.5, spec FR-23).
 * <p>턴 수 상한({@code mocha.agent.transcript-max-turns})의 계수 단위가 이 쌍이다.
 * 모델 입력({@link TurnPrompt.Message})으로의 재구성은 컨텍스트 조립부의 몫이다(ADR-44).
 */
public record TranscriptTurn(String userMessage, String mochaMessage) {

    public TranscriptTurn {
        Objects.requireNonNull(userMessage, "userMessage");
        Objects.requireNonNull(mochaMessage, "mochaMessage");
    }
}
