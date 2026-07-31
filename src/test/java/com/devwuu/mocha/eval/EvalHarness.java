package com.devwuu.mocha.eval;

import com.devwuu.mocha.agent.OpenAiChatClient;
import com.devwuu.mocha.agent.conversation.FoldingChatMemory;
import com.devwuu.mocha.agent.prompt.TurnPromptAssembler;
import com.devwuu.mocha.agent.tool.ToolCallbackProvider;
import com.devwuu.mocha.domain.PendingNote;
import com.devwuu.mocha.json.MochaObjectMapper;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.llm.OpenAiUtteranceSegmenter;
import com.devwuu.mocha.repository.JpaNoteRepository;
import com.devwuu.mocha.repository.JpaPendingStore;
import com.devwuu.mocha.repository.jpa.PendingNoteEntityRepository;
import com.devwuu.mocha.slack.AgentConversationRouter;
import com.devwuu.mocha.slack.inbound.IncomingMessage;
import com.devwuu.mocha.slack.inbound.SlackPhotoIntake;
import com.devwuu.mocha.slack.outbound.MochaMessages;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static com.devwuu.mocha.agent.tool.ToolCallbackProviderFixture.toolkit;

/**
 * eval 러너의 조립·실행부 — 케이스 1건을 <b>실 LLM으로 1회</b> 리플레이하고 사후 상태를 캡처한다
 * (ref: plan.md#ADR-68, changes/0026 TΔ2).
 *
 * <p>진입점은 {@link AgentConversationRouter#onMessage} — 루프가 아니라 라우터다. 탐지기 → 세그먼터 →
 * 게이트 전처리가 0021의 실측 실패 지점이라 루프 레벨 진입으로는 그 구간이 통째로 빠진다(ADR-68).
 *
 * <p>조립 원칙은 "판정 대상은 실물, 부수효과만 대체"다. 저장소는 인메모리 Map이 아니라 실 구현체를 쓴다 —
 * 직렬화·영속 왕복 자체가 검증에 포함되기 때문이다(백엔드 CLAUDE.md §5.2). 노트({@link JpaNoteRepository})도
 * pending({@link JpaPendingStore})도 로컬 Postgres의 {@code test} 스키마에 산다(changes/0028 TΔ10a·TΔ8).
 * 나머지 대체물은 {@link EvalFakes} 참조.
 *
 * <p><b>호출부 계약</b>: 두 저장소의 협력자는 주입받는다 — 스키마 초기화(회차 간 격리)와 Spring 컨텍스트
 * 소유는 {@link EvalCaseRunnerTest}의 몫이다. 회차마다 스키마가 새로 깔리므로 {@code BIGSERIAL}이 1부터
 * 다시 발급되고, 케이스가 심는 노트의 id는 <b>픽스처 순서로 결정된다</b>(재현성 — 구 slug 고정의 승계).
 * pending도 같은 clean으로 함께 비워진다 — 파일 시절 회차별 {@code @TempDir}이 지던 격리의 승계다.
 *
 * <p>턴 실패는 예외로 오지 않는다 — {@code onMessage}가 삼키고 재요청 안내를 보낸다(ADR-48). 그래서
 * 판정은 전부 사후 상태({@link Run})로 하고, 폴백 여부만 응답 텍스트와 {@link MochaMessages#AGENT_TURN_FAILED}의
 * <b>상수 동일성</b>으로 가른다 — 모델 출력 문구 단언이 아니다(findings-TΔ0 §1.1).
 */
final class EvalHarness {

    // 케이스는 단일 사용자 전제(NFR-6)를 그대로 따른다 — 사용자·채널 id는 판정에 쓰이지 않는 고정값.
    private static final String USER = "U-eval";
    private static final String CHANNEL = "C-eval";
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul"); // V-3 — 프로덕션 Clock 빈과 동일 존

    private EvalHarness() {
    }

