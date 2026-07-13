package com.devwuu.mocha.slack;

import com.devwuu.mocha.domain.Entry;
import com.devwuu.mocha.domain.MatchInfo;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.PendingNote;
import com.devwuu.mocha.domain.PhotoBuffer;
import com.devwuu.mocha.domain.Rating;
import com.devwuu.mocha.domain.SearchSession;
import com.devwuu.mocha.domain.Sourced;
import com.devwuu.mocha.json.MochaObjectMapper;
import com.devwuu.mocha.llm.LlmClient;
import com.devwuu.mocha.llm.LlmException;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

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
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * TΔ7(changes/0011): 커밋·수정·기록 마찰 불변 회귀 가드 — 구현 변경 없음.
 * <p>의도 우선 라우팅(ADR-24)이 상태 머신의 심장을 갈아끼웠으므로, 종전 계약이 깨지지 않았음을
 * 실배선(게이트 stub → 실제 {@link DefaultConversationRouter} → 실제 {@link DefaultConfirmationFlow}
 * → 실제 파일 저장소)으로 단언한다. 개별 단위(라우터 매트릭스·플로우 분기·store TTL)는 각자의 테스트가
 * 이미 보므로, 여기는 층을 가로지르는 불변식만 본다.
 * <ul>
 *   <li>① AC-Δ7: "저장해줘"/"취소해줘" 텍스트는 어떤 의도 판정·세션 상태에서도 커밋/폐기를 일으키지
 *       않는다 — 커밋은 버튼(action_id)으로만(ADR-3 불변). 게이트 실패 폴백 3상황도 동일.</li>
 *   <li>② AC-Δ8/AC-5: revise 판정 시 수정 병합·미리보기 edit 갱신이 종전과 동일.</li>
 *   <li>③ AC-Δ8/AC-1: 참조 신호 없는 새 기록은 종전과 동일 마찰(한 메시지 → 즉시 미리보기).</li>
 *   <li>④ AC-7/AC-35(AC-Δ4): pending은 재시작에 생존(파일 영속), 검색 세션은 소멸(메모리 전용).</li>
 * </ul>
 */
class Change0011RegressionGuardTest {

    /** 판정 결과를 테스트가 지정하는 stub 게이트 — LLM 미접촉(CLAUDE.md §5.2). */
    private static final class StubIntentClassifier extends IntentClassifier {
        MessageIntent canned = MessageIntent.OTHER;
        RuntimeException failure;

        StubIntentClassifier() {
            super(null, null); // LlmClient·ObjectMapper 미사용 — classify를 통째로 대체한다.
        }

        @Override
        public IntentResult classify(String message, ContextHint contextHint) {
            if (failure != null) {
                throw failure;
            }
            return new IntentResult(canned);
        }
    }

    /** 추출/수정/검색 후보 선정 응답을 responseType으로 분기하는 fake LLM(CLAUDE.md §5.3). */
    private static final class FakeLlmClient implements LlmClient {
        ExtractionResult canned;
        // 기본은 아무것도 바꾸지 않는 패치(null=미변경) — 어떤 텍스트가 revise로 흘러도 병합이 no-op으로 성립한다.
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

    /** 안내 메시지·카드 배달·버튼 소진 호출을 캡처하는 fake — finalize 호출 유무가 커밋/폐기 경로 진입의 프록시다. */
    private static final class FakeSlackResponder implements SlackResponder {
        final List<String> messages = new ArrayList<>();
        final List<Path> images = new ArrayList<>();
        final List<String> captions = new ArrayList<>();
        final List<String> finalizeStatuses = new ArrayList<>();

        @Override
        public void post(String channelId, String text) {
            messages.add(text);
        }

        @Override
        public void postImage(String channelId, Path imagePath, String caption) {
            images.add(imagePath);
            captions.add(caption);
        }

        @Override
        public void finalizePreview(String channelId, PendingNote pending, String statusText) {
            finalizeStatuses.add(statusText);
        }
    }

