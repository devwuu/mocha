package com.devwuu.mocha.slack;

import com.slack.api.app_backend.interactive_components.payload.BlockActionPayload;
import com.slack.api.model.event.MessageEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T3-1: 수신 계층은 얇게 — Bolt 이벤트 파싱과 라우터 위임만 검증한다(로직은 T3-2 라우터로).
 * 실제 소켓 연결 없이 handle* 진입점에 이벤트를 직접 먹여 파싱·위임을 단언한다.
 */
class SlackGatewayTest {

    /** 위임 여부·파싱 결과를 캡처하는 fake 라우터(외부 의존 대체, CLAUDE.md §5.2). */
    private static final class CapturingRouter implements ConversationRouter {
        final List<IncomingMessage> messages = new ArrayList<>();
        final List<IncomingAction> actions = new ArrayList<>();

        @Override
        public void onMessage(IncomingMessage message) {
            messages.add(message);
        }

        @Override
        public void onAction(IncomingAction action) {
            actions.add(action);
        }
    }

    private SlackGateway gateway(ConversationRouter router) {
        // 토큰 미설정 = 소켓 미연결. 파싱 경로만 검증한다.
        return new SlackGateway(router, "", "");
    }

    @Test
    @DisplayName("메시지 이벤트를 내부 표현으로 파싱해 Router.onMessage로 위임한다")
    void parsesAndDelegatesMessage() {
        CapturingRouter router = new CapturingRouter();
        MessageEvent event = new MessageEvent();
        event.setUser("U1");
        event.setChannel("C1");
        event.setText("커피베라 예가체프 마셨는데 새콤하고 좋았다");
        event.setTs("1720000000.000100");

        gateway(router).handleMessageEvent(event);

        assertEquals(1, router.messages.size());
        IncomingMessage msg = router.messages.get(0);
        assertEquals("U1", msg.userId());
        assertEquals("C1", msg.channelId());
        assertEquals("커피베라 예가체프 마셨는데 새콤하고 좋았다", msg.text());
        assertEquals("1720000000.000100", msg.ts());
        assertTrue(router.actions.isEmpty());
    }

    @Test
    @DisplayName("봇이 보낸 메시지는 무시한다 — 미리보기 응답 에코 루프 방지")
    void ignoresBotMessage() {
        CapturingRouter router = new CapturingRouter();
        MessageEvent event = new MessageEvent();
        event.setUser("U1");
        event.setText("무시되어야 함");
        event.setBotId("B123");

        gateway(router).handleMessageEvent(event);

        assertTrue(router.messages.isEmpty());
    }

    @Test
    @DisplayName("Block Kit 버튼 액션을 파싱해 Router.onAction으로 위임한다 (저장/취소 재료)")
    void parsesAndDelegatesAction() {
        CapturingRouter router = new CapturingRouter();

        BlockActionPayload payload = new BlockActionPayload();
        BlockActionPayload.User user = new BlockActionPayload.User();
        user.setId("U1");
        payload.setUser(user);
        BlockActionPayload.Channel channel = new BlockActionPayload.Channel();
        channel.setId("C1");
        payload.setChannel(channel);
        BlockActionPayload.Container container = new BlockActionPayload.Container();
        container.setMessageTs("1720000000.000999");
        payload.setContainer(container);
        BlockActionPayload.Action action = new BlockActionPayload.Action();
        action.setActionId("save");
        action.setValue("coffeevera-yirgacheffe-g1");
        payload.setActions(List.of(action));

        gateway(router).handleBlockAction(payload);

        assertEquals(1, router.actions.size());
        IncomingAction parsed = router.actions.get(0);
        assertEquals("U1", parsed.userId());
        assertEquals("C1", parsed.channelId());
        assertEquals("save", parsed.actionId());
        assertEquals("coffeevera-yirgacheffe-g1", parsed.value());
        // 버튼이 달린 메시지 ts = 미리보기 메시지(preview_ts)
        assertEquals("1720000000.000999", parsed.messageTs());
        assertTrue(router.messages.isEmpty());
    }

    @Test
    @DisplayName("액션이 비어 있으면 위임하지 않는다 — 파싱 방어")
    void ignoresEmptyActions() {
        CapturingRouter router = new CapturingRouter();
        BlockActionPayload payload = new BlockActionPayload();
        payload.setActions(List.of());

        gateway(router).handleBlockAction(payload);

        assertTrue(router.actions.isEmpty());
    }

    @Test
    @DisplayName("토큰 미설정이면 소켓을 연결하지 않고 running=false로 남는다 (테스트/CI 안전)")
    void skipsConnectWhenTokensMissing() {
        SlackGateway gateway = gateway(new CapturingRouter());

        gateway.start(); // 예외 없이 통과해야 한다 — 토큰 없으면 조용히 스킵

        assertTrue(!gateway.isRunning());
    }
}
