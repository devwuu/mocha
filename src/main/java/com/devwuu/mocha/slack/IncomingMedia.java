package com.devwuu.mocha.slack;

import java.util.List;

/**
 * Slack 파일 공유 이벤트에서 뽑아낸 사진 수신 내부 표현 (ref: tasks T4-2, spec FR-10).
 * <p>캡션(텍스트)이 같은 이벤트에 실려 오면 {@link SlackGateway}가 사진(이 값객체)을 먼저 라우팅해 스테이징한 뒤,
 * 캡션을 {@link IncomingMessage}로 이어 보낸다 — 사진 버퍼가 텍스트보다 먼저 자리 잡게 해 그룹핑(FR-10)이 성립한다.
 *
 * @param userId    보낸 사용자 id (단일 사용자 전제, NFR-6).
 * @param channelId 수신 채널 id — 미리보기/응답 전송 대상.
 * @param photos    수신 사진 목록(1건 이상).
 * @param ts        Slack 메시지 timestamp — 버퍼 그룹핑 기준 시각.
 */
public record IncomingMedia(String userId, String channelId, List<IncomingPhoto> photos, String ts) {
}
