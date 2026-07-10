package com.devwuu.mocha.config;

import com.slack.api.Slack;
import com.slack.api.methods.MethodsClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Slack 송신 클라이언트 빈 배선 — 미리보기 전송/갱신({@code PreviewMessenger})용 (ref: tasks T3-3).
 * <p>수신(Socket Mode)은 {@code SlackGateway}가 app token으로 직접 배선하고, 송신(chat.postMessage/update)은
 * bot token 기반 {@link MethodsClient}로 한다. 비밀(bot token)은 환경변수(.env)로 주입하며 코드/설정에
 * 하드코딩하지 않는다(루트 CLAUDE.md §5). 미설정 프로파일에서도 컨텍스트가 뜨도록 빈 기본값을 둔다 —
 * 클라이언트 생성은 오프라인이고 실제 호출 시에만 토큰이 필요하다.
 */
@Configuration
public class SlackConfig {

    @Bean
    public MethodsClient slackMethodsClient(@Value("${mocha.slack.bot-token:}") String botToken) {
        return Slack.getInstance().methods(botToken);
    }
}
