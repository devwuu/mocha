package com.devwuu.mocha.slack;

import com.devwuu.mocha.domain.PendingNote;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * T3-2 골격용 임시 {@link ConfirmationFlow} — 분기 결과를 로그로만 남기고 아무 파이프라인도 태우지 않는다.
 * <p>{@link DefaultConversationRouter}가 위임 시임에 배선됨을 확인하고, 실 토큰 구동 시 이벤트가 어느 분기로
 * 흘러가는지 눈으로 보게 하려는 목적이다.
 * <p><b>T3-3에서 신규 파이프라인·미리보기 조립을 담은 실제 서비스로 대체된다</b> — 이 클래스는 그때 제거한다.
 */
@Component
public class LoggingConfirmationFlow implements ConfirmationFlow {

    private static final Logger log = LoggerFactory.getLogger(LoggingConfirmationFlow.class);

    @Override
    public void startNewNote(IncomingMessage message) {
        log.info("신규 파이프라인 분기(T3-3 미구현): user={} text={}", message.userId(), message.text());
    }

    @Override
    public void revisePending(IncomingMessage message, PendingNote pending) {
        log.info("pending 수정 분기(T3-4 미구현): user={} text={}", message.userId(), message.text());
    }

    @Override
    public void confirmSave(IncomingAction action) {
        log.info("[저장] 분기(T3-5 미구현): user={} messageTs={}", action.userId(), action.messageTs());
    }

    @Override
    public void cancel(IncomingAction action) {
        log.info("[취소] 분기(T3-5 미구현): user={} messageTs={}", action.userId(), action.messageTs());
    }
}
