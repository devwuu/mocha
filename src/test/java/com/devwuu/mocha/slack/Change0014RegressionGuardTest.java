package com.devwuu.mocha.slack;

import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.PendingNote;
import com.devwuu.mocha.domain.Rating;
import com.devwuu.mocha.domain.Source;
import com.devwuu.mocha.domain.Sourced;
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
 * TΔ6(changes/0014): UNCHANGED 불변 회귀 가드 — 사진 아카이브 전용화는 렌더링·{@code Entry.photos} JSON
 * 필드를 걷어냈을 뿐 <b>수신→검증→스테이징→OCR→커밋</b> 경로는 손대지 않았다(AC-Δ6, "구현 변경 없음").
 * <p>그 "손대지 않음"을 층을 가로질러 한 흐름으로 증명한다: 실배선(stub 게이트 → 실제
 * {@link DefaultConversationRouter} → 실제 {@link SlackConversationFlows} → 실제
 * {@link LocalPhotoStore}·{@link JsonFileNoteRepository})으로 사진 포함 신규 기록을 [저장]까지 관통시켜,
 * <ul>
 *   <li>① 텍스트에 없던 커피명·로스터리·원산지를 OCR이 {@code source=photo}로 채우고 그 값이 커밋된 노트
 *       JSON까지 살아남는다(AC-26~28) — {@code Entry.photos} 도메인 필드 제거 이후에도 OCR 채움 경로는
 *       스테이징({@code readStaged})을 읽지 필드를 읽지 않으므로 불변.</li>
 *   <li>② 커밋 후 사진은 {@code photos/<slug>/<date>/}에 실파일로 존재하고(아카이브 전용), 카드 경로는
 *       {@code cards/<slug>/<date>.jpg} 규약을 지킨다(AC-43). 노트 JSON엔 {@code photos} 키가 없다(AC-Δ1).</li>
 * </ul>
 * <p>이 두 가드는 기존 테스트가 <b>각각</b>은 봐도 <b>한 흐름으로 함께</b> 보지는 않는 교차 지점이다:
 * {@link Change0013RegressionGuardTest}는 {@code VisionExtraction.empty()}로 관통해 OCR <i>필드 채움</i>을
 * 단언하지 않고, {@link SlackConversationFlowsTest#startNewNoteFillsEmptyFieldsFromPhotoOcr}는 stub
 * photoStore를 써서 <i>실디스크 아카이브 커밋</i>을 단언하지 않는다 — 그 사이를 이 가드가 잇는다.
 * <p>AC-Δ6의 나머지 축은 기존 테스트가 이미 보므로 재복제하지 않는다: HEIC 썸네일 대체·대체 불가 거부·안내
 * (AC-45·46, {@code SlackConversationFlowsTest} L863~923·{@code ImageFormatTest}), OCR 1콜 배치·상한 절삭·
 * 실패 수렴({@code PhotoInfoExtractorTest}), 커밋 경로·{@code -N} 유일화·날짜 이동({@code LocalPhotoStoreTest}),
 * source 우선순위 {@code user > photo}({@code SlackConversationFlowsTest} V-6·{@code NoteEnricherTest}),
 * 노트 JSON {@code photos} 키 부재·6장 관통({@code Change0013RegressionGuardTest}).
 */
class Change0014RegressionGuardTest {

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

    /** 사진에서 커피명·필드를 읽어낸 값을 돌려주는 fake vision — OCR 필드 채움 단언의 재료. */
    private static final class FakeVisionClient implements VisionClient {
        int calls = 0;
        VisionExtraction canned = VisionExtraction.empty();

        @Override
        public VisionExtraction read(List<String> imageUrls, VisionHint hint) {
            calls++;
            return canned;
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

    // 사용자는 감상·평가만 말했다 — 커피명·로스터리·원산지는 텍스트에 없어 사진에서 읽어야 한다.
    private static ExtractionResult impressionOnly() {
        return new ExtractionResult(null, null, null, null, null,
                "달큰하고 좋았음", "달큰하고 좋았다", Rating.GOOD, null, null, false, null);
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

    @Test
    @DisplayName("AC-Δ6: 사진 OCR로 채운 빈 필드가 source=photo로 커밋 노트까지 살아남고, 사진은 photos/<slug>/<date>/ 아카이브로 이동한다")
    void photoOcrFieldFillAndArchiveCommitSurviveChange0014() throws IOException {
        // 커피명·필드를 텍스트로 말하지 않았고, 사진(OCR)이 채운다 — 필드 제거(TΔ1) 후에도 채움 경로 불변 검증.
        visionClient.canned = new VisionExtraction(
                "커피베라 예가체프", "커피베라", "에티오피아", null, null, List.of("자스민"));

        router.onMedia(media("bag.jpg", "cup.jpg"));
        assertEquals(2, countFiles(stagingDir()), "수신 사진 2장이 스테이징된다(입구 게이트 통과, AC-46 정상 경로)");

        intentClassifier.canned = MessageIntent.RECORD;
        llmClient.canned = impressionOnly();
        router.onMessage(message("이거 달큰하고 좋았어"));

        assertEquals(1, visionClient.calls, "묶인 사진은 vision 1콜로 전달된다(ADR-23 불변)");
        assertTrue(noteRepository.findAll().isEmpty(), "미리보기 단계는 노트 JSON을 만들지 않는다(ADR-3/AC-4 불변)");

        router.onAction(action(DefaultConversationRouter.ACTION_SAVE));

        // ① OCR가 채운 필드가 source=photo로 커밋된 노트까지 살아남는다(AC-26~28) — Entry.photos 제거와 무관.
        Note saved = noteRepository.findAll().get(0);
        assertEquals("2026-07-11-110000", saved.slug(), "slug = 최초 기록일 + 생성 시각(ADR-28)");
        assertEquals(Sourced.photo("커피베라 예가체프"), saved.coffeeName(), "빈 커피명은 사진 OCR 값으로 채운다(source=photo)");
        assertEquals(Sourced.photo("커피베라"), saved.roastery(), "빈 로스터리도 사진 값·source=photo");
        assertEquals(Sourced.photo("에티오피아"), saved.origin(), "빈 원산지도 사진 값·source=photo");
        assertEquals(Source.PHOTO, saved.officialNotes().source(), "공식 노트도 사진 유래(source=photo)");

        // ② 사진은 아카이브 전용으로 photos/<slug>/<date>/에 실존, 노트 JSON엔 photos 키 없음(AC-Δ1).
        Path committedDir = dataDir.resolve("photos").resolve("2026-07-11-110000").resolve("2026-07-11");
        assertEquals(2, countFiles(committedDir), "커밋 후 사진 2장이 photos/<slug>/<date>/에 실존(아카이브 전용, AC-43)");
        String noteJson = Files.readString(dataDir.resolve("notes").resolve("2026-07-11-110000.json"));
        assertFalse(noteJson.contains("\"photos\""), "노트 JSON에 photos 키가 없다(사진은 아카이브 전용, AC-Δ1)");
        assertTrue(noteJson.contains("\"source\":\"photo\""), "OCR 채움 필드는 JSON에 source=photo로 기록된다");
        assertEquals(0, countFiles(stagingDir()), "커밋 후 스테이징 잔존물 없음");
        assertTrue(photoBufferStore.get("U1").isEmpty(), "커밋 후 버퍼도 비워진다");

        // 경로 규약(AC-43): 카드는 cards/<slug>/<date>.jpg로 렌더·배달된다.
        assertEquals(List.of("2026-07-11-110000/2026-07-11"), renderer.entryCards,
                "증분 카드 렌더 1회 — (slug, date) 인자 규약 불변");
        assertEquals(List.of(Path.of("cards", "2026-07-11-110000", "2026-07-11.jpg")), responder.images,
                "배달 카드 경로 cards/<slug>/<date>.jpg 불변(AC-43)");
        assertEquals(List.of(FlowMessages.FINALIZE_SAVED), responder.finalizeStatuses, "버튼 1회 소진 불변");
    }
}
