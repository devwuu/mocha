package com.devwuu.mocha.smoke;

import com.devwuu.mocha.agent.OpenAiAgentClient;
import com.devwuu.mocha.agent.conversation.ConversationTranscript;
import com.devwuu.mocha.agent.prompt.AgentContextAssembler;
import com.devwuu.mocha.agent.prompt.AgentTurnInput;
import com.devwuu.mocha.agent.tool.AgentToolkit;
import com.devwuu.mocha.agent.tool.ProposalValidator;
import com.devwuu.mocha.agent.tool.TurnUtterance;
import com.devwuu.mocha.domain.Aliases;
import com.devwuu.mocha.domain.Entry;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.NoteMeta;
import com.devwuu.mocha.domain.PendingNote;
import com.devwuu.mocha.json.MochaObjectMapper;
import com.devwuu.mocha.render.NoteRenderer;
import com.devwuu.mocha.repository.NoteRepository;
import com.devwuu.mocha.repository.PendingStore;
import com.devwuu.mocha.slack.outbound.PreviewBlocks;
import com.devwuu.mocha.slack.outbound.PreviewMessenger;
import com.devwuu.mocha.slack.outbound.SlackResponder;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;

import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * TΔ3b(changes/0021) — 실 OpenAI 호출 스모크(수동, 비용 발생): 시스템 프롬프트의 회차(brews) 규칙·수치
 * 정규화·다중 날짜 안내(ADR-59, FR-15/18/22)가 실 에이전트 루프에서 지켜지는지 관측한다.
 * <ul>
 *   <li>AC-74·75: {@code ideas/sample.md} 07-18 발화 → 회차 2개, 시도별 레시피·feedback과 맛 감상(tasting) 분리</li>
 *   <li>AC-66·76: grind {@code "210클릭 (매버릭 2.0)"} 형식, "10ml정도"→10·"2분 40초"→160 number 정규화</li>
 *   <li>AC-77: 두 날짜(07-18·07-19)가 섞인 발화 → pending 미생성 + 날짜별 분리 안내</li>
 * </ul>
 * <p>단언하지 않는다 — 모델 출력이라 판정은 로그로 한다(CLAUDE.md §5.3 관측). 협력자는 인메모리 fake라
 * 파일·Slack 접촉이 없고, pending draft의 brews 배열이 관측 대상이다.
 * 기본 test 제외(@Tag("openai")), 실행은 온디맨드 Test 태스크로만.
 */
@Tag("openai")
class BrewRulesSmokeTest {

    private static final String USER = "U-smoke";
    private static final String CHANNEL = "C-smoke";

    /** AC-74·75 — 같은 날 2회 시도 실사용 샘플(07-18 부분) → 회차 2개·감상/피드백 분리 관측. */
    @Test
    void printsBrewSplitForSampleTwoAttemptUtterance() throws Exception {
        // 기대: brews 요소 2개 — 각 회차에 그 시도의 recipe(도징·클릭·추출량)와 feedback(퍽·크레마 관찰·다음 계획),
        //       맛 코멘트(라떼 지방맛·단맛·개맛있다)는 1회차 tasting으로. 마시는 방식(진하게/연하게/라떼)은 회차 분리 ❌.
        runProposalSmoke("BREW SPLIT (AC-74·75)", sampleSection0718());
    }

    /** AC-66·76 — grind 형식·대략 표기 숫자화·총 시간 초 환산 관측. */
    @Test
    void printsRecipeNumericNormalization() throws Exception {
        // 기대: grind="210클릭 (매버릭 2.0)", yield_ml=10(number), time_sec=160(number).
        String message = "오늘 칠복상회 브라운럭 내려 마셨어. 매버릭 2.0으로 갈았는데 210클릭이었어. "
                + "원두 14g 넣고 내렸더니 10ml정도 나왔나? 총 2분 40초 걸렸어. 고소하고 맛있었음.";
        runProposalSmoke("NUMERIC NORMALIZATION (AC-66·76)", message);
    }

    /** AC-77 — 두 날짜가 섞인 발화(sample.md 전체) → pending 미생성 + 분리 안내 관측. */
    @Test
    void printsMultiDateGuidanceWithoutProposal() throws Exception {
        // 기대: pending 없음(제안 tool 미호출) + "한 날짜씩 나눠 보내달라" 취지의 최종 응답.
        runProposalSmoke("MULTI-DATE GUIDANCE (AC-77)", sampleWhole());
    }

