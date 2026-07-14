package com.devwuu.mocha.slack;

import com.devwuu.mocha.domain.Entry;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.NoteMeta;
import com.devwuu.mocha.domain.PendingNote;
import com.devwuu.mocha.domain.PhotoBuffer;
import com.devwuu.mocha.domain.Rating;
import com.devwuu.mocha.domain.Sourced;
import com.devwuu.mocha.json.MochaObjectMapper;
import com.devwuu.mocha.llm.LlmClient;
import com.devwuu.mocha.llm.LlmRequest;
import com.devwuu.mocha.llm.SearchClient;
import com.devwuu.mocha.llm.SearchQuery;
import com.devwuu.mocha.llm.SearchResult;
import com.devwuu.mocha.llm.VisionExtraction;
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
import com.devwuu.mocha.pipeline.RevisionResult;
import com.devwuu.mocha.pipeline.SearchSelection;
import com.devwuu.mocha.render.NoteRenderer;
import com.devwuu.mocha.repository.InMemorySearchSessionStore;
import com.devwuu.mocha.repository.InMemoryTransitionSlot;
import com.devwuu.mocha.repository.JsonFileNoteRepository;
import com.devwuu.mocha.repository.JsonFilePendingStore;
import com.devwuu.mocha.repository.PhotoBufferStore;
import com.devwuu.mocha.repository.PhotoStore;
import com.devwuu.mocha.repository.StagedImage;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TΔ7(changes/0012): 신규 기록 경로 불변 회귀 가드 — 구현 변경 없음.
 * <p>0012가 pending에 mode를 도입하고 커밋에 edit 분기를 끼워 넣었으므로, 종전 계약이 깨지지 않았음을
 * 실배선(게이트 stub → 실제 {@link DefaultConversationRouter} → 실제 {@link SlackConversationFlows}
 * → 실제 파일 저장소)으로 단언한다. mode=record 경로의 개별 AC(AC-4·13·14·17)는 기존 테스트
 * ({@code SlackConversationFlowsTest}·{@code JsonFileNoteRepositoryTest})가 이미 보므로, 여기는
 * 층을 가로지르는 불변식만 본다.
 * <ul>
 *   <li>① AC-Δ6: 참조 신호 없는 신규 기록은 mode=record pending으로 흐르고, [저장] 커밋·카드 배달·버튼
 *       소진이 mode 도입 전과 동일하다 — edit 전용 기계(removeEntryCard)는 개입하지 않는다.</li>
 *   <li>② AC-Δ1 후반: edit 세션은 revise를 거쳐 [취소]해도 원본 노트 파일이 바이트 단위로 무변화다
 *       (파일 스냅샷 비교) — [저장] 확답 전 원본 무변화 원칙(FR-4/ADR-3)의 edit 확장.</li>
 * </ul>
 */
class Change0012RegressionGuardTest {

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

    /** 추출/수정/검색 후보 선정 응답을 responseType으로 분기하는 fake LLM(CLAUDE.md §5.3). */
    private static final class FakeLlmClient implements LlmClient {
        ExtractionResult canned;
        RevisionResult cannedRevision = new RevisionResult(null, null, null, null, null, null, null, null, null);
        SearchSelection cannedSelection = new SearchSelection(List.of());

        @SuppressWarnings("unchecked")
        @Override
        public <T> T complete(LlmRequest<T> request) {
            if (request.responseType() == RevisionResult.class) {
                return (T) cannedRevision;
            }
            if (request.responseType() == SearchSelection.class) {
                return (T) cannedSelection;
            }
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

    /** 렌더·삭제 호출을 캡처하는 fake — edit 전용 기계(removeEntryCard) 미개입 단언의 재료. */
    private static final class CapturingNoteRenderer implements NoteRenderer {
        final List<String> entryCards = new ArrayList<>();
        final List<String> removedCards = new ArrayList<>();

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
            removedCards.add(slug + "/" + date);
        }
    }

    /** 사진 경로는 이 가드에서 비활성 — 빈 동작 fake. */
    private static final class NoopPhotoStore implements PhotoStore {
        @Override
        public String stage(String userId, String filename, byte[] bytes) {
            return filename;
        }

        @Override
        public List<StagedImage> readStaged(String userId) {
            return List.of();
        }

        @Override
        public List<String> commit(String userId, String slug, String date) {
            return List.of();
        }

        @Override
        public void discard(String userId) {
        }

        @Override
        public void moveEntryPhotos(String slug, String fromDate, String toDate) {
        }

