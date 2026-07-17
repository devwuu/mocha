package com.devwuu.mocha.slack.inbound;

import com.devwuu.mocha.slack.ConversationRouter;
import com.devwuu.mocha.slack.SlackGateway;

/**
 * Slack 원시 메시지 이벤트에서 필요한 필드만 뽑아낸 내부 표현.
 * <p>수신 계층(SlackGateway)이 Bolt 이벤트 타입을 {@link ConversationRouter} 경계 밖으로 새지 않게
 * 변환한 값객체다 — 라우팅·파이프라인은 Slack SDK 타입을 알 필요가 없다(CLAUDE.md §2 얇은 수신 계층).
 *
 * @param userId    보낸 사용자 id (단일 사용자 전제, NFR-6).
 * @param channelId 수신 채널 id — 미리보기/응답 전송 대상.
 * @param text      메시지 원문. 파이프라인 추출(FR-2)·pending 수정(FR-5) 입력.
 * @param ts        Slack 메시지 timestamp — 버퍼 그룹핑(FR-10)·스레드 기준.
 */
public record IncomingMessage(String userId, String channelId, String text, String ts) {
}