    private void runProposalSmoke(String label, String message) throws Exception {
        String apiKey = resolveApiKey();
        assumeTrue(apiKey != null && !apiKey.isBlank(), "OPENAI_API_KEY 미설정 — 스모크 스킵");

        OpenAIClient client = OpenAIOkHttpClient.builder().apiKey(apiKey).build();
        String model = System.getProperty("mocha.probe.model", "gpt-5.4"); // application.yaml mocha.agent.model
        ObjectMapper mapper = MochaObjectMapper.create();
        Clock clock = Clock.fixed(Instant.parse("2026-07-21T01:00:00Z"), ZoneId.of("Asia/Seoul"));

        InMemoryPendingStore pendingStore = new InMemoryPendingStore();
        AgentToolkit toolkit = new AgentToolkit(new EmptyNoteRepository(), new NoOpRenderer(),
                new PrintingResponder(), Path.of("build/smoke-artifact"), mapper, pendingStore,
                new StubPreviewMessenger(), new ProposalValidator(),
                new ConversationTranscript(20, Duration.ofHours(1)), clock);
        AgentTurnInput input = new AgentContextAssembler(mapper, clock)
                .assemble(message, List.of(), null, null, null);

        String reply = new OpenAiAgentClient(client, model, 10, 100_000, Duration.ofSeconds(60), mapper)
                .runTurn(input, toolkit.forTurn(USER, CHANNEL, new TurnUtterance(message, null)));

        System.out.println("=== BREW RULES SMOKE (TΔ3b, " + label + ") model=" + model + " ===");
        System.out.println("입력      = " + message);
        System.out.println("최종 응답 = " + reply);
        Optional<PendingNote> pending = pendingStore.get(USER);
        if (pending.isEmpty()) {
            System.out.println("pending 없음 — 제안 tool 미호출 (다중 날짜 안내 시나리오에서는 기대 동작)");
        } else {
            List<Entry> entries = pending.get().draft().entries();
            Entry latest = entries.get(entries.size() - 1);
            System.out.println("draft.brews(" + latest.brews().size() + "개) = "
                    + mapper.writeValueAsString(latest.brews()));
        }
        System.out.println("=== END ===");
    }

    // AC-74의 "sample.md 07-18 수준" — 실사용 샘플 원문을 그대로 발화로 쓴다(문서가 source of truth).
    private static String sampleSection0718() throws Exception {
        String whole = sampleWhole();
        int cut = whole.indexOf("2026 07 19");
        assumeTrue(cut > 0, "ideas/sample.md 형식 변경 — 07-19 마커 없음");
        return whole.substring(0, cut).strip();
    }

    private static String sampleWhole() throws Exception {
        Path sample = Path.of("ideas/sample.md");
        assumeTrue(Files.exists(sample), "ideas/sample.md 부재 — 스모크 스킵");
        return Files.readString(sample);
    }

    /** 저장 노트 없음 — 커밋 경로(upsert/applyEdit)는 제안 tool이 부르지 않는다(ADR-45). */
    private static final class EmptyNoteRepository implements NoteRepository {
        @Override
        public List<Note> findAll() {
            return List.of();
        }

        @Override
        public Optional<Note> findBySlug(String slug) {
            return Optional.empty();
        }

        @Override
        public String nextAvailableSlug(String base) {
            return base;
        }

        @Override
        public Note upsertEntry(String slug, NoteMeta meta, Entry entry, Aliases aliases) {
            throw new UnsupportedOperationException("스모크는 커밋하지 않는다");
        }

        @Override
        public Note applyEdit(String slug, LocalDate targetDate, Note draft) {
            throw new UnsupportedOperationException("스모크는 커밋하지 않는다");
        }
    }

    private static final class InMemoryPendingStore implements PendingStore {
        private final Map<String, PendingNote> store = new HashMap<>();

        @Override
        public void put(String userId, PendingNote pending) {
            store.put(userId, pending);
        }

        @Override
        public Optional<PendingNote> get(String userId) {
            return Optional.ofNullable(store.get(userId));
        }

        @Override
        public void clear(String userId) {
            store.remove(userId);
        }
    }

    /** Slack 미접촉 — 미리보기 전송을 고정 ts로 대체한다. */
    private static final class StubPreviewMessenger extends PreviewMessenger {
        StubPreviewMessenger() {
            super(new PreviewBlocks(), null);
        }

        @Override
        public String publish(String channelId, PendingNote pending) {
            return "1720000000.000123";
        }
    }

    private static final class PrintingResponder implements SlackResponder {
        @Override
        public void post(String channelId, String text) {
            System.out.println("[responder.post] " + text);
        }

        @Override
        public void postImage(String channelId, Path imagePath, String caption) {
            System.out.println("[responder.postImage] " + imagePath);
        }

        @Override
        public void finalizePreview(String channelId, PendingNote pending, String statusText) {
            // no-op
        }
    }

    private static final class NoOpRenderer implements NoteRenderer {
        @Override
        public void renderAll() {
            // no-op
        }

        @Override
        public List<Path> renderEntryCard(String slug, LocalDate date) {
            return List.of(Path.of("build/smoke-artifact/unused.jpg"));
        }

        @Override
        public void removeEntryCard(String slug, LocalDate date) {
            // no-op
        }
    }

    // .env.local(KEY=VALUE properties)에서 OPENAI_API_KEY를 읽는다. 없으면 환경변수 폴백. 파일 내용은 프로세스만 읽는다.
    private static String resolveApiKey() throws Exception {
        Path envLocal = Path.of(".env.local");
        if (Files.exists(envLocal)) {
            Properties props = new Properties();
            try (InputStream in = Files.newInputStream(envLocal)) {
                props.load(in);
            }
            String key = props.getProperty("OPENAI_API_KEY");
            if (key != null && !key.isBlank()) {
                return key;
            }
        }
        return System.getenv("OPENAI_API_KEY");
    }
}
