package com.devwuu.mocha.slack;

import com.devwuu.mocha.domain.Entry;
import com.devwuu.mocha.domain.MatchInfo;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.PendingNote;
import com.devwuu.mocha.domain.PhotoBuffer;
import com.devwuu.mocha.domain.Rating;
import com.devwuu.mocha.domain.SearchSession;
import com.devwuu.mocha.domain.Source;
import com.devwuu.mocha.domain.Sourced;
import com.devwuu.mocha.json.MochaObjectMapper;
import com.devwuu.mocha.llm.LlmClient;
import com.devwuu.mocha.llm.LlmException;
import com.devwuu.mocha.llm.LlmRequest;
import com.devwuu.mocha.llm.SearchClient;
import com.devwuu.mocha.llm.SearchQuery;
import com.devwuu.mocha.llm.SearchResult;
import com.devwuu.mocha.llm.VisionClient;
import com.devwuu.mocha.llm.VisionExtraction;
import com.devwuu.mocha.llm.VisionHint;
import com.devwuu.mocha.pipeline.ExtractionResult;
import com.devwuu.mocha.pipeline.NoteEnricher;
import com.devwuu.mocha.pipeline.NoteExtractor;
import com.devwuu.mocha.pipeline.NoteMatcher;
import com.devwuu.mocha.pipeline.NoteSearchService;
import com.devwuu.mocha.pipeline.PendingReviser;
import com.devwuu.mocha.pipeline.PhotoInfoExtractor;
import com.devwuu.mocha.pipeline.RevisionResult;
import com.devwuu.mocha.pipeline.SearchSelection;
import com.devwuu.mocha.render.NoteRenderer;
import com.devwuu.mocha.repository.JsonFileNoteRepository;
import com.devwuu.mocha.repository.NoteRepository;
import com.devwuu.mocha.repository.PendingStore;
import com.devwuu.mocha.repository.PhotoBufferStore;
import com.devwuu.mocha.repository.PhotoStore;
import com.devwuu.mocha.repository.SearchSessionStore;
import com.devwuu.mocha.repository.StagedImage;
import com.devwuu.mocha.repository.TransitionSlot;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T3-5: [저장]/[취소] 커밋 파이프라인 + T3-6: 신규 노트 오케스트레이션(startNewNote) 검증. 저장소는 실제
 * 파일 I/O(@TempDir, CLAUDE.md §5.2)로 AC-4를 파일 부재로 단언하고, LLM·검색·Slack 전송은 fake로 대체해
 * 추출→매칭→보강→미리보기 흐름과 커밋 순서/거부 경로를 결정론적으로 본다.
 */
class DefaultConfirmationFlowTest {

    /** get()이 돌려줄 pending을 지정하고, put/clear 호출을 캡처하는 fake. */
    private static final class FakePendingStore implements PendingStore {
        private Optional<PendingNote> pending = Optional.empty();
        final List<PendingNote> puts = new ArrayList<>();
        int clearCount = 0;
        List<String> order; // 커밋 흐름 순서 회귀 가드(TΔ3)에서 여러 fake에 걸친 호출 순서를 캡처하는 공용 로그(비면 무시).

        void setPending(PendingNote p) {
            this.pending = Optional.ofNullable(p);
        }

        @Override
        public void put(String userId, PendingNote pending) {
            puts.add(pending);
            this.pending = Optional.of(pending);
        }

        @Override
        public Optional<PendingNote> get(String userId) {
            return pending;
        }

        @Override
        public void clear(String userId) {
            clearCount++;
            if (order != null) {
                order.add("clear");
            }
            pending = Optional.empty();
        }
    }

    /** renderAll 호출과 증분 renderEntryCard 호출(slug/date)을 캡처하고, 렌더 실패를 주입하는 fake. */
    private static final class FakeNoteRenderer implements NoteRenderer {
        int renderAllCount = 0;
        final List<String> entryCards = new ArrayList<>(); // "slug/date" 캡처
        RuntimeException renderFailure;

        @Override
        public void renderAll() {
            renderAllCount++;
        }

        @Override
        public Path renderEntryCard(String slug, LocalDate date) {
            if (renderFailure != null) {
                throw renderFailure;
            }
            entryCards.add(slug + "/" + date);
            return Path.of("cards", slug, date + ".jpg");
        }
    }

    /** 전송된 안내 메시지·배달된 카드 이미지·버튼 소진 호출을 캡처하고, 업로드/소진 실패를 주입하는 fake. */
    private static final class FakeSlackResponder implements SlackResponder {
        final List<String> messages = new ArrayList<>();
        final List<Path> images = new ArrayList<>();
        final List<String> captions = new ArrayList<>();
        final List<String> finalizeStatuses = new ArrayList<>();   // 버튼 소진 statusText 캡처
        final List<PendingNote> finalizePendings = new ArrayList<>(); // 버튼 소진에 넘어온 pending 캡처
        RuntimeException imageFailure;
        RuntimeException finalizeFailure;
        List<String> order; // 커밋 흐름 순서 회귀 가드(TΔ3)용 공용 로그(비면 무시).

        @Override
        public void post(String channelId, String text) {
            messages.add(text);
        }

        @Override
        public void postImage(String channelId, Path imagePath, String caption) {
            if (imageFailure != null) {
                throw imageFailure; // 실 files.uploadV2 실패를 흉내낸다 — confirmSave가 폴백으로 수렴하는지 본다(AC-Δ6)
            }
            images.add(imagePath);
            captions.add(caption);
            if (order != null) {
                order.add("deliver");
            }
        }

        @Override
        public void finalizePreview(String channelId, PendingNote pending, String statusText) {
            if (finalizeFailure != null) {
                throw finalizeFailure; // chat.update 실패 흉내 — 커밋·배달 결과가 불변인지 본다(AC-Δ2)
            }
            finalizePendings.add(pending);
            finalizeStatuses.add(statusText);
            if (order != null) {
                order.add("finalize");
            }
        }
    }

    /**
     * 추출/수정/검색 후보 선정 응답을 미리 지정하는 fake LLM — 계약(구조)만 검증, 생성 자체는 대체(CLAUDE.md §5.3).
     * 요청의 responseType으로 추출({@link ExtractionResult})·수정({@link RevisionResult})·검색({@link SearchSelection}) 응답을 분기한다.
     */
    private static final class FakeLlmClient implements LlmClient {
        ExtractionResult canned;
        RevisionResult cannedRevision;
        SearchSelection cannedSelection = new SearchSelection(List.of());
        RuntimeException failure;         // 모든 요청 공통 실패

        @SuppressWarnings("unchecked")
        @Override
        public <T> T complete(LlmRequest<T> request) {
            if (failure != null) {
                throw failure;
            }
            if (request.responseType() == RevisionResult.class) {
                return (T) cannedRevision;
            }
            if (request.responseType() == SearchSelection.class) {
                return (T) cannedSelection;
            }
            return (T) canned;
        }
    }

    /** 검색 세션 put/clear를 캡처하는 fake — 실 store의 TTL·메모리 규칙은 InMemorySearchSessionStoreTest가 본다. */
    private static final class FakeSearchSessionStore implements SearchSessionStore {
        private Optional<SearchSession> session = Optional.empty();
        final List<SearchSession> puts = new ArrayList<>();
        int clearCount = 0;

        void setSession(SearchSession s) {
            this.session = Optional.ofNullable(s);
        }

        @Override
        public void put(String userId, SearchSession session) {
            puts.add(session);
            this.session = Optional.of(session);
        }

        @Override
        public Optional<SearchSession> get(String userId) {
            return session;
        }

        @Override
        public void clear(String userId) {
            clearCount++;
            session = Optional.empty();
        }
    }

    /**
     * 전환 슬롯 fake — hold/take 호출을 캡처하고 {@code expired}로 TTL 만료를 흉내낸다(빈 Optional).
     * 실제 TTL·단일 슬롯 규칙은 InMemoryTransitionSlotTest(TΔ4)가 본다.
     */
    private static final class FakeTransitionSlot implements TransitionSlot {
        Object payload;
        boolean expired = false;
        final List<Object> holds = new ArrayList<>();
        int takeCount = 0;

        @Override
        public void hold(Object payload) {
            holds.add(payload);
            this.payload = payload;
        }

        @Override
        public Optional<Object> take() {
            takeCount++;
            Object taken = payload;
            payload = null;
            return expired ? Optional.empty() : Optional.ofNullable(taken);
        }
    }

    /** 검색 보강 결과를 미리 지정하는 fake. 기본은 무결과(AC-12). */
    private static final class FakeSearchClient implements SearchClient {
        SearchResult canned = SearchResult.empty();

        @Override
        public SearchResult search(SearchQuery query) {
            return canned;
        }
    }

