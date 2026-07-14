package com.devwuu.mocha.slack;

import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.PendingNote;
import com.devwuu.mocha.domain.Rating;
import com.devwuu.mocha.json.MochaObjectMapper;
import com.devwuu.mocha.llm.LlmClient;
import com.devwuu.mocha.llm.LlmRequest;
import com.devwuu.mocha.llm.SearchClient;
import com.devwuu.mocha.llm.SearchQuery;
import com.devwuu.mocha.llm.SearchResult;
import com.devwuu.mocha.llm.VisionClient;
import com.devwuu.mocha.llm.VisionExtraction;
import com.devwuu.mocha.llm.VisionHint;
import com.devwuu.mocha.pipeline.ContextHint;
import com.devwuu.mocha.pipeline.ExtractionResult;
import com.devwuu.mocha.pipeline.IntentClassifier;
import com.devwuu.mocha.pipeline.IntentResult;
import com.devwuu.mocha.pipeline.MessageIntent;
import com.devwuu.mocha.pipeline.AliasGenerator;
import com.devwuu.mocha.pipeline.NoteEnricher;
import com.devwuu.mocha.pipeline.NoteExtractor;
import com.devwuu.mocha.pipeline.NoteMatcher;
import com.devwuu.mocha.pipeline.NoteSearchService;
import com.devwuu.mocha.pipeline.PendingReviser;
import com.devwuu.mocha.pipeline.PhotoInfoExtractor;
import com.devwuu.mocha.render.NoteRenderer;
import com.devwuu.mocha.repository.InMemorySearchSessionStore;
import com.devwuu.mocha.repository.InMemoryTransitionSlot;
import com.devwuu.mocha.repository.JsonFileNoteRepository;
import com.devwuu.mocha.repository.JsonFilePendingStore;
import com.devwuu.mocha.repository.JsonFilePhotoBufferStore;
import com.devwuu.mocha.repository.LocalPhotoStore;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TΔ8(changes/0013): UNCHANGED 불변 회귀 가드 — 구현 변경 없음(AC-Δ7).
 * <p>0013이 사진 수신 경로(포맷 게이트 TΔ2·HEIC 대체 TΔ3)·slug 형식(TΔ1)·flow 구조(TΔ6·TΔ7)를 한꺼번에
 * 흔들었으므로, 그 축들이 전부 겹치는 "사진 포함 신규 기록" 한 흐름을 실배선(stub 게이트 → 실제
 * {@link DefaultConversationRouter} → 실제 {@link SlackConversationFlows} → 실제
 * {@link LocalPhotoStore}·{@link JsonFileNoteRepository}·{@link JsonFilePhotoBufferStore})으로 관통해
 * 층을 가로지르는 불변식만 단언한다.
 * <ul>
 *   <li>① vision 1콜 멀티이미지 배치(ADR-23)·{@code max-images} 절삭이 입구 게이트 도입 후에도 불변 —
 *       절삭은 OCR 호출만, 첨부 저장은 전부. 새 slug 형식 하에서도 {@code photos/<slug>/<date>/}·
 *       {@code cards/<slug>/<date>.jpg} 규약 불변, [저장] 버튼 전 노트·사진 미커밋(ADR-3).</li>
 *   <li>② [취소]는 노트·사진 어느 산출도 디스크에 남기지 않는다 — 스테이징·버퍼 실파일 폐기(FR-10).</li>
 * </ul>
 * 나머지 AC-Δ7 항목은 기존 테스트가 이미 보므로 재복제하지 않는다: 버퍼 슬라이딩 윈도우 규칙
 * ({@code SlackConversationFlowsTest} AC-8 3건), max-images 단위 절삭({@code PhotoInfoExtractorTest}),
 * slug 형식·같은 초 충돌 -2 접미({@code SlackConversationFlowsTest} AC-Δ1/V-2), coffee_name 불변 V-9
 * ({@code PendingReviserTest}·{@code SlackConversationFlowsTest} AC-38), source 우선순위 V-6
 * ({@code NoteEnricherTest} AC-3·AC-Δ3), 자연어 저장/취소 차단({@code Change0011RegressionGuardTest}),
 * edit 커밋·취소 원본 무변화({@code Change0012RegressionGuardTest}).
 */
