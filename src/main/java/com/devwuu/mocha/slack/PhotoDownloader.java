package com.devwuu.mocha.slack;

/**
 * 사진 원본 다운로드 경계 (ref: tasks T4-1/T4-2, spec FR-10).
 * <p>Slack {@code url_private}는 bot token 인증이 있어야 원본을 내려주는 외부 호출이다. 사진 수신 경로
 * ({@link SlackPhotoIntake})가 HTTP·토큰 세부를 모른 채 "URL → 바이트"만 얻도록 인터페이스로 가둔다
 * (CLAUDE.md §2 — 외부 의존은 인터페이스 뒤로, 교체 가능성 NFR-4). 구현: {@link SlackPhotoDownloader}.
 */
public interface PhotoDownloader {

    /**
     * 사진 원본 URL을 인증해 바이트를 받는다.
     *
     * @throws PhotoDownloadException 비정상 응답 또는 네트워크/인터럽트 실패 시(삼키지 않음, plan §7).
     */
    byte[] download(String urlPrivate);
}
