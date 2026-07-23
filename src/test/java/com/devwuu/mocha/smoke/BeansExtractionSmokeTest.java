package com.devwuu.mocha.smoke;

import com.devwuu.mocha.agent.OpenAiAgentClient;
import com.devwuu.mocha.agent.conversation.ConversationTranscript;
import com.devwuu.mocha.agent.prompt.AgentContextAssembler;
import com.devwuu.mocha.agent.prompt.AgentTurnInput;
import com.devwuu.mocha.agent.tool.AgentToolkit;
import com.devwuu.mocha.agent.tool.validation.EditProposalValidator;
import com.devwuu.mocha.agent.tool.validation.RecordProposalValidator;
import com.devwuu.mocha.agent.turn.TurnUtterance;
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
 * TΔ3a(changes/0021) — 실 OpenAI 호출 스모크(수동, 비용 발생): 블렌드 발화가 시스템 프롬프트의 beans
 * 규칙(ADR-53 — 블렌드 원두별 요소·원두별 process·쉼표 나열 금지)대로 `beans` 원두별 요소로 제안되는지
 * 실 에이전트 루프(propose_record strict schema + 서버 검증)로 관측한다 (ref: spec FR-2/FR-3/AC-64).
 * <p>단언하지 않는다 — 모델 출력이라 판정은 로그로 한다(CLAUDE.md §5.3 관측). 협력자는 인메모리 fake라
 * 파일·Slack 접촉이 없고, pending draft의 beans 배열이 관측 대상이다.
 * 기본 test 제외(@Tag("openai")), 실행은 온디맨드 Test 태스크로만.
 */
@Tag("openai")
class BeansExtractionSmokeTest {

    private static final String USER = "U-smoke";
    private static final String CHANNEL = "C-smoke";

    @Test
    void printsProposedBeansForBlendUtterance() throws Exception {
        String apiKey = resolveApiKey();
        assumeTrue(apiKey != null && !apiKey.isBlank(), "OPENAI_API_KEY 미설정 — 스모크 스킵");

        OpenAIClient client = OpenAIOkHttpClient.builder().apiKey(apiKey).build();
        String model = System.getProperty("mocha.probe.model", "gpt-5.4"); // application.yaml mocha.agent.model
        ObjectMapper mapper = MochaObjectMapper.create();
        Clock clock = Clock.fixed(Instant.parse("2026-07-21T01:00:00Z"), ZoneId.of("Asia/Seoul"));

        InMemoryPendingStore pendingStore = new InMemoryPendingStore();
        AgentToolkit toolkit = new AgentToolkit(new EmptyNoteRepository(), new NoOpRenderer(),
                new PrintingResponder(), Path.of("build/smoke-artifact"), mapper, pendingStore,
                new StubPreviewMessenger(), new RecordProposalValidator(clock), new EditProposalValidator(),
                new ConversationTranscript(20, Duration.ofHours(1), clock), clock);

        // AC-64 수준의 블렌드 발화 — 원두별 가공방식이 갈린다. 기대: beans 요소 2개(에티오피아=워시드,
        // 콜롬비아=내추럴), 원산지 쉼표 나열 ❌.
        String message = "어제 커피리브레 배드 블러드 마셨어. 에티오피아 워시드랑 콜롬비아 내추럴 섞은 블렌드래. "
                + "고소하고 단맛이 좋았음.";
        AgentTurnInput input = new AgentContextAssembler(mapper, clock)
                .assemble(message, List.of(), null, null, null);

        String reply = new OpenAiAgentClient(client, model, 10, 100_000, Duration.ofSeconds(60), mapper)
                .runTurn(input, toolkit.forTurn(USER, CHANNEL, new TurnUtterance(message, null)));

        System.out.println("=== BEANS EXTRACTION SMOKE (TΔ3a, AC-64) model=" + model + " ===");
        System.out.println("입력      = " + message);
        System.out.println("최종 응답 = " + reply);
        Optional<PendingNote> pending = pendingStore.get(USER);
        if (pending.isEmpty()) {
            System.out.println("pending 없음 — 제안 tool 미호출(관측 실패, 프롬프트 재점검 필요)");
        } else {
            System.out.println("draft.beans = " + mapper.writeValueAsString(pending.get().draft().beans())
                    + "   (기대: 원두별 요소 2개·원두별 process, ❌ \"에티오피아, 콜롬비아\" 쉼표 나열)");
        }
        System.out.println("=== END ===");
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
