package com.devwuu.mocha.slack;

import com.devwuu.mocha.agent.AgentClient;
import com.devwuu.mocha.agent.AgentContextAssembler;
import com.devwuu.mocha.agent.AgentException;
import com.devwuu.mocha.agent.AgentMessage;
import com.devwuu.mocha.agent.AgentTool;
import com.devwuu.mocha.agent.AgentTools;
import com.devwuu.mocha.agent.AgentTurnContext;
import com.devwuu.mocha.agent.ConversationTranscript;
import com.devwuu.mocha.agent.ProposalValidator;
import com.devwuu.mocha.agent.TranscriptTurn;
import com.devwuu.mocha.domain.Entry;
import com.devwuu.mocha.domain.MatchInfo;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.PendingNote;
import com.devwuu.mocha.domain.PhotoBuffer;
import com.devwuu.mocha.domain.Rating;
import com.devwuu.mocha.domain.Sourced;
import com.devwuu.mocha.json.MochaObjectMapper;
import com.devwuu.mocha.llm.VisionExtraction;
import com.devwuu.mocha.llm.VisionHint;
import com.devwuu.mocha.pipeline.PhotoInfoExtractor;
import com.devwuu.mocha.repository.PendingStore;
import com.devwuu.mocha.repository.PhotoBufferStore;
import com.devwuu.mocha.repository.PhotoStore;
import com.devwuu.mocha.repository.StagedImage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TΔ7b(changes/0018): 라우터 배선 + 결정론 폴백 — 버튼 외 수신 = OCR 전처리 → 에이전트 턴(ADR-44·47),
 * 버튼 = 에이전트 미경유 결정론 분기(AC-Δ7), 턴 실패 = pending 무변화 + 재요청 안내(ADR-48, AC-63).
 * 외부 의존(모델·store·flow)은 전부 fake — LLM 판단 자체는 비대상(모듈 CLAUDE.md §5.2·5.3).
 */
class AgentConversationRouterTest {

    private static final String USER = "U-dev";
    private static final String CHANNEL = "C-mocha";
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final Clock clock = Clock.fixed(Instant.parse("2026-07-17T01:20:30Z"), SEOUL); // Seoul 10:20:30
    private final FakeAgentClient agentClient = new FakeAgentClient();
    private final FakePendingStore pendingStore = new FakePendingStore();
    private final ConversationTranscript transcript = new ConversationTranscript(20, Duration.ofHours(1));
    private final RecordingResponder responder = new RecordingResponder();
    private final CapturingFlow flow = new CapturingFlow();
    private final FakePhotoStore photoStore = new FakePhotoStore();
    private final FakePhotoBufferStore photoBufferStore = new FakePhotoBufferStore();
    private final StubPhotoInfoExtractor photoInfoExtractor = new StubPhotoInfoExtractor();
    private AgentConversationRouter router;

    @BeforeEach
    void setUp() {
        SlackPhotoIntake photoIntake = new SlackPhotoIntake(pendingStore, responder,
                url -> new byte[0], photoStore, photoBufferStore, photoInfoExtractor,
                Duration.ofMinutes(3), clock);
        // fake AgentClient는 tool 실행기를 부르지 않으므로 lookup·제안 협력자는 미접촉 — 장착 목록 계약만 쓴다.
        AgentTools agentTools = new AgentTools(null, null, responder, Path.of("unused-artifact"),
                MochaObjectMapper.create(), pendingStore, null, new ProposalValidator(), transcript, clock);
        router = new AgentConversationRouter(pendingStore, transcript, agentClient, agentTools,
                new AgentContextAssembler(MochaObjectMapper.create(), clock), photoIntake,
                responder, flow, clock);
    }

