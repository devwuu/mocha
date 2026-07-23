package com.devwuu.mocha.slack;

import com.devwuu.mocha.agent.AgentClient;
import com.devwuu.mocha.agent.AgentException;
import com.devwuu.mocha.agent.conversation.ConversationTranscript;
import com.devwuu.mocha.agent.conversation.TranscriptTurn;
import com.devwuu.mocha.agent.prompt.TurnPromptAssembler;
import com.devwuu.mocha.agent.prompt.TurnPrompt;
import com.devwuu.mocha.agent.tool.ToolCallback;
import com.devwuu.mocha.agent.tool.ToolCallbackProvider;
import com.devwuu.mocha.agent.tool.validation.EditProposalValidator;
import com.devwuu.mocha.agent.tool.validation.RecordProposalValidator;
import com.devwuu.mocha.domain.Brew;
import com.devwuu.mocha.domain.Entry;
import com.devwuu.mocha.domain.MatchInfo;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.PendingNote;
import com.devwuu.mocha.domain.PhotoBuffer;
import com.devwuu.mocha.domain.Rating;
import com.devwuu.mocha.domain.Sourced;
import com.devwuu.mocha.domain.Tasting;
import com.devwuu.mocha.json.MochaObjectMapper;
import com.devwuu.mocha.llm.PhotoInfoExtractor;
import com.devwuu.mocha.llm.UtteranceSegmenter;
import com.devwuu.mocha.llm.VisionExtraction;
import com.devwuu.mocha.llm.VisionHint;
import com.devwuu.mocha.repository.PendingStore;
import com.devwuu.mocha.repository.PhotoBufferStore;
import com.devwuu.mocha.repository.PhotoStore;
import com.devwuu.mocha.repository.StagedImage;
import com.devwuu.mocha.slack.inbound.IncomingAction;
import com.devwuu.mocha.slack.inbound.IncomingMedia;
import com.devwuu.mocha.slack.inbound.IncomingMessage;
import com.devwuu.mocha.slack.inbound.IncomingPhoto;
import com.devwuu.mocha.slack.inbound.SlackPhotoIntake;
import com.devwuu.mocha.slack.outbound.MochaMessages;
import com.devwuu.mocha.slack.outbound.SlackResponder;
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
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TΔ7b(changes/0018): 라우터 배선 + 결정론 폴백 — 버튼 외 수신 = OCR 전처리 → 에이전트 턴(ADR-44·47),
 * 버튼 = 에이전트 미경유 결정론 분기(AC-Δ7), 턴 실패 = pending 무변화 + 재요청 안내(ADR-48, AC-63).
 * 커밋 경로는 {@link SlackCommitHandler} 직접 배선(TΔ8a — flow 3종 미경유, AC-Δ3).
 * 외부 의존(모델·store·커밋 핸들러)은 전부 fake — LLM 판단 자체는 비대상(모듈 CLAUDE.md §5.2·5.3).
 */
class AgentConversationRouterTest {

    private static final String USER = "U-dev";
    private static final String CHANNEL = "C-mocha";
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final Clock clock = Clock.fixed(Instant.parse("2026-07-17T01:20:30Z"), SEOUL); // Seoul 10:20:30
    private final FakeAgentClient agentClient = new FakeAgentClient();
    private final FakePendingStore pendingStore = new FakePendingStore();
    private final ConversationTranscript transcript = new ConversationTranscript(20, Duration.ofHours(1), clock);
    private final RecordingResponder responder = new RecordingResponder();
    private final CapturingCommitHandler commitHandler = new CapturingCommitHandler();
    private final FakePhotoStore photoStore = new FakePhotoStore();
    private final FakePhotoBufferStore photoBufferStore = new FakePhotoBufferStore();
    private final StubPhotoInfoExtractor photoInfoExtractor = new StubPhotoInfoExtractor();
    private final FakeSegmenter segmenter = new FakeSegmenter();
    private AgentConversationRouter router;

