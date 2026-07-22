package com.devwuu.mocha.slack;

import com.devwuu.mocha.slack.inbound.IncomingAction;
import com.devwuu.mocha.slack.inbound.IncomingMedia;
import com.devwuu.mocha.slack.inbound.IncomingMessage;
import com.devwuu.mocha.slack.inbound.IncomingPhoto;
import com.slack.api.app_backend.interactive_components.payload.BlockActionPayload;
import com.slack.api.bolt.App;
import com.slack.api.bolt.AppConfig;
import com.slack.api.bolt.socket_mode.SocketModeApp;
import com.slack.api.model.File;
import com.slack.api.model.event.FileSharedEvent;
import com.slack.api.model.event.MessageChangedEvent;
import com.slack.api.model.event.MessageEvent;
import com.slack.api.model.event.MessageFileShareEvent;
import com.slack.api.socket_mode.SocketModeClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * 파이프라인 진입 — Slack Socket Mode 수신 게이트웨이 (ref: plan.md#ADR-2, §3; tasks T3-1).
 * <p>HTTP 컨트롤러에 대응하는 <b>얇은 수신 계층</b>이다(CLAUDE.md §2): Bolt 이벤트를 파싱해 내부
 * 표현({@link IncomingMessage}/{@link IncomingAction})으로 바꾸고 {@link ConversationRouter}에 위임할 뿐,
 * pending 분기·저장 같은 로직은 갖지 않는다. 인바운드 개방 없이 WebSocket 아웃바운드로만 연결한다(NFR-1).
 * <p>재연결은 Slack SDK에 위임하고 시작/종료만 로그로 남긴다(plan.md §7). 토큰(2종)은 환경변수로 주입하며,
 * 미설정 시 연결을 건너뛰어 토큰 없는 프로파일(테스트/CI)에서도 컨텍스트가 뜬다.
 */
// CLI --rerender(rerender 프로파일)에서는 수신 소켓을 띄우지 않는다 — 리렌더만 하고 종료(tasks T5-1).
@Component
@Profile("!rerender")
public class SlackGateway implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(SlackGateway.class);

    // 모든 block action을 라우터로 흘려보낸다 — 저장/취소 등 action_id 분기는 라우터 몫(ADR-3).
    private static final Pattern ALL_ACTIONS = Pattern.compile(".+");

    private final ConversationRouter router;
    private final String botToken;
    private final String appToken;

    private volatile boolean running = false;
    private SocketModeApp socketModeApp;

    // ADR-19: 무거운 handle* 처리를 넘길 실행기 — 핸들러 스레드는 즉시 ack하고 여기서 순차 백그라운드 처리한다.
    // start()가 단일 스레드 ExecutorService로 채우고 stop()에서 shutdown한다. 종전 단일 워커의 순차 처리 의미(pending·버퍼 순서)를 보존.
    // volatile: start()에서 대입한 값을 Bolt 수신 스레드가 확실히 보게 한다(running과 동일한 교차 스레드 가시성 관례).
    private volatile Executor executor;

    public SlackGateway(
            ConversationRouter router,
            @Value("${mocha.slack.bot-token:}") String botToken,
            @Value("${mocha.slack.app-token:}") String appToken) {
        this.router = router;
        this.botToken = botToken;
        this.appToken = appToken;
    }

    // 테스트 seam(패키지-프라이빗): same-thread(direct) 실행기를 주입해 ack/dispatch 배선을 네트워크·스레드 없이
    // 결정론적으로 검증한다. 주입되면 start()는 실행기를 새로 만들지 않는다(ADR-19).
    void seedExecutor(Executor executor) {
        this.executor = executor;
    }

    // --- 파싱 + 위임 (얇은 수신 계층의 본체, 네트워크 없이 단위 테스트 대상) ---

    // 이미지 파일만 커피 사진으로 취급한다 — 그 외 첨부(문서 등)는 무시.
    private static final String IMAGE_MIME_PREFIX = "image/";

    /**
     * 평문 메시지 이벤트를 내부 표현으로 파싱해 라우터에 넘긴다.
     * <p>편집/삭제/파일공유 등 subtype 메시지는 Slack 모델에서 별도 이벤트 클래스로 오므로 이 핸들러
     * ({@code MessageEvent.class})에는 도달하지 않는다 — 사진(file_share)은 {@link #handleFileShareEvent}가 받는다.
     */
    void handleMessageEvent(MessageEvent event) {
        // 봇 자신·다른 봇이 보낸 메시지는 무시 — 미리보기 응답을 다시 입력으로 먹는 에코 루프 방지.
        if (event.getBotId() != null) {
            return;
        }
        router.onMessage(new IncomingMessage(
                event.getUser(), event.getChannel(), event.getText(), event.getTs()));
    }

    /**
     * Block Kit 액션(버튼)을 내부 표현으로 파싱해 라우터에 넘긴다. 첫 액션만 취한다(버튼 1개 전제).
     */
    void handleBlockAction(BlockActionPayload payload) {
        List<BlockActionPayload.Action> actions = payload.getActions();
        if (actions == null || actions.isEmpty()) {
            return;
        }
        BlockActionPayload.Action action = actions.get(0);
        String userId = payload.getUser() != null ? payload.getUser().getId() : null;
        String channelId = payload.getChannel() != null ? payload.getChannel().getId() : null;
        // 미리보기 메시지 ts(preview_ts)는 payload가 아니라 pending에 영속된 값을 쓴다(data-model §2.3).
        router.onAction(new IncomingAction(userId, channelId, action.getActionId()));
    }

    /**
     * 파일 공유 메시지(사진 업로드)를 파싱해 라우터에 넘긴다 (ref: tasks T4-2, FR-10).
     * <p>이미지 파일만 사진으로 추려 {@link IncomingMedia}로 먼저 위임하고(스테이징이 버퍼에 자리 잡게),
     * 같은 이벤트에 캡션 텍스트가 실려 있으면 {@link IncomingMessage}로 이어 위임한다 — 사진→텍스트 순서라야
     * 버퍼 그룹핑이 성립한다(사진 버퍼 위에 텍스트가 얹혀 하나의 노트가 된다).
     */
    void handleFileShareEvent(MessageFileShareEvent event) {
        // 봇 자신이 배달한 카드는 MessageFileShareEvent를 내지 않으므로(top-level file_shared만 발생) 이 경로엔
        // 봇 에코가 유입되지 않는다 — 봇 user ID 필터(ADR-17)는 실효 없어 제거했다. 봇 자기 이벤트 차단은
        // Bolt IgnoringSelfEvents + file_shared no-op(buildApp), 재질문 차단 실효는 즉시 ack(ADR-19)가 담당한다.
        // (ref: specs/coffee-note-agent/changes/0008-socket-mode-async-ack/delta.md#ADR-19, AC-Δ4)
        List<IncomingPhoto> photos = imagePhotos(event.getFiles());
        if (!photos.isEmpty()) {
            router.onMedia(new IncomingMedia(
                    event.getUser(), event.getChannel(), photos, event.getTs()));
        }
        String text = event.getText();
        if (text != null && !text.isBlank()) {
            router.onMessage(new IncomingMessage(
                    event.getUser(), event.getChannel(), text, event.getTs()));
        }
    }

    // 이미지 파일만 추려 내부 표현으로 변환. 인증 다운로드용으로 url_private_download를 우선 사용한다.
    // HEIC 등 vision 미지원 원본을 위해 썸네일 URL 후보(최대 해상도 우선)와 메타 mimetype도 함께 싣는다(ADR-29, TΔ3).
    private static List<IncomingPhoto> imagePhotos(List<File> files) {
        List<IncomingPhoto> photos = new ArrayList<>();
        if (files == null) {
            return photos;
        }
        for (File file : files) {
            String mimetype = file.getMimetype();
            if (mimetype == null || !mimetype.startsWith(IMAGE_MIME_PREFIX)) {
                continue;
            }
            String url = file.getUrlPrivateDownload() != null ? file.getUrlPrivateDownload() : file.getUrlPrivate();
            photos.add(new IncomingPhoto(url, file.getName(), mimetype, thumbnailUrls(file)));
        }
        return photos;
    }

    // Slack 썸네일 사다리를 최대 해상도부터 모은다(실측: thumb_1024=1024×768 최대, PNG — findings-TΔ0).
    // HEIC 대체 다운로드(TΔ3)가 앞 후보부터 시도하도록 내림차순으로 싣는다. 부재 키는 건너뛴다.
    private static List<String> thumbnailUrls(File file) {
        List<String> urls = new ArrayList<>();
        addIfPresent(urls, file.getThumb1024());
        addIfPresent(urls, file.getThumb960());
        addIfPresent(urls, file.getThumb800());
        addIfPresent(urls, file.getThumb720());
        addIfPresent(urls, file.getThumb480());
        addIfPresent(urls, file.getThumb360());
        addIfPresent(urls, file.getThumb160());
        addIfPresent(urls, file.getThumb80());
        addIfPresent(urls, file.getThumb64());
        return urls;
    }

    private static void addIfPresent(List<String> urls, String url) {
        if (url != null && !url.isBlank()) {
            urls.add(url);
        }
    }

    // --- 소켓 수명주기 (SmartLifecycle) ---

    @Override
    public void start() {
        if (isBlank(botToken) || isBlank(appToken)) {
            log.warn("Slack 토큰 미설정(SLACK_BOT_TOKEN/SLACK_APP_TOKEN) — Socket Mode 연결을 건너뜁니다.");
            return;
        }
        try {
            // ADR-19: ack와 처리를 분리할 단일 스레드 실행기를 준비한다(테스트가 seedExecutor로 주입했으면 그대로 사용).
            // 명명·데몬 스레드로 두어 로그 추적을 돕고 JVM 종료를 막지 않는다.
            if (executor == null) {
                executor = Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "mocha-slack-worker");
                    t.setDaemon(true);
                    return t;
                });
            }
            // 기본 Tyrus 백엔드는 javax.websocket 구현을 요구 → 단일 jar인 JavaWebSocket 백엔드로 구동(build.gradle).
            socketModeApp = new SocketModeApp(appToken, SocketModeClient.Backend.JavaWebSocket, buildApp());
            socketModeApp.startAsync(); // 논블로킹 — 단절 시 SDK가 자동 재연결(plan.md §7)
            running = true;
            log.info("Slack Socket Mode 연결 시작.");
        } catch (Exception e) {
            throw new IllegalStateException("Slack Socket Mode 시작 실패", e);
        }
    }

    @Override
    public void stop() {
        if (socketModeApp != null) {
            try {
                socketModeApp.close();
                log.info("Slack Socket Mode 연결 종료.");
            } catch (Exception e) {
                log.warn("Slack Socket Mode 종료 중 오류", e);
            }
        }
        // ADR-19: 백그라운드 실행기 정리 — 진행 중 태스크를 잠시 기다린 뒤 강제 종료한다. 주입된 direct 실행기는 대상 아님.
        if (executor instanceof ExecutorService worker) {
            worker.shutdown();
            try {
                if (!worker.awaitTermination(5, TimeUnit.SECONDS)) {
                    worker.shutdownNow();
                }
            } catch (InterruptedException e) {
                worker.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    // POLICY: Slack 핸들러는 3초 내 ack, 무거운 처리(파이프라인·렌더·업로드)는 단일 스레드 백그라운드로 —
    // 핸들러 스레드 동기 실행 금지(ack 타임아웃 재전송 차단) (ref: plan.md#ADR-19).
    // 백그라운드 태스크의 Throwable은 로그로 삼켜 실행기 스레드 생존을 보장한다(AC-Δ6) — 실패 처리는 각 handle* 내부에 그대로 남는다.
    private void dispatch(Runnable task) {
        executor.execute(() -> {
            try {
                task.run();
            } catch (Throwable t) {
                log.error("Slack 이벤트 백그라운드 처리 실패 — 실행기 스레드는 유지된다.", t);
            }
        });
    }

    // Bolt App에 핸들러를 배선한다. 각 핸들러는 무거운 파싱·처리를 실행기로 넘기고(ADR-19) 즉시 ack로 응답한다.
    // (package-private: 네트워크 없이 핸들러 배선을 단위 테스트로 검증하기 위함)
    App buildApp() {
        App app = new App(AppConfig.builder().singleTeamBotToken(botToken).build());
        app.event(MessageEvent.class, (payload, ctx) -> {
            dispatch(() -> handleMessageEvent(payload.getEvent()));
            return ctx.ack();
        });
        app.event(MessageFileShareEvent.class, (payload, ctx) -> {
            dispatch(() -> handleFileShareEvent(payload.getEvent()));
            return ctx.ack();
        });
        app.blockAction(ALL_ACTIONS, (req, ctx) -> {
            dispatch(() -> handleBlockAction(req.getPayload()));
            return ctx.ack();
        });
        // POLICY: 봇 활동(미리보기 갱신 chat.update 등)이 되돌려보내는 message_changed subtype 이벤트는
        // 라우터 위임 없이 no-op ack로 소비한다. 미처리 시 Bolt가 404 no handler found로 반려해 로그 잡음이
        // 되고(AC-Δ1), 위임하면 봇 자신의 편집이 파이프라인에 유입돼 에코 루프가 된다(AC-Δ3, 기존 getBotId 무시와 동일 정신).
        // 대상은 봇 활동 유발분(message_changed)으로 한정 — bot_message/message_deleted는 실제 404 관측 시 추가.
        // (ref: specs/coffee-note-agent/changes/0003-socket-mode-noop-handlers/delta.md#ADR-12, AC-Δ1/AC-Δ3)
        app.event(MessageChangedEvent.class, (payload, ctx) -> ctx.ack());
        // POLICY: 봇 카드 업로드가 되돌려보내는 top-level file_shared(FileSharedEvent) 이벤트는 라우터 위임 없이
        // no-op ack로 소비한다. 핸들러가 없으면 Bolt가 404 no handler found로 반려해 로그 잡음이 되고(실측 404),
        // 사용자 사진은 MessageFileShareEvent로 별도 수신하므로 이 경로에 위임할 것이 없다(message_changed no-op과 동일 정신).
        // (ref: specs/coffee-note-agent/changes/0008-socket-mode-async-ack/delta.md#ADR-19, AC-Δ3)
        app.event(FileSharedEvent.class, (payload, ctx) -> ctx.ack());
        return app;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