    @Test
    @DisplayName("FR-22/ADR-44: 버튼 외 텍스트 수신 = 에이전트 턴 — tool 5종 장착, 최종 텍스트 응답, 트랜스크립트 축적")
    void textMessageRunsAgentTurn() {
        agentClient.reply = "커피 얘기 좋아요 멍! 🐾";

        router.onMessage(message("요즘 커피 뭐가 맛있어?"));

        assertThat(agentClient.calls).isEqualTo(1);
        assertThat(responder.posted).containsExactly("커피 얘기 좋아요 멍! 🐾");
        // 이번 발화가 messages의 마지막 user 메시지로 실린다(TΔ7a 조립 계약).
        List<AgentMessage> messages = agentClient.lastContext.messages();
        assertThat(messages.get(messages.size() - 1)).isEqualTo(AgentMessage.user("요즘 커피 뭐가 맛있어?"));
        assertThat(agentClient.lastTools).extracting(AgentTool::name).containsExactly(
                "list_notes", "get_note", "propose_record", "propose_edit", "send_entry_card");
        // 제안 없는 턴은 트랜스크립트에 쌓인다(FR-23) — 다음 턴의 문맥이 된다.
        assertThat(transcript.view(USER))
                .containsExactly(new TranscriptTurn("요즘 커피 뭐가 맛있어?", "커피 얘기 좋아요 멍! 🐾"));
        assertThat(photoInfoExtractor.calls).isZero(); // 사진 없으면 vision 미호출(FR-19)
    }

    @Test
    @DisplayName("AC-Δ7/AC-63/ADR-48: 턴 실패 폴백 — pending·트랜스크립트 무변화 + 재요청 안내 전송")
    void agentFailureFallsBackWithoutStateChange() {
        PendingNote pending = pendingNote();
        pendingStore.put(USER, pending);
        transcript.append(USER, new TranscriptTurn("이 커피 뭐더라", "찾아볼게요 멍"));
        agentClient.failure = new AgentException("tool 호출 상한(8) 도달 — 턴 중단");

        router.onMessage(message("어제 마신 예가체프 새콤했어"));

        assertThat(pendingStore.get(USER)).contains(pending); // pending 무변화
        assertThat(responder.posted).containsExactly(FlowMessages.AGENT_TURN_FAILED);
        assertThat(transcript.view(USER)).hasSize(1);         // 실패 턴은 문맥에 쌓지 않는다
    }

    @Test
    @DisplayName("AC-Δ7/ADR-3: [저장]/[취소] 버튼은 에이전트 미경유 결정론 분기 + 커밋 접힘(ADR-46 규칙 ②)")
    void buttonActionsBypassAgent() {
        transcript.append(USER, new TranscriptTurn("잡담", "네 멍"));
        router.onAction(action(DefaultConversationRouter.ACTION_SAVE));

        assertThat(flow.saves).hasSize(1);
        assertThat(agentClient.calls).isZero();
        assertThat(transcript.view(USER)).isEmpty(); // SAVE_COMMIT 접힘

        transcript.append(USER, new TranscriptTurn("잡담 둘", "네 멍"));
        router.onAction(action(DefaultConversationRouter.ACTION_CANCEL));

        assertThat(flow.cancels).hasSize(1);
        assertThat(agentClient.calls).isZero();
        assertThat(transcript.view(USER)).isEmpty(); // CANCEL_COMMIT 접힘
    }

    @Test
    @DisplayName("계약 밖 action_id는 무시 — flow·에이전트 미호출(구 라우터 규칙 승계)")
    void unknownActionIsIgnored() {
        router.onAction(action("mocha_unknown"));

        assertThat(flow.saves).isEmpty();
        assertThat(flow.cancels).isEmpty();
        assertThat(agentClient.calls).isZero();
    }

    @Test
    @DisplayName("FR-10: 사진 수신은 버퍼·스테이징 배관으로만 위임 — 에이전트 미경유(캡션은 별도 텍스트 턴)")
    void mediaDelegatesWithoutAgent() {
        router.onMedia(new IncomingMedia(USER, CHANNEL, List.of(), "1720000000.000111"));

        assertThat(flow.media).hasSize(1);
        assertThat(agentClient.calls).isZero();
    }

    @Test
    @DisplayName("FR-19/ADR-44: 윈도우 안 버퍼 사진은 루프 전 OCR 1콜 — 결과가 턴 컨텍스트에 실린다")
    void bufferedPhotosArePreprocessedIntoContext() {
        bufferPhoto("bag.jpg");
        photoInfoExtractor.canned = new VisionExtraction("Kenya AA", "FroB", null, null, null, List.of());

        router.onMessage(message("이거 마셨어"));

        assertThat(photoInfoExtractor.calls).isEqualTo(1);
        assertThat(agentClient.lastContext.instructions()).contains("Kenya AA"); // OCR 결과 주입(TΔ7a)
        // 제안 없는 턴 — 버퍼는 남아 윈도우 안 후속 텍스트가 다시 흡수한다(소비는 pending 이관 시점).
        assertThat(photoBufferStore.get(USER)).isPresent();
    }

