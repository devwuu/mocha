package com.devwuu.mocha.slack.inbound;

/**
 * Slack 사진 원본 다운로드 실패 (ref: plan.md §7, tasks T4-1).
 * <p>호출 실패·비정상 응답을 삼키지 않고 이 예외로 수렴시켜, 상위 오케스트레이션(T4-2)이 "오류 응답 +
 * pending 미생성"의 실패 모드로 처리하게 한다(CLAUDE.md §4 외부 호출 실패 처리).
 */
public class PhotoDownloadException extends RuntimeException {

    public PhotoDownloadException(String message, Throwable cause) {
        super(message, cause);
    }

    public PhotoDownloadException(String message) {
        super(message);
    }
}