class Change0013RegressionGuardTest {

    /** 판정 결과를 테스트가 지정하는 stub 게이트 — LLM 미접촉(CLAUDE.md §5.2). */
    private static final class StubIntentClassifier extends IntentClassifier {
        MessageIntent canned = MessageIntent.OTHER;

        StubIntentClassifier() {
            super(null, null); // LlmClient·ObjectMapper 미사용 — classify를 통째로 대체한다.
        }

        @Override
        public IntentResult classify(String message, ContextHint contextHint) {
            return new IntentResult(canned);
        }
    }

    /** 추출 응답만 필요한 record 경로 전용 fake LLM(CLAUDE.md §5.3). */
    private static final class FakeLlmClient implements LlmClient {
        ExtractionResult canned;

        @SuppressWarnings("unchecked")
        @Override
        public <T> T complete(LlmRequest<T> request) {
            return (T) canned;
        }
    }

    /** 검색 보강은 무결과 고정 — 이 가드의 관심사가 아니다. */
    private static final class FakeSearchClient implements SearchClient {
        @Override
        public SearchResult search(SearchQuery query) {
            return SearchResult.empty();
        }
    }

    /** 호출 횟수·전달 이미지 수를 기록하는 fake vision — 1콜 배치·상한 절삭 단언의 재료. */
    private static final class FakeVisionClient implements VisionClient {
        int calls = 0;
        List<String> lastImageUrls = List.of();

        @Override
        public VisionExtraction read(List<String> imageUrls, VisionHint hint) {
            calls++;
            lastImageUrls = imageUrls;
            return VisionExtraction.empty();
        }
    }

    /** 안내 메시지·카드 배달·버튼 소진 호출을 캡처하는 fake. */
    private static final class FakeSlackResponder implements SlackResponder {
        final List<String> messages = new ArrayList<>();
        final List<Path> images = new ArrayList<>();
        final List<String> finalizeStatuses = new ArrayList<>();

        @Override
        public void post(String channelId, String text) {
            messages.add(text);
        }

        @Override
        public void postImage(String channelId, Path imagePath, String caption) {
            images.add(imagePath);
        }

        @Override
        public void finalizePreview(String channelId, PendingNote pending, String statusText) {
            finalizeStatuses.add(statusText);
        }
    }

    /** 증분 렌더 호출(slug/date)을 캡처하고 카드 경로 규약대로 돌려주는 fake — 실산출은 렌더러 테스트가 본다. */
    private static final class CapturingNoteRenderer implements NoteRenderer {
        final List<String> entryCards = new ArrayList<>();

        @Override
        public void renderAll() {
        }

        @Override
        public Path renderEntryCard(String slug, LocalDate date) {
            entryCards.add(slug + "/" + date);
            return Path.of("cards", slug, date + ".jpg");
        }

        @Override
        public void removeEntryCard(String slug, LocalDate date) {
        }
    }

    /** 발행된 pending을 캡처하고 preview_ts를 돌려주는 미리보기 어댑터 스텁(Slack 미접촉). */
    private static final class CapturingPreviewMessenger extends PreviewMessenger {
        PendingNote published;
        final String ts = "1720000000.000123";

        CapturingPreviewMessenger() {
            super(new PreviewBlocks(), null);
        }

        @Override
        public String publish(String channelId, PendingNote pending) {
            this.published = pending;
            return ts;
        }
    }

    @TempDir
    Path dataDir;

    @TempDir
    Path artifactDir;

    // 시간 고정 — Asia/Seoul 2026-07-11 11:00:00 → target_date=2026-07-11, slug 시각 접미=110000.
    // pending TTL은 넉넉히(실 store는 시스템 시계) 고정 시각 createdAt이 만료로 새지 않게 한다.
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Duration PENDING_TTL = Duration.ofDays(365);
    private static final Duration BUFFER_WINDOW = Duration.ofMinutes(10);
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-11T02:00:00Z"), SEOUL);