    @Test
    @DisplayName("ADR-46 규칙 ①/FR-10: 제안 성공 턴 — 접힘 유지(턴 재축적 없음) + 버퍼 소비")
    void proposalTurnKeepsFoldAndConsumesBuffer() {
        bufferPhoto("bag.jpg");
        transcript.append(USER, new TranscriptTurn("이 커피 뭐더라", "찾아볼게요 멍"));
        agentClient.onRun = () -> {
            // 제안 tool 성공 효과 시뮬레이션(TΔ6 계약) — pending 생성 + 트랜스크립트 접힘.
            pendingStore.put(USER, pendingNote());
            transcript.clear(USER, ConversationTranscript.FoldTrigger.PROPOSAL_ACCEPTED);
        };

        router.onMessage(message("어제 마신 걸로 기록해줘"));

        assertThat(responder.posted).containsExactly(agentClient.reply);
        assertThat(transcript.view(USER)).isEmpty();      // 접힘 후 문맥은 pending draft가 대신한다(FR-23)
        assertThat(photoBufferStore.get(USER)).isEmpty(); // 사진은 pending으로 이관 — 버퍼 소비
    }

    @Test
    @DisplayName("ADR-48: 실패 턴은 버퍼를 보존한다 — 사진이 재시도로 살아남는다(유실 금지)")
    void failedTurnKeepsBuffer() {
        bufferPhoto("bag.jpg");
        agentClient.failure = new AgentException("에이전트 모델 호출 실패");

        router.onMessage(message("이거 기록해줘"));

        assertThat(photoBufferStore.get(USER)).isPresent();
        assertThat(responder.posted).containsExactly(FlowMessages.AGENT_TURN_FAILED);
    }

    // ---- 헬퍼 ----

    private static IncomingMessage message(String text) {
        return new IncomingMessage(USER, CHANNEL, text, "1720000000.000123");
    }

    private static IncomingAction action(String actionId) {
        return new IncomingAction(USER, CHANNEL, actionId, null, "1720000000.000456");
    }

    private void bufferPhoto(String stagedName) {
        photoBufferStore.put(USER, new PhotoBuffer(OffsetDateTime.now(clock), List.of(stagedName)));
        photoStore.staged.put(USER, List.of(new StagedImage(stagedName, new byte[]{1})));
    }

    private static PendingNote pendingNote() {
        OffsetDateTime at = OffsetDateTime.parse("2026-07-16T10:00:00+09:00");
        Entry entry = new Entry(LocalDate.of(2026, 7, 16), "새콤하고 좋았음", Rating.GOOD, null, at);
        Note draft = new Note("2026-07-16-102030", Sourced.user("커피베라 예가체프 G1"), Sourced.user("커피베라"),
                null, null, null, null, List.of(), List.of(entry), at, at);
        return new PendingNote(draft, MatchInfo.newNote(), "1720000000.000789", at);
    }

    // ---- fakes (모듈 CLAUDE.md §5.2 — 외부 의존은 인터페이스 stub/fake) ----

    /** 턴 입력을 캡처하고 지정된 응답·실패를 돌려주는 fake 루프 드라이버 — LLM 미접촉. */
    private static final class FakeAgentClient implements AgentClient {
        String reply = "네 멍!";
        RuntimeException failure;
        Runnable onRun;
        int calls;
        AgentTurnContext lastContext;
        List<AgentTool> lastTools;

        @Override
        public String runTurn(AgentTurnContext context, List<AgentTool> tools) {
            calls++;
            lastContext = context;
            lastTools = tools;
            if (onRun != null) {
                onRun.run();
            }
            if (failure != null) {
                throw failure;
            }
            return reply;
        }
    }

    private static final class FakePendingStore implements PendingStore {
        private final Map<String, PendingNote> pendings = new LinkedHashMap<>();

        @Override
        public void put(String userId, PendingNote pending) {
            pendings.put(userId, pending);
        }

        @Override
        public Optional<PendingNote> get(String userId) {
            return Optional.ofNullable(pendings.get(userId));
        }

        @Override
        public void clear(String userId) {
            pendings.remove(userId);
        }
    }

    /** 평문 전송만 캡처하는 responder — 폴백·최종 텍스트 단언용(Slack 미접촉). */
    private static final class RecordingResponder implements SlackResponder {
        final List<String> posted = new ArrayList<>();