    /**
     * 실행 파라미터 — 전부 프로덕션 기본값(application.yaml)을 그대로 복제한다.
     * <p>모델·상한이 프로덕션과 다르면 케이스가 재는 것이 프로덕션 행동이 아니게 된다. 스모크 관례처럼
     * 위치 인자로 하드코딩하되, 값의 출처를 여기 한 곳에 모아 드리프트를 눈에 보이게 둔다.
     */
    record Settings(String apiKey, String agentModel, String segmenterModel) {

        static final int MAX_TOOL_CALLS = 8;          // mocha.agent.max-tool-calls
        static final int MAX_TURN_TOKENS = 100_000;   // mocha.agent.max-turn-tokens
        static final Duration TURN_TIMEOUT = Duration.ofSeconds(60);   // mocha.agent.turn-timeout
        // mocha.agent.max-output-tokens (changes/0027 TΔ6). 재조정 트리거(plan §5 — 절단 관측 시 상향)가
        // 발동하면 yaml·AgentConfig와 **함께** 이 값과 스모크 2건의 위치 인자도 옮긴다 — 한쪽만 올리면
        // AC-Δ12의 전/후 측정이 프로덕션 행동을 재지 못한다.
        static final int MAX_OUTPUT_TOKENS = 4_000;
        static final Duration PENDING_TTL = Duration.ofHours(24);      // mocha.pending.ttl
        static final int TRANSCRIPT_MAX_TURNS = 20;                    // mocha.agent.transcript-max-turns
        static final Duration TRANSCRIPT_TTL = Duration.ofHours(1);    // mocha.agent.transcript-ttl
        static final Duration PHOTO_BUFFER_WINDOW = Duration.ofMinutes(10); // mocha.photo.buffer-window
    }

    /**
     * 1회 실행의 사후 상태 — 판정({@link EvalJudge})과 실패 출력(AC-Δ4)의 유일한 입력.
     *
     * @param tools             발화 시퀀스 전체에 걸쳐 기록된 tool 호출(순서 유지).
     * @param pendingBefore     턴 진입 전 {@code pending_note} 행 원문. 없었으면 empty.
     * @param pendingAfter      마지막 턴 종료 후 행 원문. 바이트 비교로 "무변화"를 가른다.
     * @param pendingNote       마지막 턴 종료 후 역직렬화된 pending. 없으면 null.
     * @param notesBefore       진입 전 노트 스냅샷(id → 직렬화 본문).
     * @param notesAfter        종료 후 노트 스냅샷 — 커밋은 [저장] 버튼에서만 일어나므로(AC-59) 무변화가 기대다.
     * @param replies           턴별 최종 응답 텍스트(발화 시퀀스와 같은 길이).
     * @param elapsedMs         전 발화 소요(비용·시간 실측용 — TΔ3 baseline 입력).
     */
    record Run(
            List<RecordingToolCallbacks.Invocation> tools,
            Optional<String> pendingBefore,
            Optional<String> pendingAfter,
            PendingNote pendingNote,
            Map<String, String> notesBefore,
            Map<String, String> notesAfter,
            List<String> replies,
            long elapsedMs
    ) {

        /** 폴백으로 끝난 턴 — 모델 출력이 아니라 프로덕션 상수와의 동일성 비교다(문구 단언 아님). */
        List<Integer> fallbackTurns() {
            return java.util.stream.IntStream.range(0, replies.size())
                    .filter(i -> MochaMessages.AGENT_TURN_FAILED.equals(replies.get(i)))
                    .boxed()
                    .toList();
        }

        long rejectionCount() {
            return tools.stream().filter(RecordingToolCallbacks.Invocation::rejected).count();
        }
    }

