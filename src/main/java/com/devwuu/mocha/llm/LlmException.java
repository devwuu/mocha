package com.devwuu.mocha.llm;

/**
 * LLM 호출·구조화 실패 신호 (ref: specs/coffee-note-agent/plan.md §7, data-model.md#V-1).
 * <p>호출 실패/타임아웃, 또는 재시도 소진 후에도 남은 스키마·도메인 위반을 상위(파이프라인)로 전달한다.
 * 상위는 이를 "다시 보내주세요" 오류 응답 + pending 미생성으로 수렴시킨다.
 */
public class LlmException extends RuntimeException {

    public LlmException(String message) {
        super(message);
    }

    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
}
