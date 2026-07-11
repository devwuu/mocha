package com.devwuu.mocha.slack;

import com.slack.api.app_backend.events.payload.EventsApiPayload;
import com.slack.api.app_backend.events.payload.MessageFileSharePayload;
import com.slack.api.app_backend.events.payload.MessagePayload;
import com.slack.api.app_backend.interactive_components.payload.BlockActionPayload;
import com.slack.api.bolt.App;
import com.slack.api.bolt.context.builtin.ActionContext;
import com.slack.api.bolt.context.builtin.EventContext;
import com.slack.api.bolt.handler.BoltEventHandler;
import com.slack.api.bolt.handler.builtin.BlockActionHandler;
import com.slack.api.bolt.request.RequestHeaders;
import com.slack.api.bolt.request.builtin.BlockActionRequest;
import com.slack.api.bolt.response.Response;
import com.slack.api.bolt.util.EventsApiPayloadParser;
import com.slack.api.model.File;
import com.slack.api.model.event.Event;
import com.slack.api.model.event.MessageChangedEvent;
import com.slack.api.model.event.MessageEvent;
import com.slack.api.model.event.MessageFileShareEvent;
import com.slack.api.util.json.GsonFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.regex.Pattern;

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

    /** 넘겨받은 태스크를 실행하지 않고 캡처만 하는 실행기 — ack가 처리 완료를 기다리지 않음을 단언하기 위함(ADR-19). */
    private static final class RecordingExecutor implements Executor {
        final List<Runnable> tasks = new ArrayList<>();

        @Override
        public void execute(Runnable command) {
            tasks.add(command);
        }

        void runAll() {
            for (Runnable r : new ArrayList<>(tasks)) {
                r.run();
            }
        }
    }

    /** 첫 위임에서 한 번 예외를 던지고 이후엔 정상 기록하는 라우터 — 백그라운드 예외 내성 검증용(AC-Δ6). */
    private static final class FlakyRouter implements ConversationRouter {
        final List<IncomingMessage> messages = new ArrayList<>();
        boolean throwNext = true;

        @Override
        public void onMessage(IncomingMessage message) {
            if (throwNext) {
                throwNext = false;
                throw new RuntimeException("의도적 실패 — dispatch가 삼켜야 한다");
            }
            messages.add(message);
        }

        @Override
        public void onAction(IncomingAction action) {
        }

        @Override
        public void onMedia(IncomingMedia media) {
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
        // 토큰 미설정 = 소켓 미연결. 파싱 경로만 검증한다. MethodsClient는 start() 미호출이라 불필요(null).
        return new SlackGateway(router, null, "", "");
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
    @DisplayName("AC-Δ1: 봇 자신이 배달한 카드(file_share)는 onMedia/onMessage로 위임하지 않는다 — 에코 루프 차단")
    void ignoresOwnFileShare() {
        CapturingRouter router = new CapturingRouter();
        SlackGateway gateway = gateway(router);
        seedBotUserId(gateway, "UBOT"); // start()의 auth.test 확보분을 네트워크 없이 시딩(리플렉션)

        MessageFileShareEvent event = new MessageFileShareEvent();
        event.setUser("UBOT"); // 봇 자신이 유발한 카드 배달 이벤트
        event.setChannel("C1");
        event.setTs("1720000000.000600");
        event.setText("저장했어요 멍! 🐾"); // 카드 캡션 — 신규 기록으로 둔갑하면 안 됨
        event.setFiles(List.of(imageFile("card.jpg", "https://slack/card", "image/jpeg")));

        gateway.handleFileShareEvent(event);

        assertTrue(router.media.isEmpty(), "봇 카드 JPG는 사진으로 버퍼링되지 않는다");
        assertTrue(router.messages.isEmpty(), "봇 캡션은 신규 기록 텍스트로 유입되지 않는다");
    }

    @Test
    @DisplayName("AC-Δ2: 봇 ID가 확보돼도 사용자 file_share·캡션은 종전대로 위임한다 — 필터가 사용자 경로를 가리지 않음")
    void delegatesUserFileShareEvenWhenBotIdKnown() {
        CapturingRouter router = new CapturingRouter();
        SlackGateway gateway = gateway(router);
        seedBotUserId(gateway, "UBOT");

        MessageFileShareEvent event = new MessageFileShareEvent();
        event.setUser("U1"); // 사용자 — 봇과 다른 ID
        event.setChannel("C1");
        event.setTs("1720000000.000700");
        event.setText("커피베라 예가체프 새콤했어");
        event.setFiles(List.of(imageFile("a.jpg", "https://slack/a", "image/jpeg")));

        gateway.handleFileShareEvent(event);

        assertEquals(1, router.media.size(), "사용자 사진은 종전대로 위임된다(FR-10/AC-8 불변)");
        assertEquals(1, router.messages.size(), "사용자 캡션도 종전대로 위임된다");
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
        SlackGateway gateway = new SlackGateway(router, null, "xoxb-test", "xapp-test");

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

    // start()의 auth.test가 채우는 botUserId를 네트워크 없이 세팅한다 — 테스트 seam을 프로덕션에 두지 않기 위해
    // 리플렉션으로 private 필드에 직접 주입한다(위 readField와 동일 관례).
    private static void seedBotUserId(SlackGateway gateway, String botUserId) {
        try {
            Field field = SlackGateway.class.getDeclaredField("botUserId");
            field.setAccessible(true);
            field.set(gateway, botUserId);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }

    // buildApp의 이벤트 핸들러를 네트워크 없이 직접 호출한다 — 실제 Bolt 등록 map에서 꺼내 payload를 먹인다.
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Response invokeEvent(App app, Class<? extends Event> eventClass,
                                        EventsApiPayload<?> payload) throws Exception {
        String key = EventsApiPayloadParser.getEventTypeAndSubtype(eventClass);
        Map<String, List<BoltEventHandler<Event>>> handlers =
                (Map<String, List<BoltEventHandler<Event>>>) readField(app, "eventHandlers");
        BoltEventHandler handler = handlers.get(key).get(0);
        return handler.apply(payload, new EventContext());
    }

    // buildApp의 blockAction 핸들러를 직접 호출한다 — payload를 snake_case JSON으로 왕복시켜 실제 Request로 재구성.
    @SuppressWarnings("unchecked")
    private static Response invokeBlockAction(App app, BlockActionPayload payload) throws Exception {
        String json = GsonFactory.createSnakeCase().toJson(payload);
        BlockActionRequest req = new BlockActionRequest(json, json, new RequestHeaders(Map.of()));
        Map<Pattern, BlockActionHandler> handlers =
                (Map<Pattern, BlockActionHandler>) readField(app, "blockActionHandlers");
        BlockActionHandler handler = handlers.values().iterator().next();
        return handler.apply(req, new ActionContext());
    }

    private static MessagePayload messagePayload(String text) {
        MessageEvent event = new MessageEvent();
        event.setUser("U1");
        event.setChannel("C1");
        event.setText(text);
        event.setTs("1720000000.000100");
        MessagePayload payload = new MessagePayload();
        payload.setEvent(event);
        return payload;
    }

    private static SlackGateway wiredGateway(ConversationRouter router, Executor executor) {
        // buildApp은 AppConfig에 봇 토큰을 요구한다 — 네트워크 없이 배선만 검증하므로 더미 토큰이면 충분.
        SlackGateway gateway = new SlackGateway(router, null, "xoxb-test", "xapp-test");
        gateway.seedExecutor(executor);
        return gateway;
    }

    @Test
    @DisplayName("AC-Δ1: 메시지 핸들러는 handle*를 실행기로 넘기고 즉시 ack — 라우터 위임은 태스크 실행 뒤에")
    void messageHandlerOffloadsThenAcks() throws Exception {
        CapturingRouter router = new CapturingRouter();
        RecordingExecutor executor = new RecordingExecutor();
        App app = wiredGateway(router, executor).buildApp();

        Response ack = invokeEvent(app, MessageEvent.class, messagePayload("예가체프 새콤"));

        assertEquals(Integer.valueOf(200), ack.getStatusCode(), "즉시 ack(200)를 반환한다");
        assertTrue(router.messages.isEmpty(), "ack 시점엔 아직 라우터에 위임되지 않는다(백그라운드로 오프로드)");
        assertEquals(1, executor.tasks.size(), "무거운 처리는 실행기 태스크로 넘어간다");

        executor.runAll();
        assertEquals(1, router.messages.size(), "태스크 실행 시 handle*가 라우터에 위임한다");
        assertEquals("예가체프 새콤", router.messages.get(0).text());
    }

    @Test
    @DisplayName("AC-Δ1: 파일 공유 핸들러도 실행기로 오프로드하고 즉시 ack한다")
    void fileShareHandlerOffloadsThenAcks() throws Exception {
        CapturingRouter router = new CapturingRouter();
        RecordingExecutor executor = new RecordingExecutor();
        App app = wiredGateway(router, executor).buildApp();

        MessageFileShareEvent event = new MessageFileShareEvent();
        event.setUser("U1");
        event.setChannel("C1");
        event.setTs("1720000000.000400");
        event.setFiles(List.of(imageFile("a.jpg", "https://slack/a", "image/jpeg")));
        MessageFileSharePayload payload = new MessageFileSharePayload();
        payload.setEvent(event);

        Response ack = invokeEvent(app, MessageFileShareEvent.class, payload);

        assertEquals(Integer.valueOf(200), ack.getStatusCode(), "즉시 ack(200)를 반환한다");
        assertTrue(router.media.isEmpty(), "ack 시점엔 아직 위임되지 않는다");
        assertEquals(1, executor.tasks.size());

        executor.runAll();
        assertEquals(1, router.media.size(), "태스크 실행 시 사진이 위임된다");
    }

    @Test
    @DisplayName("AC-Δ2: [저장] 등 blockAction도 실행기로 오프로드하고 즉시 ack한다 — 버튼 경고 원인 제거")
    void blockActionOffloadsThenAcks() throws Exception {
        CapturingRouter router = new CapturingRouter();
        RecordingExecutor executor = new RecordingExecutor();
        App app = wiredGateway(router, executor).buildApp();

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

        Response ack = invokeBlockAction(app, payload);

        assertEquals(Integer.valueOf(200), ack.getStatusCode(), "즉시 ack(200)를 반환한다");
        assertTrue(router.actions.isEmpty(), "ack 시점엔 아직 위임되지 않는다(렌더/업로드는 백그라운드)");
        assertEquals(1, executor.tasks.size());

        executor.runAll();
        assertEquals(1, router.actions.size(), "태스크 실행 시 액션이 위임된다");
        assertEquals("save", router.actions.get(0).actionId());
    }

    @Test
    @DisplayName("AC-Δ6: 백그라운드 태스크 예외는 로그로 수렴하고 실행기·후속 처리를 죽이지 않는다")
    void backgroundExceptionDoesNotKillExecutor() throws Exception {
        FlakyRouter router = new FlakyRouter();
        App app = wiredGateway(router, Runnable::run).buildApp(); // direct 실행기 — 태스크가 즉시 인라인 실행

        // 첫 태스크에서 라우터가 던져도 dispatch가 Throwable을 삼켜 apply는 정상 ack 반환.
        Response first = invokeEvent(app, MessageEvent.class, messagePayload("첫째"));
        assertEquals(Integer.valueOf(200), first.getStatusCode(), "예외가 나도 즉시 ack(200)");

        // 실행기 스레드가 살아 후속 메시지가 계속 처리된다.
        Response second = invokeEvent(app, MessageEvent.class, messagePayload("둘째"));
        assertEquals(Integer.valueOf(200), second.getStatusCode());
        assertEquals(1, router.messages.size(), "둘째 메시지는 정상 위임된다(실행기 생존)");
        assertEquals("둘째", router.messages.get(0).text());
    }

    @Test
    @DisplayName("토큰 미설정이면 소켓을 연결하지 않고 running=false로 남는다 (테스트/CI 안전)")
    void skipsConnectWhenTokensMissing() {
        SlackGateway gateway = gateway(new CapturingRouter());

        gateway.start(); // 예외 없이 통과해야 한다 — 토큰 없으면 조용히 스킵

        assertTrue(!gateway.isRunning());
    }
}
