package com.devwuu.mocha.slack;

import com.devwuu.mocha.domain.Entry;
import com.devwuu.mocha.domain.MatchInfo;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.PendingNote;
import com.devwuu.mocha.domain.Rating;
import com.devwuu.mocha.domain.Sourced;
import com.devwuu.mocha.json.MochaObjectMapper;
import com.devwuu.mocha.render.SiteRenderer;
import com.devwuu.mocha.repository.JsonFileNoteRepository;
import com.devwuu.mocha.repository.NoteRepository;
import com.devwuu.mocha.repository.PendingStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T3-5: [저장]/[취소] 커밋 파이프라인 검증. 저장소는 실제 파일 I/O(@TempDir, CLAUDE.md §5.2)로 AC-4를
 * 파일 존재로 단언하고, pending·렌더·Slack 통지는 fake로 대체해 커밋 순서/거부 경로를 결정론적으로 본다.
 */
class DefaultConfirmationFlowTest {

    /** get()이 돌려줄 pending을 테스트가 지정하는 fake. clear 호출 여부를 캡처한다. */
    private static final class FakePendingStore implements PendingStore {
        private Optional<PendingNote> pending = Optional.empty();
        int clearCount = 0;

        void setPending(PendingNote p) {
            this.pending = Optional.ofNullable(p);
        }

        @Override
        public void put(String userId, PendingNote pending) {
            throw new UnsupportedOperationException();
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

    @TempDir
    Path dataDir;

    private final FakePendingStore pendingStore = new FakePendingStore();
    private final FakeSiteRenderer siteRenderer = new FakeSiteRenderer();
    private final FakeSlackResponder responder = new FakeSlackResponder();

    private NoteRepository noteRepository() {
        return new JsonFileNoteRepository(dataDir, MochaObjectMapper.create());
    }

    private DefaultConfirmationFlow flow(NoteRepository repo) {
        return new DefaultConfirmationFlow(pendingStore, repo, siteRenderer, responder, "./site");
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