    /** url_private → 바이트 다운로드 대체(실 Slack 미접촉, CLAUDE.md §5.2). */
    private static final class FakePhotoDownloader implements PhotoDownloader {
        RuntimeException failure;
        final List<String> downloaded = new ArrayList<>();

        @Override
        public byte[] download(String urlPrivate) {
            if (failure != null) {
                throw failure;
            }
            downloaded.add(urlPrivate);
            return new byte[]{1, 2, 3};
        }
    }

    /** 스테이징/커밋을 인메모리로 흉내내는 fake — 파일 규칙은 LocalPhotoStore 테스트가 따로 본다. */
    private static final class FakePhotoStore implements PhotoStore {
        final List<String> staged = new ArrayList<>();
        final List<byte[]> stagedBytes = new ArrayList<>();
        final List<String> committed = new ArrayList<>();
        int discardCount = 0;

        @Override
        public String stage(String userId, String filename, byte[] bytes) {
            staged.add(filename);
            stagedBytes.add(bytes);
            return filename;
        }

        @Override
        public List<StagedImage> readStaged(String userId) {
            List<StagedImage> images = new ArrayList<>();
            for (int i = 0; i < staged.size(); i++) {
                images.add(new StagedImage(staged.get(i), stagedBytes.get(i)));
            }
            return images;
        }

        @Override
        public List<String> commit(String userId, String slug, String date) {
            List<String> paths = staged.stream().map(n -> "photos/" + slug + "/" + date + "/" + n).toList();
            committed.addAll(paths);
            staged.clear();
            stagedBytes.clear();
            return paths;
        }

        @Override
        public void discard(String userId) {
            staged.clear();
            stagedBytes.clear();
            discardCount++;
        }
    }

    /** 수신 사진 OCR([2.5])용 fake — 호출 여부·전달 이미지 수를 기록하고 canned 결과를 돌려준다. */
    private static final class FakeVisionClient implements VisionClient {
        VisionExtraction canned = VisionExtraction.empty();
        RuntimeException toThrow = null;
        int calls = 0;
        List<String> lastImageUrls = List.of();

        @Override
        public VisionExtraction read(List<String> imageUrls, VisionHint hint) {
            calls++;
            lastImageUrls = imageUrls;
            if (toThrow != null) {
                throw toThrow;
            }
            return canned;
        }
    }

    /** 사진 버퍼를 지정/캡처하는 fake. */
    private static final class FakePhotoBufferStore implements PhotoBufferStore {
        Optional<PhotoBuffer> buffer = Optional.empty();
        final List<PhotoBuffer> puts = new ArrayList<>();
        int clearCount = 0;

        void setBuffer(PhotoBuffer s) {
            this.buffer = Optional.ofNullable(s);
        }

        @Override
        public void put(String userId, PhotoBuffer buffer) {
            puts.add(buffer);
            this.buffer = Optional.of(buffer);
        }

        @Override
        public Optional<PhotoBuffer> get(String userId) {
            return buffer;
        }

        @Override
        public void clear(String userId) {
            clearCount++;
            buffer = Optional.empty();
        }
    }

    /** 발행된 pending을 캡처하고 preview_ts를 돌려주는 미리보기 어댑터 스텁(Slack 미접촉). */
    private static final class CapturingPreviewMessenger extends PreviewMessenger {
        PendingNote published;
        String ts = "1720000000.000123";
        boolean fail = false;

        CapturingPreviewMessenger() {
            super(new PreviewBlocks(), null);
        }

        @Override
        public String publish(String channelId, PendingNote pending) {
            if (fail) {
                throw new IllegalStateException("전송 실패");
            }
            this.published = pending;
            return ts;
        }
    }

    /**
     * 실제 파일 I/O({@link JsonFileNoteRepository})에 위임하되 커밋(upsertEntry) 시점을 공용 순서 로그에 기록하는 래퍼.
     * TΔ3 회귀 가드에서 "저장 커밋 → clear → 배달 → 버튼 소진" 순서를 여러 fake에 걸쳐 단언하기 위한 것 — 저장소 자체의
     * 파일 규칙 검증(§5.2)은 그대로 실제 구현이 담당한다.
     */
    private static final class RecordingNoteRepository implements NoteRepository {
        private final NoteRepository delegate;
        private final List<String> order;

        RecordingNoteRepository(NoteRepository delegate, List<String> order) {
            this.delegate = delegate;
            this.order = order;
        }

        @Override
        public List<Note> findAll() {
            return delegate.findAll();
        }

        @Override
        public Optional<Note> findBySlug(String slug) {
            return delegate.findBySlug(slug);
        }

        @Override
        public String nextAvailableSlug(String base) {
            return delegate.nextAvailableSlug(base);
        }

        @Override
        public Note upsertEntry(String slug, com.devwuu.mocha.domain.NoteMeta meta, Entry entry) {
            Note saved = delegate.upsertEntry(slug, meta, entry);
            order.add("commit"); // 파일 쓰기가 실제로 끝난 뒤 기록 — clear보다 앞섬을 단언(AC-Δ5)
            return saved;
        }

        @Override
        public Note applyEdit(String slug, java.time.LocalDate targetDate, Note draft) {
            Note saved = delegate.applyEdit(slug, targetDate, draft);
            order.add("commit"); // upsertEntry와 동일 기준 — edit 커밋도 clear보다 앞섬(changes/0012)
            return saved;
        }
    }

    @TempDir
    Path dataDir;

    @TempDir
    Path artifactDir; // 검색 카드 재전송(TΔ5)의 기존 카드 존재 판정 대상 — cards/<slug>/<date>.jpg

    // 시간 고정 — today/타임스탬프를 결정론적으로(Asia/Seoul 2026-07-11).
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-11T02:00:00Z"), SEOUL);

    private final FakePendingStore pendingStore = new FakePendingStore();
    private final FakeNoteRenderer noteRenderer = new FakeNoteRenderer();
    private final FakeSlackResponder responder = new FakeSlackResponder();
    private final FakeLlmClient llmClient = new FakeLlmClient();
    private final FakeSearchClient searchClient = new FakeSearchClient();
    private final CapturingPreviewMessenger previewMessenger = new CapturingPreviewMessenger();
    private final FakePhotoDownloader photoDownloader = new FakePhotoDownloader();
    private final FakePhotoStore photoStore = new FakePhotoStore();
    private final FakePhotoBufferStore photoBufferStore = new FakePhotoBufferStore();
    private static final Duration BUFFER_WINDOW = Duration.ofMinutes(10);

    private final FakeVisionClient visionClient = new FakeVisionClient();

    private final NoteExtractor extractor = new NoteExtractor(llmClient, MochaObjectMapper.create());
    private final NoteMatcher matcher = new NoteMatcher();
    private final NoteEnricher enricher = new NoteEnricher(searchClient);
    private final PhotoInfoExtractor photoInfoExtractor = new PhotoInfoExtractor(visionClient, 4);
    private final PendingReviser reviser = new PendingReviser(llmClient, MochaObjectMapper.create());
    private final FakeSearchSessionStore searchSessionStore = new FakeSearchSessionStore();
    private final FakeTransitionSlot transitionSlot = new FakeTransitionSlot();

    private NoteRepository noteRepository() {
        return new JsonFileNoteRepository(dataDir, MochaObjectMapper.create());
    }

    private DefaultConfirmationFlow flow(NoteRepository repo) {
        return flow(repo, 0); // 재질문 상한 미설정(0) = 무제한(spec FR-20)
    }

    private DefaultConfirmationFlow flow(NoteRepository repo, int maxRequery) {
        NoteSearchService noteSearchService =
                new NoteSearchService(llmClient, MochaObjectMapper.create(), maxRequery);
        return new DefaultConfirmationFlow(
                pendingStore, repo, noteRenderer, responder,
                extractor, matcher, enricher, photoInfoExtractor, reviser, previewMessenger,
                photoDownloader, photoStore, photoBufferStore, searchSessionStore, noteSearchService,
                transitionSlot, artifactDir, BUFFER_WINDOW, clock);
    }

