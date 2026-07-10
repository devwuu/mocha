package com.devwuu.mocha.slack;

import com.devwuu.mocha.domain.Entry;
import com.devwuu.mocha.domain.MatchInfo;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.PendingNote;
import com.devwuu.mocha.domain.Rating;
import com.devwuu.mocha.domain.Source;
import com.devwuu.mocha.domain.Sourced;
import com.devwuu.mocha.json.MochaObjectMapper;
import com.devwuu.mocha.llm.LlmClient;
import com.devwuu.mocha.llm.LlmException;
import com.devwuu.mocha.llm.LlmRequest;
import com.devwuu.mocha.llm.SearchClient;
import com.devwuu.mocha.llm.SearchQuery;
import com.devwuu.mocha.llm.SearchResult;
import com.devwuu.mocha.pipeline.ExtractionResult;
import com.devwuu.mocha.pipeline.NoteEnricher;
import com.devwuu.mocha.pipeline.NoteExtractor;
import com.devwuu.mocha.pipeline.NoteMatcher;
import com.devwuu.mocha.pipeline.PendingReviser;
import com.devwuu.mocha.pipeline.RevisionResult;
import com.devwuu.mocha.render.SiteRenderer;
import com.devwuu.mocha.repository.JsonFileNoteRepository;
import com.devwuu.mocha.repository.NoteRepository;
import com.devwuu.mocha.repository.PendingStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
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
            pending = Optional.empty();
        }
    }

    /** renderAll 호출 횟수를 캡처하는 fake. */
    private static final class FakeSiteRenderer implements SiteRenderer {
        int renderCount = 0;

        @Override
        public void renderAll() {
            renderCount++;
        }
    }

    /** 전송된 안내 메시지를 캡처하는 fake. */
    private static final class FakeSlackResponder implements SlackResponder {
        final List<String> messages = new ArrayList<>();

        @Override
        public void post(String channelId, String text) {
            messages.add(text);
        }
    }

    /**
     * 추출/수정 응답을 미리 지정하는 fake LLM — 계약(구조)만 검증, 생성 자체는 대체(CLAUDE.md §5.3).
     * 요청의 responseType으로 추출({@link ExtractionResult})과 수정({@link RevisionResult}) 응답을 분기한다.
     */
    private static final class FakeLlmClient implements LlmClient {
        ExtractionResult canned;
        RevisionResult cannedRevision;
        RuntimeException failure;

        @SuppressWarnings("unchecked")
        @Override
        public <T> T complete(LlmRequest<T> request) {
            if (failure != null) {
                throw failure;
            }
            if (request.responseType() == RevisionResult.class) {
                return (T) cannedRevision;
            }
            return (T) canned;
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

    @TempDir
    Path dataDir;

    // 시간 고정 — today/타임스탬프를 결정론적으로(Asia/Seoul 2026-07-11).
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private final Clock clock = Clock.fixed(Instant.parse("2026-07-11T02:00:00Z"), SEOUL);

    private final FakePendingStore pendingStore = new FakePendingStore();
    private final FakeSiteRenderer siteRenderer = new FakeSiteRenderer();
    private final FakeSlackResponder responder = new FakeSlackResponder();
    private final FakeLlmClient llmClient = new FakeLlmClient();
    private final FakeSearchClient searchClient = new FakeSearchClient();
    private final CapturingPreviewMessenger previewMessenger = new CapturingPreviewMessenger();

    private final NoteExtractor extractor = new NoteExtractor(llmClient, MochaObjectMapper.create());
    private final NoteMatcher matcher = new NoteMatcher();
    private final NoteEnricher enricher = new NoteEnricher(searchClient);
    private final PendingReviser reviser = new PendingReviser(llmClient, MochaObjectMapper.create());

    private NoteRepository noteRepository() {
        return new JsonFileNoteRepository(dataDir, MochaObjectMapper.create());
    }

    private DefaultConfirmationFlow flow(NoteRepository repo) {
        return new DefaultConfirmationFlow(
                pendingStore, repo, siteRenderer, responder,
                extractor, matcher, enricher, reviser, previewMessenger, "./site", clock);
    }

    private static ExtractionResult extraction(
            String coffeeName, String roastery, String origin, String myTaste, Rating rating) {
        // targetDate=null → NoteExtractor가 today로 기본화(data-model §4).
        return new ExtractionResult(coffeeName, roastery, origin, null, null, myTaste, rating, null, null);
    }

    private static PendingNote pendingWith(String slug) {
        Entry entry = new Entry(
                LocalDate.of(2026, 7, 11), "새콤하고 좋았다", Rating.GOOD, List.of(), OffsetDateTime.now());
        Note draft = new Note(
                slug, "커피베라 예가체프",
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
        assertEquals("커피베라 예가체프", draft.coffeeName());
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
    @DisplayName("[저장]: upsertEntry 커밋 → pending clear → renderAll 트리거 → 완료 안내")
    void confirmSaveCommitsClearsAndRenders() {
        NoteRepository repo = noteRepository();
        pendingStore.setPending(pendingWith("coffeevera-yirgacheffe"));

        flow(repo).confirmSave(action(DefaultConversationRouter.ACTION_SAVE));

        // 커밋: 노트 JSON이 생성되고 엔트리 1건이 담긴다.
        Optional<Note> saved = repo.findBySlug("coffeevera-yirgacheffe");
        assertTrue(saved.isPresent(), "저장된 노트 JSON이 있어야 한다");
        assertEquals(1, saved.get().entries().size());
        assertTrue(dataDir.resolve("notes/coffeevera-yirgacheffe.json").toFile().isFile());

        assertEquals(1, pendingStore.clearCount, "커밋 후 pending을 폐기한다");
        assertEquals(1, siteRenderer.renderCount, "커밋 후 전체 리렌더를 트리거한다");
        assertEquals(1, responder.messages.size());
        // 완료 안내는 노트 경로(site/notes/<slug>.html)를 담는다.
        assertTrue(responder.messages.get(0).contains("coffeevera-yirgacheffe.html"),
                "완료 안내에 노트 경로가 담겨야 한다: " + responder.messages.get(0));
    }

    @Test
    @DisplayName("V-7: 만료/부재 pending에 [저장] → 커밋하지 않고 만료 안내한다")
    void confirmSaveRejectsWhenNoPending() {
        NoteRepository repo = noteRepository();
        // get()이 빈 Optional을 준다(만료분은 store가 만료 처리 — data-model V-7).
        pendingStore.setPending(null);

        flow(repo).confirmSave(action(DefaultConversationRouter.ACTION_SAVE));

        assertTrue(repo.findAll().isEmpty(), "만료/부재 시 어떤 노트도 저장되지 않는다");
        assertEquals(0, siteRenderer.renderCount, "커밋이 없으면 리렌더도 없다");
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
        assertEquals(0, siteRenderer.renderCount);
        assertEquals(List.of(DefaultConfirmationFlow.CANCELED), responder.messages);
    }

    @Test
    @DisplayName("손상된 pending(slug 결손)에 [저장] → 저장하지 않고 방어 안내한다")
    void confirmSaveRejectsBrokenPending() {
        NoteRepository repo = noteRepository();
        pendingStore.setPending(pendingWith(null)); // slug 미할당 draft

        flow(repo).confirmSave(action(DefaultConversationRouter.ACTION_SAVE));

        assertTrue(repo.findAll().isEmpty(), "slug 없는 draft는 저장하지 않는다");
        assertEquals(0, siteRenderer.renderCount);
        assertEquals(0, pendingStore.clearCount, "손상 pending은 커밋 clear 대상이 아니다");
        assertEquals(List.of(DefaultConfirmationFlow.BROKEN_PENDING), responder.messages);
        assertFalse(responder.messages.isEmpty());
    }
}