    /** 렌더는 경로만 돌려주는 fake — 라스터화는 이 가드의 관심사가 아니다. */
    private static final class FakeNoteRenderer implements NoteRenderer {
        @Override
        public void renderAll() {
        }

        @Override
        public Path renderEntryCard(String slug, LocalDate date) {
            return Path.of("cards", slug, date + ".jpg");
        }

        @Override
        public void removeEntryCard(String slug, LocalDate date) {
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
        DefaultConfirmationFlow flow = new DefaultConfirmationFlow(
                pendingStore, noteRepository, new FakeNoteRenderer(), responder,
                new NoteExtractor(llmClient, mapper), new NoteMatcher(), new NoteEnricher(new FakeSearchClient()),
                new PhotoInfoExtractor((imageUrls, hint) -> VisionExtraction.empty(), 4),
                new PendingReviser(llmClient, mapper), previewMessenger,
                urlPrivate -> new byte[]{1}, new NoopPhotoStore(), new NoopPhotoBufferStore(),
                searchSessionStore, new NoteSearchService(llmClient, mapper, 0),
                transitionSlot, artifactDir, Duration.ofMinutes(10), clock);
        router = new DefaultConversationRouter(pendingStore, searchSessionStore, intentClassifier, flow);
    }

    private static PendingNote pendingWith(String slug) {
        Entry entry = new Entry(
                LocalDate.of(2026, 7, 11), "새콤하고 좋았다", Rating.GOOD, null, List.of(), OffsetDateTime.now());
        Note draft = new Note(
                slug, Sourced.user("커피베라 예가체프"),
                Sourced.user("커피베라"), Sourced.search("에티오피아"), null, null,
                null, List.of("https://example.com/coffee"),
                List.of(entry), OffsetDateTime.now(), OffsetDateTime.now());
        return new PendingNote(draft, MatchInfo.newNote(), "1720000000.000999", OffsetDateTime.now());
    }

    private static IncomingMessage message(String text) {
        return new IncomingMessage("U1", "C1", text, "1720000100.000001");
    }

    private static ExtractionResult extraction(String coffeeName, String roastery, String myTaste) {
        // referencesPast=false, targetDate=null(→ today 기본화) — 참조 신호 없는 평범한 새 기록.
        return new ExtractionResult(coffeeName, roastery, null, null, null, myTaste, Rating.GOOD, null, null, false, null);
    }

    private void seedState(boolean hasPending, boolean searchActive) {
        if (hasPending) {
            pendingStore.put("U1", pendingWith("coffeevera-yirgacheffe"));
        }
        if (searchActive) {
            searchSessionStore.put("U1",
                    new SearchSession(List.of("예가체프"), List.of(), 0, OffsetDateTime.now()));
        }
    }

    // 커밋/폐기 부재 공통 단언 — 커밋=노트 JSON 쓰기, 폐기=pending 소멸, finalize=저장/취소 완료 표시 경로.
    private void assertNoCommitNoDiscard(boolean hadPending) {
        assertTrue(noteRepository.findAll().isEmpty(), "자연어 텍스트로 노트 JSON이 커밋되는 경로가 없다(AC-Δ7)");
        if (hadPending) {
            assertTrue(pendingStore.get("U1").isPresent(), "자연어 텍스트로 pending이 폐기되는 경로가 없다(AC-Δ7)");
        }
        assertTrue(responder.finalizeStatuses.isEmpty(), "저장/취소 완료 표시(버튼 소진)에 진입하지 않는다");
        assertFalse(responder.messages.contains(DefaultConfirmationFlow.CANCELED), "취소 완료 안내가 없다");
        assertFalse(responder.captions.contains(DefaultConfirmationFlow.SAVE_DONE_CAPTION), "저장 완료 배달이 없다");
    }

    // --- ① AC-Δ7: 자연어 커밋/폐기 경로 부재 — 의도×상태 전 조합 ---

