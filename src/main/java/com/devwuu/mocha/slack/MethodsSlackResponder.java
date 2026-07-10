package com.devwuu.mocha.slack;

import com.slack.api.methods.MethodsClient;
import com.slack.api.methods.response.chat.ChatPostMessageResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * {@link SlackResponder} 구현 — bot token 기반 {@link MethodsClient}로 안내 메시지를 전송한다(tasks T3-5).
 * <p>Slack SDK 타입은 이 어댑터에만 존재한다(CLAUDE.md §2). 커밋 이후 통지라 전송 실패가 저장을 되돌리면
 * 안 되므로, 예외를 잡아 로그로만 남기고 흐름을 끊지 않는다(plan.md §7).
 */
@Component
public class MethodsSlackResponder implements SlackResponder {

    private static final Logger log = LoggerFactory.getLogger(MethodsSlackResponder.class);

    private final MethodsClient methods;

    public MethodsSlackResponder(MethodsClient methods) {
        this.methods = methods;
    }

    @Override
    public void post(String channelId, String text) {
        try {
            ChatPostMessageResponse res = methods.chatPostMessage(r -> r.channel(channelId).text(text));
            if (!res.isOk()) {
                log.warn("안내 메시지 전송 실패: channel={} error={}", channelId, res.getError());
            }
        } catch (Exception e) {
            // 커밋은 이미 끝났다 — 통지 실패는 로그로만 남기고 흐름을 끊지 않는다(plan.md §7).
            log.warn("안내 메시지 전송 중 오류: channel={}", channelId, e);
        }
    }
}