        @Override
        public List<String> stagedUserIds() {
            return List.of();
        }
    }

    private static final class NoopPhotoBufferStore implements PhotoBufferStore {
        @Override
        public void put(String userId, PhotoBuffer buffer) {
        }

        @Override
        public Optional<PhotoBuffer> get(String userId) {
            return Optional.empty();
        }

        @Override
        public void clear(String userId) {
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

    // 시간 고정 — today/타임스탬프를 결정론적으로(Asia/Seoul 2026-07-11). pending TTL은 넉넉히 잡아
    // (실 store는 시스템 시계) 고정 시각 createdAt이 만료로 새지 않게 한다.
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Duration PENDING_TTL = Duration.ofDays(365);
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-11T02:00:00Z"), SEOUL);

    private final ObjectMapper mapper = MochaObjectMapper.create();
    private final StubIntentClassifier intentClassifier = new StubIntentClassifier();
    private final FakeLlmClient llmClient = new FakeLlmClient();
    private final FakeSlackResponder responder = new FakeSlackResponder();
    private final CapturingNoteRenderer renderer = new CapturingNoteRenderer();
    private final CapturingPreviewMessenger previewMessenger = new CapturingPreviewMessenger();
    private final InMemorySearchSessionStore searchSessionStore = new InMemorySearchSessionStore(Duration.ofHours(1));
    private final InMemoryTransitionSlot transitionSlot = new InMemoryTransitionSlot();

    private JsonFilePendingStore pendingStore;
    private JsonFileNoteRepository noteRepository;
    private DefaultConversationRouter router;

    @BeforeEach
    void wireRealRouterAndFlow() {
        pendingStore = new JsonFilePendingStore(dataDir, mapper, PENDING_TTL);
        noteRepository = new JsonFileNoteRepository(dataDir, mapper);
        SlackConversationFlows flow = new SlackConversationFlows(
                pendingStore, noteRepository, renderer, responder,
                new NoteExtractor(llmClient, mapper), new NoteMatcher(), new NoteEnricher(new FakeSearchClient()), new AliasGenerator(llmClient, mapper),
                new PhotoInfoExtractor((imageUrls, hint) -> VisionExtraction.empty(), 4),
                new PendingReviser(llmClient, mapper), previewMessenger,
                urlPrivate -> new byte[]{1}, new NoopPhotoStore(), new NoopPhotoBufferStore(),
                searchSessionStore, new NoteSearchService(llmClient, mapper, 0),
                transitionSlot, artifactDir, Duration.ofMinutes(10), clock);
        router = new DefaultConversationRouter(pendingStore, searchSessionStore, intentClassifier, flow);
    }

    private static IncomingMessage message(String text) {
        return new IncomingMessage("U1", "C1", text, "1720000100.000001");
    }

    private static IncomingAction action(String actionId) {
        return new IncomingAction("U1", "C1", actionId, "confirm", "1720000000.000999");
    }

    /** 수정 대상이 될 원본 노트(엔트리 1건)를 실제 파일로 심는다 — 바이트 스냅샷 비교의 재료. */
    private Path seedNote(String slug, LocalDate date) {
        NoteMeta meta = new NoteMeta(
                Sourced.user("커피베라 예가체프"), Sourced.user("커피베라"), Sourced.search("에티오피아"),
                null, null, null, List.of());
        noteRepository.upsertEntry(slug, meta,
                new Entry(date, "원래 감상", Rating.GOOD, null, OffsetDateTime.now()));
        return dataDir.resolve("notes").resolve(slug + ".json");
    }

    /** mode=edit pending — 원본 (slug, targetDate) 엔트리를 고치는 단일 엔트리 draft. */
    private static PendingNote editPending(String slug, LocalDate targetDate) {
        Entry entry = new Entry(targetDate, "고친 감상 draft", Rating.GOOD, null, OffsetDateTime.now());
        Note draft = new Note(
                slug, Sourced.user("커피베라 예가체프"),
                Sourced.user("커피베라"), Sourced.search("에티오피아"), null, null,
                null, List.of(), List.of(entry), OffsetDateTime.now(), OffsetDateTime.now());
        return new PendingNote(PendingNote.Mode.EDIT, draft, new PendingNote.EditTarget(slug, targetDate),
                null, "1720000000.000999", OffsetDateTime.now());
    }

    // --- ① AC-Δ6: 신규 기록(mode=record) 경로가 mode 도입 전과 동일 ---

    @Test
    @DisplayName("AC-Δ6: 신규 기록 → mode=record pending, [저장] 커밋·카드 배달(AC-17)·버튼 소진에 edit 기계 미개입")
    void plainRecordPathIsUnchangedByModeIntroduction() {
        intentClassifier.canned = MessageIntent.RECORD;
        llmClient.canned = new ExtractionResult(
                "커피베라 예가체프", "커피베라", null, null, null, "새콤하고 좋았다", Rating.GOOD, null, null, false, null);

        router.onMessage(message("커피베라 예가체프 마셨는데 새콤하고 좋았다"));

        PendingNote pending = pendingStore.get("U1").orElseThrow();
        assertEquals(PendingNote.Mode.RECORD, pending.mode(), "신규 기록은 mode=record로 흐른다(AC-Δ6)");
        assertNull(pending.target(), "record pending에는 edit 대상이 실리지 않는다");
        assertFalse(pending.dateConflict(), "record pending에 날짜 충돌 경고가 실리지 않는다");
        assertNotNull(previewMessenger.published, "한 메시지로 곧장 미리보기가 도착한다(AC-1 마찰 불변)");
        assertTrue(noteRepository.findAll().isEmpty(), "미리보기 단계는 노트 JSON을 만들지 않는다(AC-4)");

        router.onAction(action(DefaultConversationRouter.ACTION_SAVE));

        List<Note> saved = noteRepository.findAll();
        assertEquals(1, saved.size(), "[저장] 버튼으로 노트가 커밋된다(종전과 동일)");
        assertEquals("새콤하고 좋았다", saved.get(0).entries().get(0).myTaste());
        assertTrue(pendingStore.get("U1").isEmpty(), "커밋 후 pending 폐기");
        assertEquals(1, renderer.entryCards.size(), "증분 렌더 1회 — 종전과 동일");
        assertTrue(renderer.removedCards.isEmpty(), "record 커밋은 removeEntryCard(edit 전용)를 부르지 않는다");
        assertEquals(1, responder.images.size(), "저장 후 갱신 카드가 배달된다(AC-17)");
        assertEquals(List.of(FlowMessages.FINALIZE_SAVED), responder.finalizeStatuses,
                "버튼 1회 소진(0009) — 종전과 동일");
    }

    // --- ② AC-Δ1 후반: edit 세션 [취소] 시 원본 노트 파일 바이트 무변화 ---

    @Test
    @DisplayName("AC-Δ1: edit 세션 revise 후 [취소] → 원본 노트 파일 바이트 무변화 + pending 폐기 + 파생물 무접촉")
    void editCancelLeavesOriginalNoteBytesUntouched() throws Exception {
        Path noteFile = seedNote("yirga", LocalDate.of(2026, 7, 8));
        byte[] original = Files.readAllBytes(noteFile);
        pendingStore.put("U1", editPending("yirga", LocalDate.of(2026, 7, 8)));

        // 수정 세션 중 revise 한 턴 — draft에는 반영되지만 원본 파일은 [저장] 확답 전까지 무변화(FR-4/ADR-3).
        intentClassifier.canned = MessageIntent.REVISE;
        llmClient.cannedRevision = new RevisionResult(null, null, null, null, null, null, "산미가 낮아 부드러웠다", null, null);
        router.onMessage(message("감상을 산미 낮음으로 바꿔줘"));

        assertEquals("산미가 낮아 부드러웠다",
                pendingStore.get("U1").orElseThrow().draft().entries().get(0).myTaste(), "revise는 draft에만 반영된다");
        assertArrayEquals(original, Files.readAllBytes(noteFile), "revise 반영 중에도 원본 노트 파일은 바이트 무변화");

        router.onAction(action(DefaultConversationRouter.ACTION_CANCEL));

        assertArrayEquals(original, Files.readAllBytes(noteFile), "[취소] 후 원본 노트 파일 바이트 무변화(AC-Δ1)");
        assertTrue(pendingStore.get("U1").isEmpty(), "[취소]로 edit pending 폐기");
        assertTrue(responder.messages.contains(FlowMessages.CANCELED), "취소 완료 안내");
        assertEquals(List.of(FlowMessages.FINALIZE_CANCELED), responder.finalizeStatuses, "버튼 1회 소진");
        assertTrue(renderer.entryCards.isEmpty() && renderer.removedCards.isEmpty(),
                "[취소]는 카드 렌더·삭제 어느 파생물도 건드리지 않는다");
    }
}
