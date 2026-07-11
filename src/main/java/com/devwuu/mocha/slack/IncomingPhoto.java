package com.devwuu.mocha.slack;

/**
 * Slack 파일 공유 이벤트의 사진 1건에서 필요한 필드만 뽑아낸 내부 표현 (ref: tasks T4-2, FR-10).
 * <p>{@link SlackGateway}가 Slack {@code File} 타입을 라우팅 경계 밖으로 새지 않게 변환한 값객체다 —
 * 다운로드는 오케스트레이션이 {@link PhotoDownloader}로 수행한다.
 *
 * @param urlPrivate bot token 인증이 필요한 원본 URL(url_private_download 우선).
 * @param filename   원본 파일명 — 스테이징 시 안전 문자로 정규화된다.
 */
public record IncomingPhoto(String urlPrivate, String filename) {
}