    private final ObjectMapper mapper = MochaObjectMapper.create();
    private final StubIntentClassifier intentClassifier = new StubIntentClassifier();
    private final FakeLlmClient llmClient = new FakeLlmClient();
    private final FakeVisionClient visionClient = new FakeVisionClient();
    private final FakeSlackResponder responder = new FakeSlackResponder();
    private final CapturingNoteRenderer renderer = new CapturingNoteRenderer();
    private final CapturingPreviewMessenger previewMessenger = new CapturingPreviewMessenger();
    private final InMemorySearchSessionStore searchSessionStore = new InMemorySearchSessionStore(Duration.ofHours(1));
    private final InMemoryTransitionSlot transitionSlot = new InMemoryTransitionSlot();

    private JsonFilePendingStore pendingStore;
    private JsonFileNoteRepository noteRepository;
    private JsonFilePhotoBufferStore photoBufferStore;
    private DefaultConversationRouter router;

    @BeforeEach
    void wireRealRouterFlowAndStores() {
        pendingStore = new JsonFilePendingStore(dataDir, mapper, PENDING_TTL);
        noteRepository = new JsonFileNoteRepository(dataDir, mapper);
        photoBufferStore = new JsonFilePhotoBufferStore(dataDir, mapper);
        SlackConversationFlows flow = new SlackConversationFlows(
                pendingStore, noteRepository, renderer, responder,
                new NoteExtractor(llmClient, mapper), new NoteMatcher(), new NoteEnricher(new FakeSearchClient()), new AliasGenerator(llmClient, mapper),
                new PhotoInfoExtractor(visionClient, 4),
                new PendingReviser(llmClient, mapper), previewMessenger,
                urlPrivate -> jpegBytes(), new LocalPhotoStore(dataDir), photoBufferStore,
                searchSessionStore, new NoteSearchService(llmClient, mapper, 0),
                transitionSlot, artifactDir, BUFFER_WINDOW, clock);
        router = new DefaultConversationRouter(pendingStore, searchSessionStore, intentClassifier, flow);
    }