    /**
     * 케이스를 1회 실행한다. 노트·pending 스키마는 호출부가 회차마다 새로 만들므로 회차 간 상태가 섞이지 않는다.
     *
     * @param evalCase       실행할 케이스.
     * @param workDir        이 회차 전용 작업 디렉터리(빈 디렉터리 — 호출부가 회차마다 새로 판다).
     *                       사진·카드 등 <b>파일로 남는 것</b>만 여기 떨어진다(pending은 TΔ8에서 DB로 갔다).
     * @param settings       실행 파라미터.
     * @param noteRepository 빈 {@code test} 스키마에 붙은 실 저장소 — 호출부가 회차마다 스키마를 새로 깔아 준다.
     * @param pendings       같은 스키마의 {@code pending_note} 행 접근 — 케이스 픽스처 적재와 원문 스냅샷에 쓴다.
     */
    static Run run(EvalCase evalCase, Path workDir, Settings settings, JpaNoteRepository noteRepository,
                   PendingNoteEntityRepository pendings) throws IOException {
        // pending이 DB로 간 뒤(TΔ8) 이 회차가 파일로 남기는 것은 카드뿐이다 — data/는 더 이상 만들지 않는다.
        Path artifactDir = workDir.resolve("artifact");
        Files.createDirectories(artifactDir);

        // 케이스 today는 instant까지 고정한다 — 턴 타임스탬프·TTL 판정이 시각에서 파생돼 날짜만 고정하면
        // 회차마다 흔들린다(findings-TΔ0 §1.1). 라우터의 시계 고정점 2종이 이 하나에서 나온다.
        Clock clock = Clock.fixed(evalCase.today().toInstant(), SEOUL);
        JsonMapper mapper = MochaObjectMapper.create();
        // 케이스 시계로 조립한다 — 빈(프로덕션 Clock)을 쓰면 TTL 판정이 실시간에 걸려 회차마다 흔들린다.
        JpaPendingStore pendingStore = new JpaPendingStore(pendings, mapper, Settings.PENDING_TTL, clock);
        loadFixtures(evalCase, noteRepository, pendingStore, mapper);
        FoldingChatMemory transcript =
                new FoldingChatMemory(Settings.TRANSCRIPT_MAX_TURNS, Settings.TRANSCRIPT_TTL, clock);
        EvalFakes.CapturingResponder responder = new EvalFakes.CapturingResponder();

        ToolCallbackProvider toolkit = toolkit()
                .noteRepository(noteRepository)
                .noteRenderer(new EvalFakes.NoOpNoteRenderer())
                .responder(responder)
                .artifactDir(artifactDir)
                .mapper(mapper)
                .pendingStore(pendingStore)
                .previewMessenger(new EvalFakes.StubPreviewMessenger())
                .transcript(transcript)
                .clock(clock)  // 검증기 2종은 프로덕션(RouterConfig)과 같은 조합으로 파생된다
                .build();
        RecordingToolCallbacks recorder = new RecordingToolCallbacks(toolkit, mapper);

        OpenAIClient openAi = OpenAIOkHttpClient.builder().apiKey(settings.apiKey()).build();
        SlackPhotoIntake photoIntake = new SlackPhotoIntake(pendingStore, responder,
                url -> new byte[0], new EvalFakes.EmptyPhotoStore(), new EvalFakes.EmptyPhotoBufferStore(),
                new EvalFakes.NoOpPhotoInfoExtractor(), Settings.PHOTO_BUFFER_WINDOW, clock);

        AgentConversationRouter router = new AgentConversationRouter(
                pendingStore,
                transcript,
                // 드라이버 시계만 실시간 — 케이스 날짜는 고정(clock)이지만 턴 경과·요청 잔여 예산(ADR-70 ①)은
                // 실제 소요를 재야 상한이 실효한다. millis만 쓰므로 존은 무관하다.
                new OpenAiChatClient(openAi, settings.agentModel(), Settings.MAX_TOOL_CALLS,
                        Settings.MAX_TURN_TOKENS, Settings.TURN_TIMEOUT, Settings.MAX_OUTPUT_TOKENS,
                        mapper, Clock.systemUTC()),
                recorder,
                new TurnPromptAssembler(mapper, clock),
                new OpenAiUtteranceSegmenter(openAi, settings.segmenterModel(), mapper),
                photoIntake,
                responder,
                null, // 커밋 핸들러는 onMessage 경로가 접촉하지 않는다 — null이 "미접촉"의 정직한 신호(픽스처 관례)
                clock);

        // pending 원문은 store.get() 전에, 그리고 저장소를 거치지 않고 행에서 직접 뜬다 — 훼손분은 get이
        // 행을 지우므로(ADR-66 ②) 나중에 뜨면 "초기 상태가 있었다"는 사실 자체가 사라진다.
        Optional<String> pendingBefore = snapshotPending(pendings);
        Map<String, String> notesBefore = snapshotNotes(noteRepository, mapper);

        long startedAt = System.nanoTime();
        for (String utterance : evalCase.utterances()) {
            // 멀티턴은 같은 라우터 인스턴스에 순차 주입 — 턴 사이 문맥은 트랜스크립트·pending이 잇는다.
            router.onMessage(new IncomingMessage(USER, CHANNEL, utterance));
        }
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        return new Run(
                recorder.invocations(),
                pendingBefore,
                snapshotPending(pendings),
                pendingStore.get(USER).orElse(null),
                notesBefore,
                snapshotNotes(noteRepository, mapper),
                List.copyOf(responder.posted),
                elapsedMs);
    }

