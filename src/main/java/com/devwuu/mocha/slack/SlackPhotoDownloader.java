package com.devwuu.mocha.slack;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Slack 파일 원본 다운로더 — bot token 인증 (ref: tasks T4-1, FR-10).
 * <p>Slack 업로드 파일의 {@code url_private}는 공개 URL이 아니라 {@code Authorization: Bearer <bot-token>}
 * 인증이 있어야 원본을 내려준다. 이 어댑터가 그 인증 GET을 담당한다 — 다운로드 세부(HTTP·토큰)를
 * 파이프라인 밖으로 가둔다(CLAUDE.md §2 얇은 수신 계층/어댑터 경계).
 * <p>비정상 응답·I/O 실패는 삼키지 않고 {@link PhotoDownloadException}으로 수렴시킨다(plan.md §7).
 */
@Component
public class SlackPhotoDownloader {

    private final String botToken;
    private final HttpClient http;

    @Autowired
    public SlackPhotoDownloader(@Value("${mocha.slack.bot-token:}") String botToken) {
        this(botToken, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build());
    }

    // 테스트 주입용 — 실제 HTTP 없이 로컬 서버/스텁 클라이언트로 인증 헤더·실패 경로를 검증한다.
    SlackPhotoDownloader(String botToken, HttpClient http) {
        this.botToken = botToken;
        this.http = http;
    }

    /**
     * {@code url_private}를 bot token으로 인증해 원본 바이트를 받는다.
     *
     * @throws PhotoDownloadException 2xx 외 응답 또는 네트워크/인터럽트 실패 시.
     */
    public byte[] download(String urlPrivate) {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(urlPrivate))
                .timeout(Duration.ofSeconds(30))
                .header("Authorization", "Bearer " + botToken)
                .GET()
                .build();
        try {
            HttpResponse<byte[]> response = http.send(request, HttpResponse.BodyHandlers.ofByteArray());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw new PhotoDownloadException("사진 다운로드 실패(status=" + status + "): " + urlPrivate);
            }
            return response.body();
        } catch (IOException e) {
            throw new PhotoDownloadException("사진 다운로드 실패: " + urlPrivate, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new PhotoDownloadException("사진 다운로드 중단: " + urlPrivate, e);
        }
    }
}
