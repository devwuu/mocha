package com.devwuu.mocha.eval;

import com.devwuu.mocha.agent.OpenAiChatClient;
import com.devwuu.mocha.agent.conversation.FoldingChatMemory;
import com.devwuu.mocha.agent.prompt.TurnPromptAssembler;
import com.devwuu.mocha.agent.tool.ToolCallbackProvider;
import com.devwuu.mocha.agent.turn.TurnDraft;
import com.devwuu.mocha.agent.turn.TurnRunner;
import com.devwuu.mocha.json.MochaObjectMapper;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.llm.OpenAiUtteranceSegmenter;
import com.devwuu.mocha.service.NoteTxService;
import com.devwuu.mocha.service.NoteService;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
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
 * <p>진입점은 {@link TurnRunner#run} — 루프가 아니라 턴이다. 탐지기 → 세그먼터 → 게이트 전처리가 0021의
 * 실측 실패 지점이라 루프 레벨 진입으로는 그 구간이 통째로 빠진다(ADR-68).
 *
 * <p><b>0029 TΔ16에서 진입점이 구 {@code AgentConversationRouter.onMessage}에서 여기로 내려왔다.</b>
 * ADR-69는 케이스 리플레이의 입구가 <i>실사용과 같아야</i> 행동 회귀 측정이 성립한다고 요구하는데,
 * 실사용 입구가 앱({@code POST /api/agent/turn})으로 바뀌었고 그 컨트롤러가 부르는 것이 바로 이 러너다.
 * 라우터를 계속 쓰는 쪽이 오히려 <b>실사용에 없는 경로</b>를 재는 것이 됐다. 러너 위에 남아 있던 Slack
 * 특화(수신 형식·응답 배달·사진 버퍼)는 케이스가 재는 대상이 아니었으므로 판정에 손실이 없다.
 *
 * <p>조립 원칙은 "판정 대상은 실물, 부수효과만 대체"다. 저장소는 인메모리 Map이 아니라 실 구현체를 쓴다 —
 * 직렬화·영속 왕복 자체가 검증에 포함되기 때문이다(백엔드 CLAUDE.md §5.2). 노트({@link NoteTxService})는
 * 로컬 Postgres의 {@code test} 스키마에 산다(changes/0028 TΔ10a).
 * <p><b>대체물이 0개가 됐다</b>(TΔ16) — 구 {@code EvalFakes}는 Slack·렌더·사진 3종을 비워 두는 곳이었는데,
 * Slack은 삭제됐고 렌더·사진은 러너가 협력자로 갖지 않는다(카드는 커밋 경로, 사진은 TΔ8a의 업로드 경로).
 * 남은 협력자는 전부 실물이고, 그 자체가 <i>턴이 외부 부수효과를 갖지 않는다</i>는 사후 확인이다.
 *
 * <p><b>호출부 계약</b>: 노트 저장소는 주입받는다 — 스키마 초기화(회차 간 격리)와 Spring 컨텍스트
 * 소유는 {@link EvalCaseRunnerTest}의 몫이다. 회차마다 스키마가 새로 깔리므로 {@code BIGSERIAL}이 1부터
 * 다시 발급되고, 케이스가 심는 노트의 id는 <b>픽스처 순서로 결정된다</b>(재현성 — 구 slug 고정의 승계).
 *
 * <p>0029 TΔ4: 사후 상태에서 pending이 빠지고 <b>제안 결과</b>가 들어왔다. 서버가 작성 중 데이터를
 * 영속하지 않으므로 뜰 행이 없고, 대신 턴마다 만들어지는 수거함을 {@link RecordingToolCallbacks}가
 * 붙들어 준다.
 *
 * <p>턴 실패는 <b>예외로 온다</b>(ADR-48 — 러너는 삼키지 않고 전송 계층이 형태를 정한다). 하네스는 그것을
 * 잡아 해당 턴의 응답을 {@code null}로 기록하고 다음 발화로 진행한다 — 시퀀스가 중단되면 뒤 턴의 행동을
 * 재지 못한다. <b>0029 TΔ16에서 폴백 판정이 문구 비교에서 벗어났다</b>: 종전에는 응답 텍스트를
 * {@code MochaMessages.AGENT_TURN_FAILED} 상수와 대조했는데(라우터가 안내를 돌려줬으므로), 이제 예외
 * 발생 자체가 신호라 <b>비교할 문구가 아예 없다</b> — findings-TΔ0 §1.1이 상수 동일성으로 우회하려던
 * 자리가 구조적으로 닫혔다.
 */
final class EvalHarness {

    private static final Logger log = LoggerFactory.getLogger(EvalHarness.class);

    // 케이스는 단일 사용자 전제(NFR-6)를 그대로 따른다 — 판정에 쓰이지 않는 고정 키다.
    private static final String USER = "U-eval";
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
        static final int TRANSCRIPT_MAX_TURNS = 20;                    // mocha.agent.transcript-max-turns
        static final Duration TRANSCRIPT_TTL = Duration.ofHours(1);    // mocha.agent.transcript-ttl
        // mocha.photo.buffer-window는 0029 TΔ16에서 소멸했다 — 사진 버퍼가 Slack 배관이었다.
    }

    /**
     * 1회 실행의 사후 상태 — 판정({@link EvalJudge})과 실패 출력(AC-Δ4)의 유일한 입력.
     *
     * @param tools             발화 시퀀스 전체에 걸쳐 기록된 tool 호출(순서 유지).
     * @param proposal          마지막 턴의 제안 결과. 제안이 없었으면 empty.
     * @param notesBefore       진입 전 노트 스냅샷(id → 직렬화 본문).
     * @param notesAfter        종료 후 노트 스냅샷 — 커밋은 사용자 확정에서만 일어나므로(AC-59) 무변화가 기대다.
     * @param replies           턴별 최종 응답 텍스트(발화 시퀀스와 같은 길이). <b>폴백 턴은 null</b>이다 —
     *                          응답이 없었다는 뜻이고, 그 자리에 안내 문구를 넣으면 실패가 정상 응답처럼 보인다.
     * @param elapsedMs         전 발화 소요(비용·시간 실측용 — TΔ3 baseline 입력).
     */
    record Run(
            List<RecordingToolCallbacks.Invocation> tools,
            Optional<TurnDraft> proposal,
            Map<String, String> notesBefore,
            Map<String, String> notesAfter,
            List<String> replies,
            long elapsedMs
    ) {

        /** 폴백으로 끝난 턴 — 응답 부재(= 러너가 예외로 끝낸 턴)가 신호다. 문구 비교가 아니다. */
        List<Integer> fallbackTurns() {
            return java.util.stream.IntStream.range(0, replies.size())
                    .filter(i -> replies.get(i) == null)
                    .boxed()
                    .toList();
        }

        long rejectionCount() {
            return tools.stream().filter(RecordingToolCallbacks.Invocation::rejected).count();
        }
    }

    /**
     * 케이스를 1회 실행한다. 노트 스키마는 호출부가 회차마다 새로 만들므로 회차 간 상태가 섞이지 않는다.
     *
     * @param evalCase       실행할 케이스.
     * @param workDir        이 회차 전용 작업 디렉터리(빈 디렉터리 — 호출부가 회차마다 새로 판다).
     *                       사진·카드 등 <b>파일로 남는 것</b>만 여기 떨어진다.
     * @param settings       실행 파라미터.
     * @param noteRepository 빈 {@code test} 스키마에 붙은 실 저장소 — 호출부가 회차마다 스키마를 새로 깔아 준다.
     */
    static Run run(EvalCase evalCase, Path workDir, Settings settings, NoteTxService noteRepository)
            throws IOException {
        // 이 회차가 파일로 남기는 것은 카드뿐이다 — data/는 더 이상 만들지 않는다.
        Path artifactDir = workDir.resolve("artifact");
        Files.createDirectories(artifactDir);

        // 케이스 today는 instant까지 고정한다 — 턴 타임스탬프·TTL 판정이 시각에서 파생돼 날짜만 고정하면
        // 회차마다 흔들린다(findings-TΔ0 §1.1). 턴의 시계 고정점 2종이 이 하나에서 나온다.
        Clock clock = Clock.fixed(evalCase.today().toInstant(), SEOUL);
        JsonMapper mapper = MochaObjectMapper.create();
        loadFixtures(evalCase, noteRepository, mapper);
        FoldingChatMemory transcript =
                new FoldingChatMemory(Settings.TRANSCRIPT_MAX_TURNS, Settings.TRANSCRIPT_TTL, clock);

        ToolCallbackProvider toolkit = toolkit()
                // 0029 TΔ4a: tool이 잡는 타입이 NoteService다 — 실 저장소를 그 뒤에 세운다.
                // 별칭 생성기는 null이다: 제안 tool은 커밋을 지나지 않으므로 닿을 일이 없다.
                .noteService(new NoteService(noteRepository, null))
                .mapper(mapper)
                .clock(clock)  // 검증기는 프로덕션(TurnConfig)과 같은 조합으로 파생된다
                .build();
        RecordingToolCallbacks recorder = new RecordingToolCallbacks(toolkit, mapper);

        OpenAIClient openAi = OpenAIOkHttpClient.builder().apiKey(settings.apiKey()).build();

        // 0029 TΔ16: 이 러너가 진입점이다 — 실사용 입구(POST /api/agent/turn)가 부르는 것과 같은 객체이고,
        // 케이스 리플레이의 입구가 실사용과 같아야 행동 회귀 측정이 성립한다(ADR-69).
        TurnRunner turnRunner = new TurnRunner(
                transcript,
                // 드라이버 시계만 실시간 — 케이스 날짜는 고정(clock)이지만 턴 경과·요청 잔여 예산(ADR-70 ①)은
                // 실제 소요를 재야 상한이 실효한다. millis만 쓰므로 존은 무관하다.
                new OpenAiChatClient(openAi, settings.agentModel(), Settings.MAX_TOOL_CALLS,
                        Settings.MAX_TURN_TOKENS, Settings.TURN_TIMEOUT, Settings.MAX_OUTPUT_TOKENS,
                        mapper, Clock.systemUTC()),
                recorder,
                new TurnPromptAssembler(mapper, clock),
                new OpenAiUtteranceSegmenter(openAi, settings.segmenterModel(), mapper),
                // 사진 OCR은 케이스 v1의 비범위다(아래 주석) — 사진 없는 턴은 이 협력자를 지나지 않는다.
                null,
                clock);

        Map<String, String> notesBefore = snapshotNotes(noteRepository, mapper);

        // 케이스 v1은 사진 없는 텍스트 턴만 다룬다(ADR-68 비범위) — draft도 null이다. 폼 상태를 동봉하는
        // 케이스(TΔ2 계약)는 TΔ21이 코퍼스와 함께 세운다.
        List<String> replies = new ArrayList<>();
        long startedAt = System.nanoTime();
        for (String utterance : evalCase.utterances()) {
            // 멀티턴은 같은 러너·트랜스크립트에 순차 주입 — 턴 사이 문맥은 트랜스크립트가 잇는다.
            try {
                replies.add(turnRunner.run(USER, utterance, null).reply());
            } catch (RuntimeException e) {
                // 폴백 턴(ADR-48): 응답 없음으로 기록하고 다음 발화로 간다 — 시퀀스를 여기서 끊으면
                // 뒤 턴의 행동을 재지 못한다. 사유는 프로덕션과 같은 자리(로그)에 남긴다.
                log.warn("eval 턴 폴백(응답 없음): 원문={}", utterance, e);
                replies.add(null);
            }
        }
        long elapsedMs = (System.nanoTime() - startedAt) / 1_000_000;

        return new Run(
                recorder.invocations(),
                recorder.proposal(),
                notesBefore,
                snapshotNotes(noteRepository, mapper),
                // List.copyOf가 아니다 — 폴백 턴의 null을 허용해야 한다(그 자체가 폴백 신호다).
                Collections.unmodifiableList(replies),
                elapsedMs);
    }

    // 케이스 동봉 노트 픽스처를 실 저장소로 깐다. 파일명 오름차순으로 심으므로 BIGSERIAL이 발급하는
    // id가 회차마다 같다(재현성). 픽스처 JSON의 id 필드는 insert가 무시한다 — 발급자는 DB다(TΔ5a 계약).
    private static void loadFixtures(EvalCase evalCase, NoteTxService noteRepository,
                                     JsonMapper mapper) throws IOException {
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
    }

    // 노트 저장소 스냅샷 — 파일 시절의 "파일명 → 내용"을 "id → 직렬화 본문"이 승계한다. 저장소가 id
    // 오름차순을 보장하므로(NoteTxService 계약) 순서는 결정적이고, 직렬화를 거치므로 값 변화도 잡힌다.
    private static Map<String, String> snapshotNotes(NoteTxService noteRepository, JsonMapper mapper) {
        Map<String, String> snapshot = new LinkedHashMap<>();
        for (Note note : noteRepository.findAll()) {
            snapshot.put(String.valueOf(note.id()), mapper.writeValueAsString(note));
        }
        return snapshot;
    }
}