    static Stream<Arguments> commitPhraseMatrix() {
        List<Arguments> combos = new ArrayList<>();
        for (String text : List.of("저장해줘", "취소해줘")) {
            for (MessageIntent intent : MessageIntent.values()) {
                for (boolean hasPending : new boolean[]{false, true}) {
                    for (boolean searchActive : new boolean[]{false, true}) {
                        combos.add(arguments(text, intent, hasPending, searchActive));
                    }
                }
            }
        }
        return combos.stream();
    }

    @ParameterizedTest(name = "\"{0}\" × {1} × pending={2} × search={3}")
    @MethodSource("commitPhraseMatrix")
    @DisplayName("AC-Δ7: \"저장해줘\"/\"취소해줘\" 텍스트는 어떤 의도 판정·세션 상태에서도 커밋/폐기를 일으키지 않는다")
    void savePhraseNeverCommitsThroughAnyRoute(
            String text, MessageIntent intent, boolean hasPending, boolean searchActive) {
        seedState(hasPending, searchActive);
        intentClassifier.canned = intent;
        // record로 판정돼 파이프라인에 들어가도 커밋이 아니라 미리보기까지만 가야 한다 — 추출은 canned로 성립시킨다.
        llmClient.canned = extraction("커피베라 예가체프", "커피베라", "새콤함");

        router.onMessage(message(text));

        assertNoCommitNoDiscard(hasPending);
    }

    static Stream<Arguments> fallbackStates() {
        return Stream.of(
                arguments(true, true),    // 폴백① 검색 세션 중 → search
                arguments(true, false),   // 폴백② 대기 있음 → revise
                arguments(false, false)); // 폴백③ 무 → record
    }

    @ParameterizedTest(name = "pending={0} × search={1}")
    @MethodSource("fallbackStates")
    @DisplayName("AC-Δ7/AC-21: 게이트 실패 폴백 3상황에서도 \"저장해줘\" 텍스트는 커밋/폐기를 일으키지 않는다")
    void savePhraseNeverCommitsUnderGateFallback(boolean hasPending, boolean searchActive) {
        seedState(hasPending, searchActive);
        intentClassifier.failure = new LlmException("게이트 호출 실패");
        llmClient.canned = extraction("커피베라 예가체프", "커피베라", "새콤함");

        router.onMessage(message("저장해줘"));

        assertNoCommitNoDiscard(hasPending);
    }

    @Test
    @DisplayName("AC-Δ7 대조: 커밋은 여전히 [저장] 버튼(action_id)으로만 — 같은 배선에서 버튼은 정상 커밋한다")
    void saveButtonStillCommitsThroughSameWiring() {
        pendingStore.put("U1", pendingWith("coffeevera-yirgacheffe"));

        router.onAction(new IncomingAction(
                "U1", "C1", DefaultConversationRouter.ACTION_SAVE, "slug", "1720000000.000999"));

        assertTrue(noteRepository.findBySlug("coffeevera-yirgacheffe").isPresent(),
                "버튼 커밋 경로는 종전대로 동작한다(ADR-3)");
        assertTrue(pendingStore.get("U1").isEmpty(), "커밋 후 pending이 폐기된다");
    }

    // --- ② AC-Δ8: revise 판정의 수정 동작이 종전 AC-5와 동일 ---