    // 케이스 동봉 픽스처를 실 저장소로 깐다 — 둘 다 DB다(노트는 INSERT, pending은 put).
    // 노트 파일명 오름차순으로 심으므로 BIGSERIAL이 발급하는 id가 회차마다 같다(재현성). 픽스처 JSON의
    // id 필드는 insert가 무시한다 — 발급자는 DB다(TΔ5a 계약).
    // pending 픽스처는 파일 복사가 아니라 도메인으로 역직렬화해 put한다(TΔ8) — 파일 시절엔 저장 형태와
    // 픽스처 형태가 같아 복사로 끝났지만 이제 형태가 갈린다(draft만 JSONB, 나머지는 컬럼).
    private static void loadFixtures(EvalCase evalCase, JpaNoteRepository noteRepository,
                                     JpaPendingStore pendingStore, JsonMapper mapper) throws IOException {
        EvalCase.Initial initial = evalCase.initial();
        if (initial == null) {
            return;
        }
        if (initial.notes() != null) {
            Path source = evalCase.dir().resolve(initial.notes());
            try (Stream<Path> files = Files.list(source)) {
                for (Path file : files.filter(p -> p.getFileName().toString().endsWith(".json"))
                        .sorted().toList()) {
                    noteRepository.insert(mapper.readValue(
                            Files.readString(file, StandardCharsets.UTF_8), Note.class));
                }
            }
        }
        if (initial.pending() != null) {
            pendingStore.put(USER, mapper.readValue(
                    Files.readString(evalCase.dir().resolve(initial.pending()), StandardCharsets.UTF_8),
                    PendingNote.class));
        }
    }

    // pending 행 원문 — 파일 시절의 "파일 바이트"를 행 전 컬럼의 연결이 승계한다. 저장소를 지나지 않으므로
    // TTL·무결성 필터가 걸리지 않고(스냅샷은 있는 그대로여야 한다) draft는 저장된 JSON 그대로 실린다.
    private static Optional<String> snapshotPending(PendingNoteEntityRepository pendings) {
        return pendings.findById(USER).map(row -> String.join(" | ",
                String.valueOf(row.getMode()),
                String.valueOf(row.getTargetNoteId()),
                String.valueOf(row.getTargetDate()),
                String.valueOf(row.isDateConflict()),
                String.valueOf(row.getMatchType()),
                String.valueOf(row.getMatchNoteId()),
                String.valueOf(row.getMatchDate()),
                String.valueOf(row.getPreviewTs()),
                String.valueOf(row.getCreatedAt()),
                row.getDraft()));
    }

    // 노트 저장소 스냅샷 — 파일 시절의 "파일명 → 내용"을 "id → 직렬화 본문"이 승계한다. 저장소가 id
    // 오름차순을 보장하므로(NoteRepository 계약) 순서는 결정적이고, 직렬화를 거치므로 값 변화도 잡힌다.
    private static Map<String, String> snapshotNotes(JpaNoteRepository noteRepository, JsonMapper mapper) {
        Map<String, String> snapshot = new LinkedHashMap<>();
        for (Note note : noteRepository.findAll()) {
            snapshot.put(String.valueOf(note.id()), mapper.writeValueAsString(note));
        }
        return snapshot;
    }
}
