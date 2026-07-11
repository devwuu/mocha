package com.devwuu.mocha.slack;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T4-1: SlackPhotoDownloader — 로컬 HTTP 서버로 bot token 인증 GET을 end-to-end 검증(CLAUDE.md §5.2 어댑터).
 * <p>실제 Slack 호출 없이 Authorization 헤더 전달과 실패 응답 처리(plan.md §7)를 확인한다.
 */
class SlackPhotoDownloaderTest {

    private static final String BOT_TOKEN = "xoxb-test-token";

    private HttpServer server;
    private String baseUrl;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private SlackPhotoDownloader downloader() {
        return new SlackPhotoDownloader(BOT_TOKEN, HttpClient.newHttpClient());
    }

    @Test
    @DisplayName("T4-1: url_private에 Bearer bot token을 실어 인증하고 원본 바이트를 받는다")
    void downloadsWithBearerAuth() {
        AtomicReference<String> seenAuth = new AtomicReference<>();
        byte[] body = {10, 20, 30, 40};
        server.createContext("/file", exchange -> {
            seenAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            exchange.sendResponseHeaders(200, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        });

        byte[] result = downloader().download(baseUrl + "/file");

        assertThat(seenAuth.get()).isEqualTo("Bearer " + BOT_TOKEN);
        assertThat(result).containsExactly(10, 20, 30, 40);
    }

    @Test
    @DisplayName("T4-1: 비정상 응답(401 등)은 삼키지 않고 PhotoDownloadException으로 수렴한다(plan §7)")
    void nonSuccessStatusFails() {
        server.createContext("/denied", exchange -> {
            exchange.sendResponseHeaders(401, -1);
            exchange.close();
        });

        SlackPhotoDownloader downloader = downloader();
        assertThatThrownBy(() -> downloader.download(baseUrl + "/denied"))
                .isInstanceOf(PhotoDownloadException.class)
                .hasMessageContaining("401");
    }

    @Test
    @DisplayName("T4-1: 네트워크 실패도 PhotoDownloadException으로 수렴한다")
    void networkFailureFails() {
        // 연결이 거부되는 포트(서버 없음)로 요청 → I/O 실패.
        SlackPhotoDownloader downloader = downloader();
        assertThatThrownBy(() -> downloader.download("http://127.0.0.1:1/nope"))
                .isInstanceOf(PhotoDownloadException.class);
    }
}