    private static ExtractionResult extraction(
            String coffeeName, String roastery, String origin, String myTaste, Rating rating) {
        // targetDate=null → NoteExtractor가 today로 기본화(data-model §4).
        return new ExtractionResult(coffeeName, roastery, origin, null, null, myTaste, rating, null, null, false, null);
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

    private static IncomingAction action(String actionId) {
        return new IncomingAction("U1", "C1", actionId, "slug", "1720000000.000999");
    }

    private static IncomingMessage message(String text) {
        return new IncomingMessage("U1", "C1", text, "1720000100.000001");
    }

    private static IncomingMedia media(String... filenames) {
        List<IncomingPhoto> photos = new ArrayList<>();
        for (String f : filenames) {
            photos.add(new IncomingPhoto("https://slack/" + f, f));
        }
        return new IncomingMedia("U1", "C1", photos, "1720000100.000002");
    }

    // --- T3-6: 신규 파이프라인(startNewNote) ---

    @Test
    @DisplayName("AC-1: 한 줄 메시지 → 추출·매칭·보강 후 미리보기가 전송되고 preview_ts가 pending에 반영된다")
    void startNewNoteSendsPreview() {
        NoteRepository repo = noteRepository();
        llmClient.canned = extraction("커피베라 예가체프", "커피베라", null, "새콤하고 좋았다", Rating.GOOD);

        flow(repo).startNewNote(message("커피베라 예가체프 마셨는데 새콤하고 좋았다"));

        // 미리보기가 실제로 발행됐다(AC-1).
        assertNotNull(previewMessenger.published, "미리보기가 전송되어야 한다");
        Note draft = previewMessenger.published.draft();
        assertEquals("커피베라 예가체프", draft.coffeeName().value());
        assertEquals(1, draft.entries().size(), "이번 시음 엔트리 1건이 조립된다");
        assertEquals(LocalDate.of(2026, 7, 11), draft.entries().get(0).date(), "target_date가 today로 기본화된다");
        assertEquals(MatchInfo.MatchType.NEW, previewMessenger.published.match().type(), "후보 없음 → 신규 판정");

        // put 2회: 전송 전(preview_ts=null) → 전송 후(확정 ts) 재저장.
        assertEquals(2, pendingStore.puts.size());
        assertNull(pendingStore.puts.get(0).previewTs());
        assertEquals(previewMessenger.ts, pendingStore.puts.get(1).previewTs(), "확정된 preview_ts가 pending에 반영된다");

        // AC-4: 미리보기 전 어떤 노트 JSON도 만들어지지 않는다.
        assertTrue(repo.findAll().isEmpty(), "미리보기 단계는 노트 JSON을 만들지 않는다(AC-4)");
    }

    @Test
    @DisplayName("AC-12: 검색이 무결과여도 사용자 값만으로 미리보기가 진행되고 미언급 필드는 빈 채 남는다")
    void startNewNoteProceedsWithoutSearchResults() {
        NoteRepository repo = noteRepository();
        llmClient.canned = extraction("커피베라 예가체프", "커피베라", null, "새콤함", Rating.GOOD);
        searchClient.canned = SearchResult.empty(); // 무결과

        flow(repo).startNewNote(message("커피베라 예가체프 마셨어"));

        assertNotNull(previewMessenger.published, "무결과여도 미리보기는 진행된다(AC-12)");
        Note draft = previewMessenger.published.draft();
        assertEquals("커피베라", draft.roastery().value());
        assertEquals(Source.USER, draft.roastery().source());
        assertNull(draft.origin(), "검색이 못 찾은 미언급 필드는 빈 채 남는다");
        assertTrue(responder.messages.isEmpty(), "정상 진행이면 오류 안내가 없다");
    }

    @Test
    @DisplayName("AC-2/V-6: 사용자 명시 필드는 검색 값으로 덮이지 않고(source=user), 빈 필드만 source=search로 보강된다")
    void startNewNoteKeepsUserFieldsAndEnrichesEmptyOnes() {
        NoteRepository repo = noteRepository();
        // 사용자는 로스터리만 말함. origin은 미언급 → 검색 보강 대상.
        llmClient.canned = extraction("예가체프", "커피베라", null, "새콤함", Rating.GOOD);
        searchClient.canned = new SearchResult(
                "다른로스터리", "에티오피아", null, null, List.of(), List.of("https://example.com/y"));

        flow(repo).startNewNote(message("커피베라 예가체프 새콤했어"));

        Note draft = previewMessenger.published.draft();
        // V-6/AC-3: 사용자가 말한 로스터리는 검색 값("다른로스터리")으로 덮이지 않는다.
        assertEquals("커피베라", draft.roastery().value());
        assertEquals(Source.USER, draft.roastery().source());
        // 빈 필드(origin)는 검색으로 채워지고 source=search로 마킹된다(AC-2 재료).
        assertEquals("에티오피아", draft.origin().value());
        assertEquals(Source.SEARCH, draft.origin().source());
        assertTrue(draft.sources().contains("https://example.com/y"), "검색 참조 링크가 병합된다");
    }

    @Test
    @DisplayName("plan §7: 추출(LLM) 실패 → 오류 안내 + pending 미생성, 노트 JSON 무변경")
    void startNewNoteReportsFailureWithoutPending() {
        NoteRepository repo = noteRepository();
        llmClient.failure = new LlmException("추출 실패");

        flow(repo).startNewNote(message("커피베라 예가체프 마셨어"));

        assertTrue(pendingStore.puts.isEmpty(), "실패 시 pending을 만들지 않는다");
        assertTrue(repo.findAll().isEmpty(), "실패 시 노트 JSON도 없다");
        assertEquals(List.of(DefaultConfirmationFlow.NEW_NOTE_FAILED), responder.messages);
    }

    @Test
    @DisplayName("전송 실패 → 절반만 만들어진 pending을 폐기하고 오류 안내한다")
    void startNewNoteClearsPendingOnPublishFailure() {
        NoteRepository repo = noteRepository();
        llmClient.canned = extraction("예가체프", "커피베라", null, "새콤함", Rating.GOOD);
        previewMessenger.fail = true;

        flow(repo).startNewNote(message("커피베라 예가체프 마셨어"));

        assertEquals(1, pendingStore.clearCount, "전송 실패 시 남은 pending을 폐기한다");
        assertTrue(pendingStore.get("U1").isEmpty(), "미리보기 없으면 pending도 없다");
        assertEquals(List.of(DefaultConfirmationFlow.NEW_NOTE_FAILED), responder.messages);
    }

    // --- TΔ3(changes/0011): 의도별 안내 응답 — 게이트는 라우터 층으로 상향됐다(ADR-24 구현 배치) ---

    @Test
    @DisplayName("AC-20: other 안내(guideNotARecord) → 추출·보강·pending·미리보기 없이 짧은 안내만, 버퍼도 불변")
    void guideNotARecordRespondsWithoutSideEffects() {
        NoteRepository repo = noteRepository();
        // 윈도우 내 버퍼가 있어도 안내 응답은 건드리지 않아야 한다(종전 게이트 차단과 동일 계약).
        photoBufferStore.setBuffer(new PhotoBuffer(OffsetDateTime.now(clock), List.of("a.jpg")));

        flow(repo).guideNotARecord(message("안녕! 뭐하는 봇이야?"));

        assertTrue(pendingStore.puts.isEmpty(), "other면 pending을 만들지 않는다");
        assertNull(previewMessenger.published, "other면 미리보기가 없다");
        assertTrue(repo.findAll().isEmpty(), "other면 노트 JSON도 없다");
        assertEquals(List.of(DefaultConfirmationFlow.NOT_A_RECORD), responder.messages, "짧은 안내로 응답한다");
        assertEquals(0, photoBufferStore.clearCount, "안내 응답은 버퍼를 건드리지 않는다");
        assertEquals(0, photoStore.discardCount, "안내 응답은 스테이징을 폐기하지 않는다");
    }

    @Test
    @DisplayName("AC-30: record 의도 + 대기 존재(guidePendingExists) → 대기 불변 + 새 대기 미생성, 안내만")
    void guidePendingExistsLeavesPendingUntouched() {
        NoteRepository repo = noteRepository();
        PendingNote pending = pendingWith("coffeevera-yirgacheffe");
        pendingStore.setPending(pending);

        flow(repo).guidePendingExists(message("어제 마신 다른 커피도 기록해줘"));

        assertEquals(List.of(DefaultConfirmationFlow.PENDING_EXISTS), responder.messages, "먼저 저장/취소 안내");
        assertTrue(pendingStore.puts.isEmpty(), "대기를 덮어쓰지 않는다(대기 불변)");
        assertEquals(0, pendingStore.clearCount, "대기를 폐기하지 않는다");
        assertNull(previewMessenger.published, "새 미리보기를 만들지 않는다");
        assertTrue(repo.findAll().isEmpty(), "노트 JSON도 없다");
    }

    @Test
    @DisplayName("FR-17: revise 의도 + 대기 없음(guideNothingToRevise) → 안내만, 어떤 상태도 만들지 않는다")
    void guideNothingToReviseRespondsOnly() {
        NoteRepository repo = noteRepository();

        flow(repo).guideNothingToRevise(message("산미는 낮음으로 바꿔줘"));

        assertEquals(List.of(DefaultConfirmationFlow.NOTHING_TO_REVISE), responder.messages);
        assertTrue(pendingStore.puts.isEmpty(), "pending을 만들지 않는다");
        assertNull(previewMessenger.published, "미리보기가 없다");
    }

    // --- T3-7: pending 수정 오케스트레이션(revisePending) ---

    @Test
    @DisplayName("AC-5: 수정 텍스트 반영 → 같은 미리보기를 edit로 갱신, 엔트리 미생성·노트 JSON 무변경")
    void revisePendingUpdatesPreviewWithoutNewEntry() {
        NoteRepository repo = noteRepository();
        PendingNote pending = pendingWith("coffeevera-yirgacheffe");
        pendingStore.setPending(pending);
        // 사용자가 감상만 바꿈 → my_taste 패치만 실린다.
        llmClient.cannedRevision = new RevisionResult(null, null, null, null, null, null, "산미가 낮아 부드러웠다", null);

        flow(repo).revisePending(message("산미는 낮음으로"), pending);

        // 미리보기가 갱신 발행됐고 preview_ts가 보존돼(=edit) 같은 메시지를 고친다.
        assertNotNull(previewMessenger.published, "수정 후 미리보기가 갱신 발행되어야 한다");
        assertEquals(pending.previewTs(), previewMessenger.published.previewTs(),
                "preview_ts 보존 → 재전송이 아닌 edit로 갱신한다");
        // AC-5: 엔트리는 새로 만들지 않고 제자리 갱신한다.
        Note draft = previewMessenger.published.draft();
        assertEquals(1, draft.entries().size(), "수정은 엔트리를 새로 만들지 않는다(AC-5)");
        assertEquals("산미가 낮아 부드러웠다", draft.entries().get(0).myTaste(), "감상이 제자리 갱신된다");

        // 갱신본이 영속화된다(재시작 생존).
        assertEquals(1, pendingStore.puts.size(), "수정 갱신본을 pending에 재저장한다");
        assertEquals("산미가 낮아 부드러웠다", pendingStore.puts.get(0).draft().entries().get(0).myTaste());
        // 미리보기 단계이므로 노트 JSON은 손대지 않는다(AC-4).
        assertTrue(repo.findAll().isEmpty(), "수정 반영은 노트 JSON을 만들지 않는다");
        assertTrue(responder.messages.isEmpty(), "정상 수정이면 오류 안내가 없다");
    }

    @Test
    @DisplayName("AC-2/V-6: 검색 보강 필드를 수정하면 source=user로 승격된다")
    void revisePendingPromotesSearchFieldToUser() {
        NoteRepository repo = noteRepository();
        PendingNote pending = pendingWith("coffeevera-yirgacheffe"); // origin=Sourced.search("에티오피아")
        pendingStore.setPending(pending);
        llmClient.cannedRevision = new RevisionResult(null, null, "콜롬비아", null, null, null, null, null);

        flow(repo).revisePending(message("원산지는 콜롬비아야"), pending);

        Note draft = previewMessenger.published.draft();
        assertEquals("콜롬비아", draft.origin().value());
        assertEquals(Source.USER, draft.origin().source(), "수정된 필드는 user로 승격된다(AC-2 재료)");
    }

    @Test
    @DisplayName("plan §7: 수정 병합 실패 → 오류 안내, 기존 pending 보존(폐기 안 함)")
    void revisePendingKeepsPendingOnFailure() {
        NoteRepository repo = noteRepository();
        PendingNote pending = pendingWith("coffeevera-yirgacheffe");
        pendingStore.setPending(pending);
        llmClient.failure = new LlmException("수정 병합 실패");

        flow(repo).revisePending(message("산미는 낮음으로"), pending);

        assertTrue(pendingStore.puts.isEmpty(), "실패 시 갱신본을 저장하지 않는다");
        assertEquals(0, pendingStore.clearCount, "실패해도 기존 pending은 폐기하지 않는다");
        assertTrue(pendingStore.get("U1").isPresent(), "기존 확인 대기 노트가 그대로 남는다");
        assertNull(previewMessenger.published, "수정 실패 시 미리보기 갱신도 없다");
        assertEquals(List.of(DefaultConfirmationFlow.REVISE_FAILED), responder.messages);
    }

    // --- T3-5: [저장]/[취소] 커밋 ---

    @Test
    @DisplayName("AC-Δ1: [저장] 커밋 → pending clear → 방금 엔트리 카드 증분 렌더 → 카드 JPG 배달(file:// 경로 텍스트 아님)")
    void confirmSaveCommitsClearsAndDeliversCard() {
        NoteRepository repo = noteRepository();
        pendingStore.setPending(pendingWith("coffeevera-yirgacheffe"));

        flow(repo).confirmSave(action(DefaultConversationRouter.ACTION_SAVE));

        // 커밋: 노트 JSON이 생성되고 엔트리 1건이 담긴다.
        Optional<Note> saved = repo.findBySlug("coffeevera-yirgacheffe");
        assertTrue(saved.isPresent(), "저장된 노트 JSON이 있어야 한다");
        assertEquals(1, saved.get().entries().size());
        assertTrue(dataDir.resolve("notes/coffeevera-yirgacheffe.json").toFile().isFile());

        assertEquals(1, pendingStore.clearCount, "커밋 후 pending을 폐기한다");
        // AC-Δ7 증분: 방금 그 (slug,date) 엔트리 카드 1장만 굽는다(전체 재래스터화 없음).
        assertEquals(List.of("coffeevera-yirgacheffe/2026-07-11"), noteRenderer.entryCards,
                "커밋 후 방금 엔트리 카드만 증분 렌더한다");
        assertEquals(0, noteRenderer.renderAllCount, "저장 시점은 전체 리렌더를 트리거하지 않는다");
        // AC-Δ1: 카드 JPG를 파일로 채널에 배달한다 — file:// 경로 텍스트 응답이 아니다.
        assertEquals(1, responder.images.size(), "방금 엔트리 카드 JPG를 채널에 올린다");
        assertEquals(Path.of("cards", "coffeevera-yirgacheffe", "2026-07-11.jpg"), responder.images.get(0));
        assertEquals(List.of(DefaultConfirmationFlow.SAVE_DONE_CAPTION), responder.captions);
        assertTrue(responder.messages.isEmpty(), "정상 배달이면 폴백 텍스트가 없다");
    }

    @Test
    @DisplayName("AC-Δ6: 카드 렌더 실패 → 노트 JSON 저장은 유지되고 안내 텍스트로 폴백한다")
    void confirmSaveKeepsCommitWhenRenderFails() {
        NoteRepository repo = noteRepository();
        pendingStore.setPending(pendingWith("coffeevera-yirgacheffe"));
        noteRenderer.renderFailure = new IllegalStateException("Chromium 미기동");

        flow(repo).confirmSave(action(DefaultConversationRouter.ACTION_SAVE));

        assertTrue(repo.findBySlug("coffeevera-yirgacheffe").isPresent(), "렌더 실패해도 저장은 유지된다(AC-18)");
        assertEquals(1, pendingStore.clearCount, "커밋은 완료됐다");
        assertTrue(responder.images.isEmpty(), "카드는 배달되지 못했다");
        assertEquals(List.of(DefaultConfirmationFlow.SAVE_DONE_NO_IMAGE), responder.messages, "안내 텍스트로 폴백한다");
    }

    @Test
    @DisplayName("AC-Δ6: 카드 전송(files.upload) 실패 → 저장은 유지되고 안내 텍스트로 폴백한다")
    void confirmSaveKeepsCommitWhenUploadFails() {
        NoteRepository repo = noteRepository();
        pendingStore.setPending(pendingWith("coffeevera-yirgacheffe"));
        responder.imageFailure = new IllegalStateException("files.upload 실패");

        flow(repo).confirmSave(action(DefaultConversationRouter.ACTION_SAVE));

        assertTrue(repo.findBySlug("coffeevera-yirgacheffe").isPresent(), "업로드 실패해도 저장은 유지된다(AC-18)");
        assertEquals(1, noteRenderer.entryCards.size(), "카드는 렌더됐다(전송에서 실패)");
        assertEquals(List.of(DefaultConfirmationFlow.SAVE_DONE_NO_IMAGE), responder.messages, "안내 텍스트로 폴백한다");
    }

    @Test
    @DisplayName("V-7: 만료/부재 pending에 [저장] → 커밋하지 않고 만료 안내한다")
    void confirmSaveRejectsWhenNoPending() {
        NoteRepository repo = noteRepository();
        // get()이 빈 Optional을 준다(만료분은 store가 만료 처리 — data-model V-7).
        pendingStore.setPending(null);

        flow(repo).confirmSave(action(DefaultConversationRouter.ACTION_SAVE));

        assertTrue(repo.findAll().isEmpty(), "만료/부재 시 어떤 노트도 저장되지 않는다");
        assertTrue(noteRenderer.entryCards.isEmpty(), "커밋이 없으면 카드 렌더·배달도 없다");
        assertTrue(responder.images.isEmpty());
        assertEquals(List.of(DefaultConfirmationFlow.NOTHING_TO_SAVE), responder.messages);
    }

    @Test
    @DisplayName("AC-4: [취소] → pending 폐기, 어떤 노트 JSON도 생성·변경되지 않는다")
    void cancelDiscardsPendingWithoutSaving() {
        NoteRepository repo = noteRepository();
        pendingStore.setPending(pendingWith("coffeevera-yirgacheffe"));

        flow(repo).cancel(action(DefaultConversationRouter.ACTION_CANCEL));

        assertEquals(1, pendingStore.clearCount, "취소는 pending을 폐기한다");
        assertTrue(repo.findAll().isEmpty(), "취소 시 저장은 일어나지 않는다(AC-4)");
        assertTrue(noteRenderer.entryCards.isEmpty());
        assertEquals(List.of(DefaultConfirmationFlow.CANCELED), responder.messages);
    }

    @Test
    @DisplayName("손상된 pending(slug 결손)에 [저장] → 저장하지 않고 방어 안내한다")
    void confirmSaveRejectsBrokenPending() {
        NoteRepository repo = noteRepository();
        pendingStore.setPending(pendingWith(null)); // slug 미할당 draft

        flow(repo).confirmSave(action(DefaultConversationRouter.ACTION_SAVE));

        assertTrue(repo.findAll().isEmpty(), "slug 없는 draft는 저장하지 않는다");
        assertTrue(noteRenderer.entryCards.isEmpty());
        assertEquals(0, pendingStore.clearCount, "손상 pending은 커밋 clear 대상이 아니다");
        assertEquals(List.of(DefaultConfirmationFlow.BROKEN_PENDING), responder.messages);
        assertFalse(responder.messages.isEmpty());
    }

    // --- TΔ2: 확인 버튼 1회 소진(changes/0009, ADR-20, AC-22) ---

    @Test
    @DisplayName("AC-Δ1: [저장] 완료 시 버튼 소진(finalizePreview) 호출 — '저장 완료' 상태 문구로 교체된다")
    void confirmSaveFinalizesPreviewButtons() {
        NoteRepository repo = noteRepository();
        PendingNote pending = pendingWith("coffeevera-yirgacheffe");
        pendingStore.setPending(pending);

        flow(repo).confirmSave(action(DefaultConversationRouter.ACTION_SAVE));

        // 커밋·배달 이후 버튼 소진이 정확히 1회, "저장 완료" 문구로 호출된다.
        assertEquals(List.of(DefaultConfirmationFlow.FINALIZE_SAVED), responder.finalizeStatuses);
        // 필드 재조립 대상 pending(previewTs 보유)이 넘어간다 — 필드 내용 유지의 재료.
        assertEquals(1, responder.finalizePendings.size());
        assertEquals(pending.previewTs(), responder.finalizePendings.get(0).previewTs(),
                "버튼 소진 대상 미리보기 메시지(previewTs)가 넘어가야 한다");
    }

    @Test
    @DisplayName("AC-Δ1: [취소] 완료 시 버튼 소진(finalizePreview) 호출 — '취소됨' 상태 문구로 교체된다")
    void cancelFinalizesPreviewButtons() {
        NoteRepository repo = noteRepository();
        pendingStore.setPending(pendingWith("coffeevera-yirgacheffe"));

        flow(repo).cancel(action(DefaultConversationRouter.ACTION_CANCEL));

        assertEquals(List.of(DefaultConfirmationFlow.FINALIZE_CANCELED), responder.finalizeStatuses);
    }

    @Test
    @DisplayName("AC-Δ2: 버튼 소진(chat.update) 실패를 주입해도 노트 커밋·카드 배달은 정상 완료된다(로그만)")
    void confirmSaveKeepsCommitAndDeliveryWhenFinalizeFails() {
        NoteRepository repo = noteRepository();
        pendingStore.setPending(pendingWith("coffeevera-yirgacheffe"));
        responder.finalizeFailure = new IllegalStateException("chat.update 실패");

        flow(repo).confirmSave(action(DefaultConversationRouter.ACTION_SAVE));

        // 버튼 소진이 던져도 커밋과 카드 배달은 불변이어야 한다(ADR-20 POLICY).
        assertTrue(repo.findBySlug("coffeevera-yirgacheffe").isPresent(), "버튼 소진 실패해도 저장은 유지된다");
        assertEquals(1, pendingStore.clearCount, "커밋은 완료됐다");
        assertEquals(1, responder.images.size(), "카드 배달도 정상 완료된다");
        assertTrue(responder.messages.isEmpty(), "정상 배달이면 폴백 텍스트가 없다");
    }

    @Test
    @DisplayName("AC-Δ5(회귀 가드): [저장] 커밋 → pending clear → 카드 배달 → 버튼 소진 순서가 종전과 동일하다")
    void confirmSavePreservesCommitFlowOrder() {
        List<String> order = new ArrayList<>();
        NoteRepository repo = new RecordingNoteRepository(noteRepository(), order);
        pendingStore.order = order;
        responder.order = order;
        pendingStore.setPending(pendingWith("coffeevera-yirgacheffe"));

        flow(repo).confirmSave(action(DefaultConversationRouter.ACTION_SAVE));

        // 버튼 소진은 커밋·배달을 되돌리지 않는 "이후의 표시 갱신"일 뿐 — 커밋·clear·배달 순서와 조건은 불변이어야 한다(AC-Δ5, ADR-20).
        assertEquals(List.of("commit", "clear", "deliver", "finalize"), order,
                "저장 커밋 → pending clear → 카드 배달 → 버튼 소진 순서가 유지되어야 한다");
    }

    @Test
    @DisplayName("V-7: 만료/부재 pending에 [취소] → 버튼 소진 대상이 없어 finalizePreview를 호출하지 않는다")
    void cancelSkipsFinalizeWhenNoPending() {
        NoteRepository repo = noteRepository();
        pendingStore.setPending(null); // get()이 빈 Optional

        flow(repo).cancel(action(DefaultConversationRouter.ACTION_CANCEL));

        assertTrue(responder.finalizeStatuses.isEmpty(), "갱신할 미리보기가 없으면 버튼 소진을 호출하지 않는다");
        assertEquals(List.of(DefaultConfirmationFlow.CANCELED), responder.messages);
    }

    // --- T4-2: 사진 버퍼 그룹핑(FR-10, AC-8) ---

    @Test
    @DisplayName("AC-8 전반: 윈도우 내 사진 버퍼 + 텍스트 → 하나의 pending에 사진 3장이 묶인다")
    void startNewNoteAbsorbsBufferPhotosWithinWindow() {
        NoteRepository repo = noteRepository();
        llmClient.canned = extraction("커피베라 예가체프", "커피베라", null, "새콤함", Rating.GOOD);
        // 방금(윈도우 내) 도착해 버퍼링된 사진 3장.
        photoBufferStore.setBuffer(new PhotoBuffer(
                OffsetDateTime.now(clock), List.of("a.jpg", "b.jpg", "c.jpg")));

        flow(repo).startNewNote(message("커피베라 예가체프 마셨어"));

        Note draft = previewMessenger.published.draft();
        List<String> photos = draft.entries().get(0).photos();
        assertEquals(3, photos.size(), "윈도우 내 사진 3장이 이번 노트로 묶인다(AC-8 전반)");
        assertTrue(photos.get(0).startsWith("photos/" + draft.slug() + "/"),
                "미리보기 사진 경로는 확정 저장 경로 규칙을 따른다: " + photos.get(0));
        assertEquals(1, photoBufferStore.clearCount, "사진이 pending으로 이관되면 버퍼를 비운다");
        assertEquals(0, photoStore.discardCount, "윈도우 내 버퍼는 폐기하지 않는다(저장 시 commit 대상)");
    }

    // --- TΔ3: 수신 사진 OCR([2.5], FR-19/ADR-23) ---

    @Test
    @DisplayName("AC-Δ4: 원두 봉투 사진 → 빈 커피명·필드를 (사진) source=photo로 채운다(1콜)")
    void startNewNoteFillsEmptyFieldsFromPhotoOcr() {
        NoteRepository repo = noteRepository();
        // 텍스트엔 커피명·원산지 없음 — 사진에서 읽어야 한다.
        llmClient.canned = extraction(null, null, null, "달큰하고 좋았다", Rating.GOOD);
        photoStore.stage("U1", "bag.jpg", new byte[]{1, 2, 3});
        photoBufferStore.setBuffer(new PhotoBuffer(OffsetDateTime.now(clock), List.of("bag.jpg")));
        visionClient.canned = new VisionExtraction(
                "커피베라 예가체프", "커피베라", "에티오피아", null, null, List.of("자스민"));

        flow(repo).startNewNote(message("이거 달큰하고 좋았어"));

        assertEquals(1, visionClient.calls, "묶인 사진은 vision 1콜로 전달된다(ADR-23)");
        Note draft = previewMessenger.published.draft();
        assertEquals(Sourced.photo("커피베라 예가체프"), draft.coffeeName(), "빈 커피명은 사진 값으로 채운다(AC-Δ4)");
        assertEquals(Sourced.photo("커피베라"), draft.roastery());
        assertEquals(Sourced.photo("에티오피아"), draft.origin());
        assertEquals(Source.PHOTO, draft.officialNotes().source(), "공식 노트도 사진 유래(source=photo)");
    }

    @Test
    @DisplayName("AC-Δ4/V-6: 사용자가 말한 커피명·필드는 사진 값과 충돌해도 사용자 값이 유지된다(불가침)")
    void startNewNoteKeepsUserFieldsOverPhoto() {
        NoteRepository repo = noteRepository();
        llmClient.canned = extraction("커피베라 예가체프", null, null, "새콤함", Rating.GOOD);
        photoStore.stage("U1", "bag.jpg", new byte[]{1});
        photoBufferStore.setBuffer(new PhotoBuffer(OffsetDateTime.now(clock), List.of("bag.jpg")));
        // 사진은 다른 커피명·로스터리를 읽었다 — 사용자 값이 이겨야 한다(V-6).
        visionClient.canned = new VisionExtraction(
                "모모스 와이키키", "모모스", "브라질", null, null, List.of());

        flow(repo).startNewNote(message("커피베라 예가체프 새콤했어"));

        Note draft = previewMessenger.published.draft();
        assertEquals(Sourced.user("커피베라 예가체프"), draft.coffeeName(), "사용자 커피명 불가침(V-6)");
        assertEquals(Sourced.photo("모모스"), draft.roastery(), "user 미언급 로스터리는 사진 값으로 채움");
        assertEquals(Sourced.photo("브라질"), draft.origin());
    }

    @Test
    @DisplayName("AC-Δ5: 사진 OCR 실패(vision 예외)여도 오류 없이 파이프라인이 정상 진행된다(첨부로만)")
    void startNewNoteProceedsWhenPhotoOcrFails() {
        NoteRepository repo = noteRepository();
        llmClient.canned = extraction("커피베라 예가체프", "커피베라", null, "새콤함", Rating.GOOD);
        photoStore.stage("U1", "bag.jpg", new byte[]{1});
        photoBufferStore.setBuffer(new PhotoBuffer(OffsetDateTime.now(clock), List.of("bag.jpg")));
        visionClient.toThrow = new RuntimeException("vision timeout");

        flow(repo).startNewNote(message("커피베라 예가체프 새콤했어"));

        assertNotNull(previewMessenger.published, "OCR 실패여도 미리보기는 정상 전송된다(AC-Δ5)");
        Note draft = previewMessenger.published.draft();
        assertEquals(1, draft.entries().get(0).photos().size(), "사진은 첨부로만 유지된다(흐름 불변)");
        assertEquals(Sourced.user("커피베라 예가체프"), draft.coffeeName());
    }

    @Test
    @DisplayName("AC-8 후반: 윈도우 밖 사진 버퍼는 이 텍스트에 묶이지 않고 스테이징이 폐기된다(새 흐름)")
    void startNewNoteDropsStaleBufferPhotos() {
        NoteRepository repo = noteRepository();
        llmClient.canned = extraction("커피베라 예가체프", "커피베라", null, "새콤함", Rating.GOOD);
        // 20분 전(윈도우 밖) 사진 → 버려진 것.
        photoBufferStore.setBuffer(new PhotoBuffer(
                OffsetDateTime.now(clock).minusMinutes(20), List.of("old.jpg")));

        flow(repo).startNewNote(message("커피베라 예가체프 마셨어"));

        Note draft = previewMessenger.published.draft();
        assertTrue(draft.entries().get(0).photos().isEmpty(), "윈도우 밖 사진은 이 노트에 묶이지 않는다(AC-8 후반)");
        assertEquals(1, photoStore.discardCount, "버려진 스테이징을 정리한다");
    }

    @Test
    @DisplayName("T4-2: pending 없이 사진만 수신 → 버퍼에 쌓고 미리보기는 아직 보내지 않는다")
    void receiveMediaBuffersWhenNoPending() {
        NoteRepository repo = noteRepository();

        flow(repo).receiveMedia(media("a.jpg", "b.jpg", "c.jpg"));

        assertEquals(3, photoDownloader.downloaded.size(), "3장 모두 내려받는다");
        assertEquals(3, photoStore.staged.size(), "3장 모두 스테이징된다");
        assertEquals(1, photoBufferStore.puts.size(), "버퍼에 저장된다");
        assertEquals(3, photoBufferStore.puts.get(0).stagedNames().size());
        assertNull(previewMessenger.published, "텍스트가 없으니 미리보기는 아직 없다");
        assertTrue(repo.findAll().isEmpty(), "노트 JSON은 만들지 않는다");
    }

    @Test
    @DisplayName("T4-2: 진행 중 pending에 사진 수신 → 그 노트에 첨부하고 같은 미리보기를 edit로 갱신한다")
    void receiveMediaAttachesToExistingPending() {
        NoteRepository repo = noteRepository();
        PendingNote pending = pendingWith("coffeevera-yirgacheffe"); // 엔트리 photos 비어 있음
        pendingStore.setPending(pending);

        flow(repo).receiveMedia(media("a.jpg", "b.jpg"));

        Note draft = previewMessenger.published.draft();
        assertEquals(2, draft.entries().get(0).photos().size(), "진행 중 노트에 사진 2장이 첨부된다");
        assertEquals(pending.previewTs(), previewMessenger.published.previewTs(),
                "preview_ts 보존 → 재전송이 아닌 edit로 갱신한다");
        assertEquals(1, pendingStore.puts.size(), "첨부 갱신본을 pending에 재저장한다");
        assertTrue(repo.findAll().isEmpty(), "첨부는 노트 JSON을 만들지 않는다(AC-4)");
    }

    @Test
    @DisplayName("AC-8 후반: 윈도우 밖 이전 버퍼 위로 사진이 오면 옛 스테이징을 버리고 새 버퍼로 시작한다")
    void receiveMediaStartsFreshBufferAfterWindow() {
        NoteRepository repo = noteRepository();
        photoBufferStore.setBuffer(new PhotoBuffer(
                OffsetDateTime.now(clock).minusMinutes(20), List.of("old.jpg")));

        flow(repo).receiveMedia(media("new.jpg"));

        assertEquals(1, photoStore.discardCount, "윈도우 밖 이전 버퍼의 스테이징을 폐기한다");
        PhotoBuffer latest = photoBufferStore.puts.get(photoBufferStore.puts.size() - 1);
        assertEquals(List.of("new.jpg"), latest.stagedNames(), "새 사진만으로 버퍼를 다시 시작한다(옛 사진 미포함)");
    }

    @Test
    @DisplayName("T4-2: [저장] 시 스테이징 사진을 photos/<slug>/<date>/로 commit해 엔트리에 상대 경로로 담는다(V-4)")
    void confirmSaveCommitsStagedPhotos() {
        NoteRepository repo = noteRepository();
        photoStore.staged.add("a.jpg"); // 대기 중 스테이징 사진
        pendingStore.setPending(pendingWith("coffeevera-yirgacheffe")); // 엔트리 date=2026-07-11

        flow(repo).confirmSave(action(DefaultConversationRouter.ACTION_SAVE));

        Note saved = repo.findBySlug("coffeevera-yirgacheffe").orElseThrow();
        List<String> photos = saved.entries().get(0).photos();
        assertEquals(List.of("photos/coffeevera-yirgacheffe/2026-07-11/a.jpg"), photos,
                "스테이징 사진이 확정 상대 경로로 엔트리에 담긴다(V-4)");
        assertTrue(photoStore.staged.isEmpty(), "commit 후 스테이징은 비워진다");
    }

    @Test
    @DisplayName("AC-4/FR-10: [취소] 시 대기 중이던 스테이징 사진·버퍼도 함께 폐기된다")
    void cancelDiscardsStagedPhotos() {
        NoteRepository repo = noteRepository();
        photoStore.staged.add("a.jpg");
        pendingStore.setPending(pendingWith("coffeevera-yirgacheffe"));

        flow(repo).cancel(action(DefaultConversationRouter.ACTION_CANCEL));

        assertEquals(1, photoStore.discardCount, "취소는 스테이징 사진을 폐기한다");
        assertTrue(photoStore.staged.isEmpty());
        assertEquals(1, photoBufferStore.clearCount, "사진 버퍼도 함께 정리한다");
    }

    // --- TΔ5(changes/0011): 검색 세션 배선(searchNotes/endSearch, FR-20/ADR-25) ---

    private Note savedNote(NoteRepository repo, String slug, String coffeeName, String roastery, LocalDate date) {
        com.devwuu.mocha.domain.NoteMeta meta = new com.devwuu.mocha.domain.NoteMeta(
                Sourced.user(coffeeName), Sourced.user(roastery), null, null, null, null, List.of());
        Entry entry = new Entry(date, "좋았다", Rating.GOOD, null, List.of(), OffsetDateTime.now(clock));
        return repo.upsertEntry(slug, meta, entry);
    }

    @Test
    @DisplayName("AC-34: 세션 없이 search → 시작 모카 톤 안내가 결과보다 먼저 가고 세션이 저장된다")
    void searchNotesStartsSessionWithGreeting() {
        NoteRepository repo = noteRepository();
        savedNote(repo, "coffeevera-yirgacheffe", "커피베라 예가체프", "커피베라", LocalDate.of(2026, 7, 1));
        llmClient.cannedSelection = new SearchSelection(List.of()); // 무후보 → 재질문

        flow(repo).searchNotes(message("저번에 마신 거 찾아줘"));

        assertEquals(
                List.of(DefaultConfirmationFlow.SEARCH_STARTED, DefaultConfirmationFlow.SEARCH_REQUERY),
                responder.messages, "시작 안내 → 결과(재질문) 순서로 응답한다");
        assertEquals(1, searchSessionStore.puts.size(), "새 검색 세션이 저장된다");
        assertEquals(1, searchSessionStore.puts.get(0).requeryCount(), "무후보 재질문 횟수가 세션에 반영된다");
    }

    @Test
    @DisplayName("AC-31: 단일 매치 + 기존 카드 존재 → 그 카드 JPG를 재전송하고 새 렌더는 없다(파생물 재사용)")
    void searchNotesResendsExistingCard() throws Exception {
        NoteRepository repo = noteRepository();
        savedNote(repo, "coffeevera-yirgacheffe", "커피베라 예가체프", "커피베라", LocalDate.of(2026, 7, 1));
        Path card = artifactDir.resolve("cards/coffeevera-yirgacheffe/2026-07-01.jpg");
        Files.createDirectories(card.getParent());
        Files.write(card, new byte[]{1});
        llmClient.cannedSelection = new SearchSelection(List.of("coffeevera-yirgacheffe"));

        flow(repo).searchNotes(message("저번에 마신 예가체프 찾아줘"));

        assertEquals(List.of(card), responder.images, "기존 카드 경로를 그대로 재전송한다");
        assertEquals(List.of(DefaultConfirmationFlow.SEARCH_FOUND_CAPTION), responder.captions);
        assertTrue(noteRenderer.entryCards.isEmpty(), "카드가 있으면 증분 렌더도 없다(ADR-25 POLICY)");
        assertEquals(List.of("coffeevera-yirgacheffe"), searchSessionStore.puts.get(0).candidateSlugs(),
                "매치 대상이 세션 후보로 남는다(후속 선택·수정 세션 진입 재료)");
    }

    @Test
    @DisplayName("AC-31/FR-20: 단일 매치인데 카드 파일 부재 → 그 엔트리 1장만 증분 렌더해 전송한다")
    void searchNotesRendersMissingCardIncrementally() {
        NoteRepository repo = noteRepository();
        savedNote(repo, "coffeevera-yirgacheffe", "커피베라 예가체프", "커피베라", LocalDate.of(2026, 7, 1));
        llmClient.cannedSelection = new SearchSelection(List.of("coffeevera-yirgacheffe"));

        flow(repo).searchNotes(message("저번에 마신 예가체프 찾아줘"));

        assertEquals(List.of("coffeevera-yirgacheffe/2026-07-01"), noteRenderer.entryCards,
                "부재 시에만 그 엔트리 1장을 증분 렌더한다");
        assertEquals(List.of(Path.of("cards", "coffeevera-yirgacheffe", "2026-07-01.jpg")), responder.images);
    }

    @Test
    @DisplayName("AC-32: 복수 후보 → 커피명·로스터리·최근 시음일이 담긴 번호 목록 텍스트를 제시한다")
    void searchNotesListsMultipleCandidates() {
        NoteRepository repo = noteRepository();
        savedNote(repo, "coffeevera-yirgacheffe", "커피베라 예가체프", "커피베라", LocalDate.of(2026, 7, 1));
        savedNote(repo, "momos-waikiki", "모모스 와이키키", "모모스", LocalDate.of(2026, 5, 20));
        llmClient.cannedSelection = new SearchSelection(List.of("coffeevera-yirgacheffe", "momos-waikiki"));

        flow(repo).searchNotes(message("예가체프였나 와이키키였나"));

        String list = responder.messages.get(responder.messages.size() - 1);
        assertTrue(list.startsWith(DefaultConfirmationFlow.SEARCH_CANDIDATES_HEADER), "목록 머리말(모카 톤)");
        assertTrue(list.contains("1. 커피베라 예가체프 — 커피베라 (최근 2026-07-01)"), "커피명·로스터리·최근 시음일: " + list);
        assertTrue(list.contains("2. 모모스 와이키키 — 모모스 (최근 2026-05-20)"), "제시 순서 번호가 붙는다: " + list);
        assertEquals(List.of("coffeevera-yirgacheffe", "momos-waikiki"),
                searchSessionStore.puts.get(0).candidateSlugs(), "'두 번째' 선택 해석 기준이 세션에 남는다");
        assertTrue(responder.images.isEmpty(), "복수 후보는 카드를 보내지 않는다");
    }

    @Test
    @DisplayName("AC-33/FR-20: 재질문 상한 도달 → 종료 안내와 함께 세션이 폐기된다(max-requery=1)")
    void searchNotesEndsSessionAtRequeryLimit() {
        NoteRepository repo = noteRepository();
        savedNote(repo, "coffeevera-yirgacheffe", "커피베라 예가체프", "커피베라", LocalDate.of(2026, 7, 1));
        searchSessionStore.setSession(
                new SearchSession(List.of("예가체프"), List.of(), 1, OffsetDateTime.now(clock)));
        llmClient.cannedSelection = new SearchSelection(List.of()); // 또 무후보

        flow(repo, 1).searchNotes(message("몰라, 그냥 찾아봐"));

        assertEquals(List.of(DefaultConfirmationFlow.SEARCH_LIMIT_REACHED), responder.messages,
                "상한 도달 안내(진행 중 세션이라 시작 안내는 없다)");
        assertEquals(1, searchSessionStore.clearCount, "세션을 폐기한다");
        assertTrue(searchSessionStore.get("U1").isEmpty());
    }

    @Test
    @DisplayName("AC-34: end 의도(endSearch) → 세션 폐기 + 모카 톤 종료 안내")
    void endSearchClearsSessionWithFarewell() {
        NoteRepository repo = noteRepository();
        searchSessionStore.setSession(
                new SearchSession(List.of("예가체프"), List.of(), 0, OffsetDateTime.now(clock)));

        flow(repo).endSearch(message("됐어"));

        assertEquals(1, searchSessionStore.clearCount, "end는 세션을 폐기한다");
        assertTrue(searchSessionStore.get("U1").isEmpty());
        assertEquals(List.of(DefaultConfirmationFlow.SEARCH_ENDED), responder.messages);
    }

    @Test
    @DisplayName("AC-Δ1/AC-29: 검색 전 과정은 pending을 읽지도 쓰지도 않는다 — 대기 기록 불변(격리)")
    void searchNotesNeverTouchesPending() {
        NoteRepository repo = noteRepository();
        PendingNote pending = pendingWith("other-coffee");
        pendingStore.setPending(pending);
        savedNote(repo, "coffeevera-yirgacheffe", "커피베라 예가체프", "커피베라", LocalDate.of(2026, 7, 1));
        llmClient.cannedSelection = new SearchSelection(List.of("coffeevera-yirgacheffe"));

        flow(repo).searchNotes(message("저번에 마신 예가체프 찾아줘"));

        assertTrue(pendingStore.puts.isEmpty(), "검색은 pending을 쓰지 않는다(ADR-25 POLICY)");
        assertEquals(0, pendingStore.clearCount, "검색은 pending을 폐기하지 않는다");
        assertTrue(pendingStore.get("U1").isPresent(), "확인 대기 기록이 그대로 남는다(AC-29)");
        assertEquals(1, responder.images.size(), "대기 중에도 검색 응답(카드)은 정상 배달된다");
    }

    @Test
    @DisplayName("plan §7: 후보 선정(LLM) 실패 → 오류 안내만, 진행 중 세션은 유지된다")
    void searchNotesKeepsSessionOnLlmFailure() {
        NoteRepository repo = noteRepository();
        savedNote(repo, "coffeevera-yirgacheffe", "커피베라 예가체프", "커피베라", LocalDate.of(2026, 7, 1));
        searchSessionStore.setSession(
                new SearchSession(List.of("예가체프"), List.of(), 0, OffsetDateTime.now(clock)));
        llmClient.failure = new LlmException("후보 선정 실패");

        flow(repo).searchNotes(message("작년 겨울에 마신 거"));

        assertEquals(List.of(DefaultConfirmationFlow.SEARCH_FAILED), responder.messages);
        assertEquals(0, searchSessionStore.clearCount, "실패해도 세션을 잃지 않는다(plan §7)");
        assertTrue(searchSessionStore.get("U1").isPresent(), "다음 메시지로 검색을 계속할 수 있다");
        assertTrue(searchSessionStore.puts.isEmpty(), "실패 턴은 세션을 갱신하지 않는다");
    }

    // --- TΔ6(changes/0011): 과거 참조 매치 실패 → TransitionSlot 보관 + 다음 의도 재개(FR-14, ADR-26) ---

    /** references_past=true인 추출 결과 — matchedSlug가 없거나(무매칭) 존재하지 않으면 매치 실패 분기 대상. */
    private static ExtractionResult referencingExtraction(String coffeeName, String matchedSlug) {
        return new ExtractionResult(
                coffeeName, null, null, null, null, "저번처럼 새콤했다", Rating.GOOD, null, matchedSlug, true, null);
    }

    @Test
    @DisplayName("AC-36/FR-14: 과거 참조 매치 실패 → pending 미생성, 추출 결과를 전환 슬롯에 보관하고 안내만")
    void startNewNoteHoldsExtractionOnPastReferenceMiss() {
        NoteRepository repo = noteRepository();
        llmClient.canned = referencingExtraction("그때 그 커피", null); // 참조 신호 + 매칭 실패

        flow(repo).startNewNote(message("저번에 마셨던 그 커피 또 마셨어"));

        assertEquals(List.of(DefaultConfirmationFlow.REFERENCE_NOT_FOUND), responder.messages,
                "미리보기 대신 못 찾았다 안내가 간다(AC-36)");
        assertTrue(pendingStore.puts.isEmpty(), "매치 실패 분기는 pending을 만들지 않는다(FR-14)");
        assertNull(previewMessenger.published, "미리보기도 없다");
        assertTrue(repo.findAll().isEmpty(), "노트 JSON도 없다");
        assertEquals(1, transitionSlot.holds.size(), "추출 결과가 전환 슬롯에 보관된다(ADR-26)");
        ExtractionResult held = (ExtractionResult) transitionSlot.holds.get(0);
        assertEquals("그때 그 커피", held.coffeeName(), "보관분은 이번 추출 결과 그대로다");
        assertEquals(LocalDate.of(2026, 7, 11), held.targetDate(), "target_date가 today로 기본화된 상태로 보관된다");
    }

    @Test
    @DisplayName("AC-36: 보관분이 살아 있을 때 record(\"새로 기록해줘\") → 감상 재전송 없이 보관분으로 즉시 미리보기 재개")
    void startNewNoteResumesPreviewFromHeldExtraction() {
        NoteRepository repo = noteRepository();
        transitionSlot.payload = new ExtractionResult(
                "커피베라 예가체프", "커피베라", null, null, null, "저번처럼 새콤했다", Rating.GOOD, null,
                "ghost-slug", true, LocalDate.of(2026, 7, 10));
        // 재개 경로는 이 텍스트를 추출하지 않는다 — LLM이 호출되면 실패로 드러나게 한다.
        llmClient.failure = new LlmException("재개 경로는 추출을 호출하면 안 된다");

        flow(repo).startNewNote(message("새로 기록해줘"));

        assertNotNull(previewMessenger.published, "보관분으로 미리보기가 재개된다(AC-36)");
        Note draft = previewMessenger.published.draft();
        assertEquals("커피베라 예가체프", draft.coffeeName().value(), "보관분의 내용 그대로다(감상 재전송 불요)");
        assertEquals("저번처럼 새콤했다", draft.entries().get(0).myTaste());
        assertEquals(LocalDate.of(2026, 7, 10), draft.entries().get(0).date(),
                "원 발화의 target_date가 보존된다(재개 시점 today로 덮이지 않음)");
        assertEquals(MatchInfo.MatchType.NEW, previewMessenger.published.match().type(),
                "존재하지 않는 matched_slug는 신규로 폴백(재보관 없이 진행)");
        assertEquals(2, pendingStore.puts.size(), "재개는 종전 신규 흐름대로 pending을 만든다");
        assertNull(transitionSlot.payload, "보관분은 소비된다(단일 소비)");
        assertTrue(transitionSlot.holds.isEmpty(), "재개분을 슬롯에 재보관하지 않는다");
        assertTrue(responder.messages.isEmpty(), "정상 재개면 오류·안내가 없다");
    }

    @Test
    @DisplayName("AC-36: 보관분이 살아 있을 때 search(\"찾아줘\") → 검색 세션으로 넘어가고 슬롯은 폐기된다")
    void searchNotesDiscardsHeldExtraction() {
        NoteRepository repo = noteRepository();
        savedNote(repo, "coffeevera-yirgacheffe", "커피베라 예가체프", "커피베라", LocalDate.of(2026, 7, 1));
        transitionSlot.payload = referencingExtraction("그때 그 커피", null);
        llmClient.cannedSelection = new SearchSelection(List.of("coffeevera-yirgacheffe"));

        flow(repo).searchNotes(message("찾아줘"));

        assertNull(transitionSlot.payload, "search류 전환은 보관분을 폐기한다(ADR-26)");
        assertTrue(transitionSlot.takeCount >= 1, "폐기는 슬롯 소비(take)로 일어난다");
        assertEquals(1, responder.images.size(), "검색 세션은 정상 진행된다(단일 매치 카드)");
        assertTrue(pendingStore.puts.isEmpty(), "pending은 여전히 만들지 않는다");
    }

    @Test
    @DisplayName("AC-36: 전환 슬롯 TTL 폐기 후 record → 보관분 없이 이번 텍스트의 일반 신규 처리로 흐른다")
    void startNewNoteFallsBackToNormalAfterSlotTtl() {
        NoteRepository repo = noteRepository();
        transitionSlot.payload = referencingExtraction("그때 그 커피", null);
        transitionSlot.expired = true; // TTL 경과 — take()가 빈 Optional로 수렴(실 TTL은 InMemoryTransitionSlotTest)
        llmClient.canned = extraction("모모스 와이키키", "모모스", null, "고소했다", Rating.GOOD);

        flow(repo).startNewNote(message("모모스 와이키키 마셨는데 고소했다"));

        assertNotNull(previewMessenger.published, "TTL 폐기 후에는 일반 신규 처리로 미리보기가 진행된다");
        assertEquals("모모스 와이키키", previewMessenger.published.draft().coffeeName().value(),
                "만료된 보관분이 아니라 이번 텍스트의 추출 결과가 쓰인다");
        assertTrue(transitionSlot.holds.isEmpty(), "이번 추출은 참조 신호가 없으니 다시 보관되지 않는다");
    }

    @Test
    @DisplayName("AC-Δ8(회귀 가드): references_past=false 새 기록은 종전대로 즉시 미리보기 — 슬롯 보관·안내 없음")
    void startNewNoteWithoutReferenceKeepsImmediatePreview() {
        NoteRepository repo = noteRepository();
        llmClient.canned = extraction("커피베라 예가체프", "커피베라", null, "새콤함", Rating.GOOD); // referencesPast=false

        flow(repo).startNewNote(message("커피베라 예가체프 마셨는데 새콤했다"));

        assertNotNull(previewMessenger.published, "참조 신호 없는 새 기록은 바로 미리보기다(AC-1 마찰 불변)");
        assertTrue(transitionSlot.holds.isEmpty(), "슬롯에 아무것도 보관하지 않는다");
        assertTrue(responder.messages.isEmpty(), "못 찾았다 안내도 없다");
        assertEquals(2, pendingStore.puts.size(), "종전 신규 흐름 그대로 pending이 만들어진다");
    }

    @Test
    @DisplayName("FR-14: references_past=true여도 매치에 성공하면 분기 없이 기존 노트 흐름으로 미리보기가 진행된다")
    void startNewNoteWithReferenceMatchSuccessProceedsNormally() {
        NoteRepository repo = noteRepository();
        savedNote(repo, "coffeevera-yirgacheffe", "커피베라 예가체프", "커피베라", LocalDate.of(2026, 7, 1));
        llmClient.canned = referencingExtraction("커피베라 예가체프", "coffeevera-yirgacheffe"); // 참조 + 매치 성공

        flow(repo).startNewNote(message("저번에 마신 예가체프 또 마셨어"));

        assertNotNull(previewMessenger.published, "매치에 성공한 참조는 종전 기존 노트 흐름이다");
        assertEquals(MatchInfo.MatchType.EXISTING, previewMessenger.published.match().type());
        assertTrue(transitionSlot.holds.isEmpty(), "매치 성공이면 슬롯을 쓰지 않는다");
        assertTrue(responder.messages.isEmpty());
    }
}
