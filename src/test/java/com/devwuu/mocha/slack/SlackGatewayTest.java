package com.devwuu.mocha.slack;

import com.slack.api.app_backend.interactive_components.payload.BlockActionPayload;
import com.slack.api.bolt.App;
import com.slack.api.bolt.context.builtin.EventContext;
import com.slack.api.bolt.handler.BoltEventHandler;
import com.slack.api.bolt.response.Response;
import com.slack.api.bolt.util.EventsApiPayloadParser;
import com.slack.api.model.File;
import com.slack.api.model.event.Event;
import com.slack.api.model.event.MessageChangedEvent;
import com.slack.api.model.event.MessageEvent;
import com.slack.api.model.event.MessageFileShareEvent;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
        final List<IncomingMedia> media = new ArrayList<>();

        @Override
        public void onMessage(IncomingMessage message) {
            messages.add(message);
        }

        @Override
        public void onAction(IncomingAction action) {
            actions.add(action);
        }

        @Override
        public void onMedia(IncomingMedia media) {
            this.media.add(media);
        }
    }

    private static File imageFile(String name, String urlDownload, String mimetype) {
        File file = new File();
        file.setName(name);
        file.setMimetype(mimetype);
        file.setUrlPrivateDownload(urlDownload);
        return file;
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
    @DisplayName("T4-2: 파일 공유 이벤트의 이미지만 추려 onMedia로 위임하고, 비이미지는 거른다")
    void parsesFileShareToMedia() {
        CapturingRouter router = new CapturingRouter();
        MessageFileShareEvent event = new MessageFileShareEvent();
        event.setUser("U1");
        event.setChannel("C1");
        event.setTs("1720000000.000400");
        event.setFiles(List.of(
                imageFile("a.jpg", "https://slack/a", "image/jpeg"),
                imageFile("doc.pdf", "https://slack/doc", "application/pdf"), // 비이미지 → 제외
                imageFile("b.png", "https://slack/b", "image/png")));

        gateway(router).handleFileShareEvent(event);

        assertEquals(1, router.media.size());
        IncomingMedia media = router.media.get(0);
        assertEquals("U1", media.userId());
        assertEquals("C1", media.channelId());
        assertEquals(2, media.photos().size(), "이미지 2장만 추려진다(pdf 제외)");
        assertEquals("https://slack/a", media.photos().get(0).urlPrivate());
        assertEquals("a.jpg", media.photos().get(0).filename());
        // 캡션 없는 앨범이면 텍스트 위임은 없다.
        assertTrue(router.messages.isEmpty());
    }

    @Test
    @DisplayName("T4-2: 캡션이 실린 앨범은 사진(onMedia) 먼저, 캡션(onMessage) 뒤로 위임한다")
    void parsesFileShareWithCaption() {
        CapturingRouter router = new CapturingRouter();
        MessageFileShareEvent event = new MessageFileShareEvent();
        event.setUser("U1");
        event.setChannel("C1");
        event.setTs("1720000000.000500");
        event.setText("커피베라 예가체프 새콤했어");
        event.setFiles(List.of(imageFile("a.jpg", "https://slack/a", "image/jpeg")));

        gateway(router).handleFileShareEvent(event);

        assertEquals(1, router.media.size(), "사진이 먼저 위임된다(버퍼 자리잡기)");
        assertEquals(1, router.messages.size(), "캡션이 이어 위임된다");
        assertEquals("커피베라 예가체프 새콤했어", router.messages.get(0).text());
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
    @DisplayName("AC-Δ1/AC-Δ3: message_changed에 no-op ack 핸들러가 배선되고, 호출 시 라우터를 부르지 않는다")
    void registersNoopAckForMessageChanged() throws Exception {
        CapturingRouter router = new CapturingRouter();
        // buildApp은 AppConfig에 봇 토큰을 요구한다 — 네트워크 호출 없이 배선만 검증하므로 더미 토큰이면 충분.
        SlackGateway gateway = new SlackGateway(router, "xoxb-test", "xapp-test");

        App app = gateway.buildApp();

        // AC-Δ1: 봇 chat.update가 되돌려보내는 message_changed에 핸들러가 있어 Bolt가 404 대신 소비한다.
        String key = EventsApiPayloadParser.getEventTypeAndSubtype(MessageChangedEvent.class);
        @SuppressWarnings("unchecked")
        Map<String, List<BoltEventHandler<Event>>> handlers =
                (Map<String, List<BoltEventHandler<Event>>>) readField(app, "eventHandlers");
        List<BoltEventHandler<Event>> forChanged = handlers.get(key);
        assertNotNull(forChanged, "message_changed 핸들러가 등록되어야 한다: " + key);
        assertEquals(1, forChanged.size());

        // AC-Δ3: no-op 핸들러는 ctx.ack()만 하고 ConversationRouter를 부르지 않는다(파이프라인 유입 차단).
        Response ack = forChanged.get(0).apply(null, new EventContext());
        assertEquals(Integer.valueOf(200), ack.getStatusCode(), "no-op는 ack(200)만 반환한다");
        assertTrue(router.messages.isEmpty() && router.actions.isEmpty() && router.media.isEmpty(),
                "no-op 핸들러는 라우터를 호출하지 않는다");
    }

    private static Object readField(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    @Test
    @DisplayName("토큰 미설정이면 소켓을 연결하지 않고 running=false로 남는다 (테스트/CI 안전)")
    void skipsConnectWhenTokensMissing() {
        SlackGateway gateway = gateway(new CapturingRouter());

        gateway.start(); // 예외 없이 통과해야 한다 — 토큰 없으면 조용히 스킵

        assertTrue(!gateway.isRunning());
    }
}