    // vision 지원 포맷의 최소 매직바이트 — 스테이징 입구 게이트(ADR-29)를 통과하는 정상 JPEG.
    private static byte[] jpegBytes() {
        return new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0, 0, 0, 0, 0, 0, 0};
    }

    private static IncomingMessage message(String text) {
        return new IncomingMessage("U1", "C1", text, "1720000100.000001");
    }

    private static IncomingAction action(String actionId) {
        return new IncomingAction("U1", "C1", actionId, "confirm", "1720000000.000999");
    }

    private static IncomingMedia media(String... filenames) {
        List<IncomingPhoto> photos = new ArrayList<>();
        for (String f : filenames) {
            photos.add(new IncomingPhoto("https://slack/" + f, f, "image/jpeg", List.of()));
        }
        return new IncomingMedia("U1", "C1", photos, "1720000100.000002");
    }

    private static ExtractionResult extraction() {
        return new ExtractionResult("커피베라 예가체프", "커피베라", null, null, null,
                "새콤하고 좋았음", "새콤하고 좋았다", Rating.GOOD, null, null, false, null);
    }

    private Path stagingDir() {
        return dataDir.resolve("photos").resolve(".staging").resolve("U1");
    }

    private static long countFiles(Path dir) {
        if (!Files.isDirectory(dir)) {
            return 0;
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.count();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // --- ① 사진 6장 신규 기록 관통 — 배치·상한·경로 규약·버튼 전용 커밋 불변 ---

    @Test
    @DisplayName("AC-Δ7: 사진 6장 기록 관통 — vision 1콜·max-images=4 절삭, 새 slug 하 photos/<slug>/<date>/·cards/<slug>/<date>.jpg 규약, [저장] 전 미커밋")
    void photoRecordPipelineInvariantsSurviveChange0013() throws java.io.IOException {
        router.onMedia(media("a.jpg", "b.jpg", "c.jpg", "d.jpg", "e.jpg", "f.jpg"));

        assertEquals(6, countFiles(stagingDir()), "수신 사진 6장이 전부 스테이징된다(입구 게이트 통과, AC-46 정상 경로)");

        intentClassifier.canned = MessageIntent.RECORD;
        llmClient.canned = extraction();
        router.onMessage(message("커피베라 예가체프 마셨는데 새콤하고 좋았다"));

        assertEquals(1, visionClient.calls, "여러 장이어도 vision 호출은 1콜 배치(ADR-23 불변)");
        assertEquals(4, visionClient.lastImageUrls.size(), "OCR 전달은 max-images=4 상한 절삭 불변");
        assertTrue(noteRepository.findAll().isEmpty(), "미리보기 단계는 노트 JSON을 만들지 않는다(ADR-3/AC-4 불변)");
        assertEquals(6, countFiles(stagingDir()), "[저장] 전 사진은 스테이징에만 있다");

        router.onAction(action(DefaultConversationRouter.ACTION_SAVE));

        Note saved = noteRepository.findAll().get(0);
        assertEquals("2026-07-11-110000", saved.slug(), "slug = 최초 기록일 + 생성 시각(ADR-28)");
        // 사진은 아카이브 전용(changes/0014 ADR-32) — OCR 절삭(4장)은 호출만, 저장은 6장 전부 폴더로 이동한다.
        // 노트 JSON에는 사진을 기록하지 않는다(AC-Δ1) — 경로 규약 photos/<slug>/<date>/ 불변은 디스크로 검증.
        Path committedDir = dataDir.resolve("photos").resolve("2026-07-11-110000").resolve("2026-07-11");
        assertEquals(6, countFiles(committedDir), "실제 디스크에도 photos/<slug>/<date>/로 6장 이동(첨부 저장은 6장 전부)");
        String noteJson = Files.readString(dataDir.resolve("notes").resolve("2026-07-11-110000.json"));
        assertFalse(noteJson.contains("photos"), "노트 JSON에 photos 키가 없다(사진은 아카이브 전용, AC-Δ1)");
        assertEquals(0, countFiles(stagingDir()), "커밋 후 스테이징 잔존물 없음");
        assertTrue(photoBufferStore.get("U1").isEmpty(), "커밋 후 버퍼도 비워진다");
        assertEquals(List.of("2026-07-11-110000/2026-07-11"), renderer.entryCards,
                "증분 카드 렌더 1회 — (slug, date) 인자 규약 불변");
        assertEquals(List.of(Path.of("cards", "2026-07-11-110000", "2026-07-11.jpg")), responder.images,
                "배달 카드 경로 cards/<slug>/<date>.jpg 불변(AC-17)");
        assertEquals(List.of(FlowMessages.FINALIZE_SAVED), responder.finalizeStatuses, "버튼 1회 소진 불변");
    }

    // --- ② [취소]는 노트·사진 어느 산출도 남기지 않는다 ---

    @Test
    @DisplayName("AC-Δ7/ADR-3: 사진 포함 기록 [취소] → 노트 미생성 + 스테이징·버퍼 실파일 폐기(FR-10) + 파생물 무접촉")
    void cancelLeavesNoNoteAndNoPhotoArtifacts() {
        router.onMedia(media("a.jpg", "b.jpg"));
        intentClassifier.canned = MessageIntent.RECORD;
        llmClient.canned = extraction();
        router.onMessage(message("커피베라 예가체프 마셨는데 새콤하고 좋았다"));

        router.onAction(action(DefaultConversationRouter.ACTION_CANCEL));

        assertTrue(noteRepository.findAll().isEmpty(), "[취소]는 노트를 커밋하지 않는다(ADR-3 불변)");
        assertEquals(0, countFiles(stagingDir()), "스테이징 사진 폐기(FR-10)");
        assertFalse(Files.exists(dataDir.resolve("photos").resolve("2026-07-11-110000")),
                "노트 사진 트리(photos/<slug>/)로 아무것도 새지 않는다");
        assertTrue(photoBufferStore.get("U1").isEmpty(), "버퍼 폐기");
        assertTrue(pendingStore.get("U1").isEmpty(), "pending 폐기");
        assertTrue(renderer.entryCards.isEmpty() && responder.images.isEmpty(),
                "[취소]는 카드 렌더·배달 어느 파생물도 만들지 않는다");
        assertEquals(List.of(FlowMessages.FINALIZE_CANCELED), responder.finalizeStatuses, "버튼 1회 소진");
    }
}
