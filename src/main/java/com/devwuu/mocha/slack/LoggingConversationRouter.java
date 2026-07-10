package com.devwuu.mocha.slack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * T3-1 골격용 임시 라우터 — 수신 이벤트를 로그로만 남기고 아무 파이프라인도 태우지 않는다.
 * <p>게이트웨이 위임 시임({@link ConversationRouter})이 배선 가능함을 확인하고, 실 토큰 구동 시
 * 이벤트가 라우터까지 도달하는지 눈으로 보게 하려는 목적이다.
 * <p><b>T3-2에서 pending 유무 분기 로직을 담은 실제 구현체로 대체된다</b>(ADR-3) — 이 클래스는 그때 제거한다.
 */
@Component
public class LoggingConversationRouter implements ConversationRouter {

    private static final Logger log = LoggerFactory.getLogger(LoggingConversationRouter.class);

    @Override
    public void onMessage(IncomingMessage message) {
        log.info("메시지 수신(T3-2 미구현): user={} channel={} ts={} text={}",
                message.userId(), message.channelId(), message.ts(), message.text());
    }

    @Override
    public void onAction(IncomingAction action) {
        log.info("액션 수신(T3-2 미구현): user={} actionId={} messageTs={}",
                action.userId(), action.actionId(), action.messageTs());
    }
}