    @BeforeEach
    void setUp() {
        SlackPhotoIntake photoIntake = new SlackPhotoIntake(pendingStore, responder,
                url -> jpegBytes(), photoStore, photoBufferStore, photoInfoExtractor,
                Duration.ofMinutes(3), clock);
        // fake AgentClient는 tool 실행기를 부르지 않으므로 lookup·제안 협력자는 미접촉 — 장착 목록 계약만 쓴다.
        ToolCallbackProvider toolCallbackProvider = new ToolCallbackProvider(null, null, responder,
                Path.of("unused-artifact"), MochaObjectMapper.create(), pendingStore, null,
                new RecordProposalValidator(clock), new EditProposalValidator(), transcript, clock);
        router = new AgentConversationRouter(pendingStore, transcript, agentClient, toolCallbackProvider,
                new TurnPromptAssembler(MochaObjectMapper.create(), clock), segmenter, photoIntake,
                responder, commitHandler, clock);
    }

    @Test
    @DisplayName("FR-22/ADR-44: 버튼 외 텍스트 수신 = 에이전트 턴 — tool 5종 장착, 최종 텍스트 응답, 트랜스크립트 축적")
    void textMessageRunsAgentTurn() {
        agentClient.reply = "커피 얘기 좋아요 멍! 🐾";

        router.onMessage(message("요즘 커피 뭐가 맛있어?"));

        assertThat(agentClient.calls).isEqualTo(1);
        assertThat(responder.posted).containsExactly("커피 얘기 좋아요 멍! 🐾");
        // 이번 발화가 messages의 마지막 user 메시지로 실린다(TΔ7a 조립 계약).
        List<TurnPrompt.Message> messages = agentClient.lastContext.messages();
        assertThat(messages.get(messages.size() - 1)).isEqualTo(TurnPrompt.Message.user("요즘 커피 뭐가 맛있어?"));
        assertThat(agentClient.lastTools).extracting(ToolCallback::name).containsExactly(
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
        assertThat(responder.posted).containsExactly(MochaMessages.AGENT_TURN_FAILED);
        assertThat(transcript.view(USER)).hasSize(1);         // 실패 턴은 문맥에 쌓지 않는다
    }

    @Test
    @DisplayName("AC-Δ7/AC-Δ3/ADR-3: [저장]/[취소] 버튼은 에이전트 미경유 — 커밋 핸들러 직접 분기(TΔ8a) + 커밋 접힘(ADR-46 규칙 ②)")
    void buttonActionsBypassAgent() {
        transcript.append(USER, new TranscriptTurn("잡담", "네 멍"));
        router.onAction(action(AgentConversationRouter.ACTION_SAVE));

        assertThat(commitHandler.saves).hasSize(1);
        assertThat(agentClient.calls).isZero();
        assertThat(transcript.view(USER)).isEmpty(); // SAVE_COMMIT 접힘

        transcript.append(USER, new TranscriptTurn("잡담 둘", "네 멍"));
        router.onAction(action(AgentConversationRouter.ACTION_CANCEL));

        assertThat(commitHandler.cancels).hasSize(1);
        assertThat(agentClient.calls).isZero();
        assertThat(transcript.view(USER)).isEmpty(); // CANCEL_COMMIT 접힘
    }

    @Test
    @DisplayName("계약 밖 action_id는 무시 — 커밋 핸들러·에이전트 미호출(구 라우터 규칙 승계)")
    void unknownActionIsIgnored() {
        router.onAction(action("mocha_unknown"));

        assertThat(commitHandler.saves).isEmpty();
        assertThat(commitHandler.cancels).isEmpty();
        assertThat(agentClient.calls).isZero();
    }

    @Test
    @DisplayName("FR-10/TΔ8a: 사진 수신은 버퍼·스테이징 배관(SlackPhotoIntake) 직접 배선 — 에이전트·flow 미경유")
    void mediaDelegatesWithoutAgent() {
        IncomingPhoto photo = new IncomingPhoto("https://slack/bag.jpg", "bag.jpg", "image/jpeg", List.of());
        router.onMedia(new IncomingMedia(USER, CHANNEL, List.of(photo), "1720000000.000111"));

        assertThat(photoStore.staged.get(USER)).extracting(StagedImage::name).containsExactly("bag.jpg");
        assertThat(photoBufferStore.get(USER)).isPresent(); // pending 없음 → 버퍼에 담아 텍스트를 기다린다
        assertThat(agentClient.calls).isZero();
    }

    @Test
    @DisplayName("FR-19/ADR-44: 윈도우 안 버퍼 사진은 루프 전 OCR 1콜 — 결과가 턴 컨텍스트에 실린다")
    void bufferedPhotosArePreprocessedIntoContext() {
        bufferPhoto("bag.jpg");
        photoInfoExtractor.canned = new VisionExtraction("Kenya AA", "FroB", List.of(), null, List.of());

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
        assertThat(responder.posted).containsExactly(MochaMessages.AGENT_TURN_FAILED);
    }

    @Test
    @DisplayName("ADR-61/TΔ3b: 다중 날짜 발화 = 탐지→세그먼터 1콜→세그먼트 컨텍스트 주입(가장 이른 날짜 활성)")
    void multiDateUtteranceInjectsSegments() {
        segmenter.canned = List.of(
                new UtteranceSegmenter.Segment(LocalDate.of(2026, 7, 15), "7/15 에티오피아 새콤했음"),
                new UtteranceSegmenter.Segment(LocalDate.of(2026, 7, 16), "7/16 케냐 진했음"));

        router.onMessage(message("7/15 에티오피아 새콤했음. 7/16 케냐 진했음"));

        // 탐지 날짜 집합이 세그먼터에 그대로 전달된다(ADR-60·61 탐지기 공유).
        assertThat(segmenter.calls).isEqualTo(1);
        assertThat(segmenter.lastUtterance).isEqualTo("7/15 에티오피아 새콤했음. 7/16 케냐 진했음");
        assertThat(segmenter.lastDates).containsExactly(LocalDate.of(2026, 7, 15), LocalDate.of(2026, 7, 16));
        // 세그먼트가 턴 컨텍스트에 실리고(가장 이른 날짜 활성) 턴은 정상 진행된다.
        assertThat(agentClient.calls).isEqualTo(1);
        assertThat(agentClient.lastContext.instructions())
                .contains("다중 날짜 자동 분해 세그먼트")
                .contains("\"active_date\":\"2026-07-15\"")
                .contains("7/16 케냐 진했음");
    }

    @Test
    @DisplayName("AC-Δ2/ADR-61: 세그먼터 실패 = 주입 없이 턴 정상 진행 — 턴 폴백 아님(뭉뚱그림은 게이트 V-16이 방어)")
    void segmenterFailureProceedsWithoutInjection() {
        segmenter.failure = new IllegalStateException("세그먼터 응답 스키마 위반");

        router.onMessage(message("7/15 에티오피아 새콤했음. 7/16 케냐 진했음"));

        assertThat(segmenter.calls).isEqualTo(1);
        assertThat(agentClient.calls).isEqualTo(1);                 // 기록 흐름이 막히지 않는다
        assertThat(responder.posted).containsExactly(agentClient.reply); // AGENT_TURN_FAILED 아님
        // 주입 블록 bullet 마커로 부재 단언 — 프롬프트 정책 문구의 "세그먼트"(TΔ3d)와 구분한다.
        assertThat(agentClient.lastContext.instructions()).doesNotContain("- 다중 날짜 자동 분해 세그먼트(");
    }

    @Test
    @DisplayName("ADR-61: 단일 날짜(탐지 2개 미만) 발화는 무개입 — 세그먼터 미호출, 컨텍스트 무변화")
    void singleDateUtteranceSkipsSegmenter() {
        router.onMessage(message("7/16에 마신 케냐 진했어"));

        assertThat(segmenter.calls).isZero();
        // 주입 블록 bullet 마커로 부재 단언 — 프롬프트 정책 문구의 "세그먼트"(TΔ3d)와 구분한다.
        assertThat(agentClient.lastContext.instructions()).doesNotContain("- 다중 날짜 자동 분해 세그먼트(");
    }

    // ---- 헬퍼 ----

    private static IncomingMessage message(String text) {
        return new IncomingMessage(USER, CHANNEL, text, "1720000000.000123");
    }

    private static IncomingAction action(String actionId) {
        return new IncomingAction(USER, CHANNEL, actionId);
    }

    // vision 지원 포맷의 최소 매직바이트 — 스테이징 입구 게이트(ADR-29) 통과용.
    private static byte[] jpegBytes() {
        return new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0, 0, 0, 0, 0};
    }