        @Override
        public void post(String channelId, String text) {
            posted.add(text);
        }

        @Override
        public void postImage(String channelId, Path imagePath, String caption) {
            throw new UnsupportedOperationException("에이전트 라우터는 이미지 배달을 직접 하지 않는다");
        }

        @Override
        public void finalizePreview(String channelId, PendingNote pending, String statusText) {
            throw new UnsupportedOperationException("버튼 소진은 커밋 flow의 몫(TΔ8a 이관 전)");
        }
    }

    /** 버튼·사진 위임만 캡처하는 flow — 구 텍스트 flow가 호출되면 배선 위반으로 즉시 실패한다(AC-Δ7). */
    private static final class CapturingFlow implements ConversationFlows {
        final List<IncomingAction> saves = new ArrayList<>();
        final List<IncomingAction> cancels = new ArrayList<>();
        final List<IncomingMedia> media = new ArrayList<>();

        @Override
        public void startNewNote(IncomingMessage message) {
            throw new UnsupportedOperationException("텍스트는 에이전트 턴으로만 흐른다(ADR-44)");
        }

        @Override
        public void revisePending(IncomingMessage message, PendingNote pending) {
            throw new UnsupportedOperationException("텍스트는 에이전트 턴으로만 흐른다(ADR-44)");
        }

        @Override
        public void guidePendingExists(IncomingMessage message) {
            throw new UnsupportedOperationException("의도 게이트 안내는 폐지됐다(ADR-47)");
        }

        @Override
        public void guideNotARecord(IncomingMessage message) {
            throw new UnsupportedOperationException("의도 게이트 안내는 폐지됐다(ADR-47)");
        }

        @Override
        public void searchNotes(IncomingMessage message) {
            throw new UnsupportedOperationException("검색은 에이전트 tool로만 흐른다(ADR-44)");
        }

        @Override
        public void endSearch(IncomingMessage message) {
            throw new UnsupportedOperationException("검색 세션은 폐지됐다(ADR-46)");
        }

        @Override
        public void confirmSave(IncomingAction action) {
            saves.add(action);
        }

        @Override
        public void cancel(IncomingAction action) {
            cancels.add(action);
        }

        @Override
        public void receiveMedia(IncomingMedia incoming) {
            media.add(incoming);
        }
    }

    private static final class FakePhotoBufferStore implements PhotoBufferStore {
        private final Map<String, PhotoBuffer> buffers = new LinkedHashMap<>();

        @Override
        public void put(String userId, PhotoBuffer buffer) {
            buffers.put(userId, buffer);
        }

        @Override
        public Optional<PhotoBuffer> get(String userId) {
            return Optional.ofNullable(buffers.get(userId));
        }

        @Override
        public void clear(String userId) {
            buffers.remove(userId);
        }
    }

    private static final class FakePhotoStore implements PhotoStore {
        final Map<String, List<StagedImage>> staged = new LinkedHashMap<>();

        @Override
        public String stage(String userId, String filename, byte[] bytes) {
            throw new UnsupportedOperationException("스테이징은 사진 수신 경로(onMedia)의 몫");
        }

        @Override
        public List<StagedImage> readStaged(String userId) {
            return staged.getOrDefault(userId, List.of());
        }

        @Override
        public List<String> commit(String userId, String slug, String date) {
            throw new UnsupportedOperationException("사진 커밋은 [저장] 버튼 flow의 몫(AC-Δ3)");
        }

        @Override
        public void discard(String userId) {
            staged.remove(userId);
        }

        @Override
        public void moveEntryPhotos(String slug, String fromDate, String toDate) {
            throw new UnsupportedOperationException();
        }

        @Override
        public List<String> stagedUserIds() {
            return List.copyOf(staged.keySet());
        }
    }

    /** OCR 결과를 지정하는 stub — vision 미접촉, 호출 횟수를 캡처한다(FR-19 전처리 배선 단언용). */
    private static final class StubPhotoInfoExtractor extends PhotoInfoExtractor {
        VisionExtraction canned = VisionExtraction.empty();
        int calls;

        StubPhotoInfoExtractor() {
            super(null, 4); // VisionClient 미사용 — extract를 통째로 대체한다.
        }

        @Override
        public VisionExtraction extract(List<StagedImage> images, VisionHint hint) {
            calls++;
            return canned;
        }
    }
}