    @Test
    @DisplayName("AC-Δ8/AC-5: revise 판정 → 수정이 병합되고 같은 미리보기가 edit로 갱신된다(엔트리 미생성·커밋 없음)")
    void reviseStillMergesAndEditsPreview() {
        pendingStore.put("U1", pendingWith("coffeevera-yirgacheffe"));
        intentClassifier.canned = MessageIntent.REVISE;
        llmClient.cannedRevision = new RevisionResult(null, null, null, null, null, null, "산미가 낮아 부드러웠다", null, null);

        router.onMessage(message("산미는 낮음으로 바꿔줘"));

        assertNotNull(previewMessenger.published, "수정 후 미리보기가 갱신 발행된다");
        assertEquals("1720000000.000999", previewMessenger.published.previewTs(),
                "preview_ts 보존 → 재전송이 아닌 edit로 갱신한다(종전 AC-5)");
        Note draft = previewMessenger.published.draft();
        assertEquals(1, draft.entries().size(), "수정은 엔트리를 새로 만들지 않는다(AC-5)");
        assertEquals("산미가 낮아 부드러웠다", draft.entries().get(0).myTaste(), "수정 텍스트가 병합된다");
        assertEquals("산미가 낮아 부드러웠다",
                pendingStore.get("U1").orElseThrow().draft().entries().get(0).myTaste(),
                "갱신본이 파일에 영속화된다(재시작 생존 재료)");
        assertTrue(noteRepository.findAll().isEmpty(), "수정 반영은 커밋이 아니다(AC-4 불변)");
    }

    // --- ③ AC-Δ8: 참조 신호 없는 새 기록의 AC-1 마찰 불변 ---

    @Test
    @DisplayName("AC-Δ8/AC-1: 참조 신호 없는 새 기록은 메시지 한 줄 → 즉시 미리보기 + pending 생성(추가 안내·왕복 없음)")
    void plainNewRecordKeepsImmediatePreviewFriction() {
        intentClassifier.canned = MessageIntent.RECORD;
        llmClient.canned = extraction("커피베라 예가체프", "커피베라", "새콤하고 좋았다");

        router.onMessage(message("커피베라 예가체프 마셨는데 새콤하고 좋았다"));

        assertNotNull(previewMessenger.published, "한 메시지로 곧장 미리보기가 도착한다(AC-1)");
        assertEquals("커피베라 예가체프", previewMessenger.published.draft().coffeeName().value());
        assertEquals(previewMessenger.ts, pendingStore.get("U1").orElseThrow().previewTs(),
                "확정 preview_ts가 실린 pending이 생성된다");
        assertTrue(responder.messages.isEmpty(), "안내·재질문 등 추가 왕복이 없다(마찰 불변)");
        assertTrue(noteRepository.findAll().isEmpty(), "미리보기 단계는 노트 JSON을 만들지 않는다(AC-4)");
    }

    // --- ④ AC-7/AC-35: 재시작 — pending 생존 vs 검색 세션 소멸 ---

    @Test
    @DisplayName("AC-7/AC-35(AC-Δ4): 재시작 시 pending은 파일에서 생존하고 검색 세션은 소멸한다 — data/에는 pending만 남는다")
    void restartKeepsPendingButDropsSearchSession() throws Exception {
        pendingStore.put("U1", pendingWith("coffeevera-yirgacheffe"));
        searchSessionStore.put("U1",
                new SearchSession(List.of("예가체프"), List.of(), 0, OffsetDateTime.now()));

        // 재시작 = 저장소 인스턴스를 새로 만든다(pending은 같은 data/, 세션 store는 빈 메모리).
        JsonFilePendingStore restartedPending = new JsonFilePendingStore(dataDir, mapper, PENDING_TTL);
        InMemorySearchSessionStore restartedSearch = new InMemorySearchSessionStore(Duration.ofHours(1));

        PendingNote survived = restartedPending.get("U1").orElseThrow(
                () -> new AssertionError("pending은 재시작에 생존해야 한다(AC-7)"));
        assertEquals("coffeevera-yirgacheffe", survived.draft().slug(), "동일 draft가 복원된다");
        assertTrue(restartedSearch.get("U1").isEmpty(), "검색 세션은 메모리 전용 — 재시작 시 소멸한다(AC-35)");
        // 검색 세션이 data/ 아래 어떤 파일도 남기지 않았음을 파일 목록으로 못박는다(NFR-2 예외의 경계).
        try (Stream<Path> files = Files.list(dataDir)) {
            assertEquals(List.of(dataDir.resolve("pending.json")), files.toList(),
                    "data/에는 pending.json만 존재한다");
        }
    }
}