    private void bufferPhoto(String stagedName) {
        photoBufferStore.put(USER, new PhotoBuffer(OffsetDateTime.now(clock), List.of(stagedName)));
        photoStore.staged.put(USER, List.of(new StagedImage(stagedName, new byte[]{1})));
    }

    private static PendingNote pendingNote() {
        OffsetDateTime at = OffsetDateTime.parse("2026-07-16T10:00:00+09:00");
        Entry entry = new Entry(LocalDate.of(2026, 7, 16),
                List.of(new Brew(null, new Tasting("새콤하고 좋았음", null, Rating.GOOD))), at);
        Note draft = new Note("2026-07-16-102030", Sourced.user("커피베라 예가체프 G1"), Sourced.user("커피베라"),
                List.of(), null, null, List.of(), List.of(entry), at, at);
        return new PendingNote(draft, MatchInfo.newNote(), "1720000000.000789", at);
    }

    // ---- fakes (모듈 CLAUDE.md §5.2 — 외부 의존은 인터페이스 stub/fake) ----

    /** 턴 입력을 캡처하고 지정된 응답·실패를 돌려주는 fake 루프 드라이버 — LLM 미접촉. */
    private static final class FakeAgentClient implements AgentClient {
        String reply = "네 멍!";
        RuntimeException failure;
        Runnable onRun;
        int calls;
        TurnPrompt lastContext;
        List<ToolCallback> lastTools;

