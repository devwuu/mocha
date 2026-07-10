package com.devwuu.mocha.slack;

/**
 * 평문 응답 송신 경계 — 미리보기(Block Kit)가 아닌 단순 안내 메시지를 채널에 보낸다
 * (저장 완료 안내·만료 안내·취소 안내 등, tasks T3-5).
 * <p>{@link PreviewMessenger}가 확인 미리보기 전용 송신 어댑터라면, 이쪽은 커밋 결과 통지용이다.
 * 오케스트레이션(ConfirmationFlow)이 Slack SDK 타입을 직접 참조하지 않도록 인터페이스로 분리한다
 * (CLAUDE.md §2 — Slack 타입은 송신 어댑터에만). 구현: {@link MethodsSlackResponder}.
 */
public interface SlackResponder {

    /**
     * 채널에 안내 메시지를 보낸다. 커밋 이후 통지이므로 전송 실패가 커밋을 되돌리지 않는다 —
     * 구현체는 예외를 삼키지 말고 로그로 남기되 흐름을 끊지 않는다(plan.md §7).
     */
    void post(String channelId, String text);
}