        @Override
        public String runTurn(TurnPrompt context, List<ToolCallback> tools) {
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
            throw new UnsupportedOperationException("버튼 소진은 SlackCommitHandler의 몫(TΔ8a)");
        }
    }

    /** 버튼 분기만 캡처하는 커밋 핸들러 스텁 — 커밋 체인 자체는 SlackCommitHandlerTest가 본다(TΔ8a). */
    private static final class CapturingCommitHandler extends SlackCommitHandler {
        final List<IncomingAction> saves = new ArrayList<>();
        final List<IncomingAction> cancels = new ArrayList<>();

        CapturingCommitHandler() {
            super(null, null, null, null, null, null); // 캡처 전용 — 실 협력자 미접촉
        }

        @Override
        void confirmSave(IncomingAction action) {
            saves.add(action);
        }

        @Override
        void cancel(IncomingAction action) {
            cancels.add(action);
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
            staged.computeIfAbsent(userId, k -> new ArrayList<>()).add(new StagedImage(filename, bytes));
            return filename;
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

    /** 분리 결과·실패를 지정하는 fake 세그먼터 — LLM 미접촉, 호출 입력을 캡처한다(ADR-61 배선 단언용). */
    private static final class FakeSegmenter implements UtteranceSegmenter {
        List<Segment> canned = List.of();
        RuntimeException failure;
        int calls;
        String lastUtterance;
        Collection<LocalDate> lastDates;

        @Override
        public List<Segment> segment(String utterance, Collection<LocalDate> dates) {
            calls++;
            lastUtterance = utterance;
            lastDates = dates;
            if (failure != null) {
                throw failure;
            }
            return canned;
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
