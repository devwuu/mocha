package com.devwuu.mocha.slack;

import com.devwuu.mocha.domain.PendingNote;
import com.devwuu.mocha.repository.PendingStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T3-2: pending 유무·action_id 결정론 분기(ADR-3) 검증. 외부 의존(PendingStore·ConfirmationFlow)은
 * fake로 대체해 라우팅 결정만 결정론적으로 단언한다(CLAUDE.md §5.2).
 */
class DefaultConversationRouterTest {

    /** get()이 돌려줄 pending을 테스트가 지정하는 fake. put/clear는 이 테스트 범위 밖. */
    private static final class FakePendingStore implements PendingStore {
        private Optional<PendingNote> pending = Optional.empty();

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
            throw new UnsupportedOperationException();
        }
    }

    /** 어느 분기가 호출됐는지 캡처하는 fake. */
    private static final class CapturingFlow implements ConfirmationFlow {
        final List<IncomingMessage> newNotes = new ArrayList<>();
        final List<IncomingMessage> revisions = new ArrayList<>();
        final List<IncomingAction> saves = new ArrayList<>();
        final List<IncomingAction> cancels = new ArrayList<>();
        final List<IncomingMedia> media = new ArrayList<>();

        @Override
        public void startNewNote(IncomingMessage message) {
            newNotes.add(message);
        }

        @Override
        public void revisePending(IncomingMessage message, PendingNote pending) {
            revisions.add(message);
        }

        @Override
        public void confirmSave(IncomingAction action) {
            saves.add(action);
        }

        @Override
        public void cancel(IncomingAction action) {
            cancels.add(action);
        }

        @Override
        public void receiveMedia(IncomingMedia media) {
            this.media.add(media);
        }
    }

    private final FakePendingStore pendingStore = new FakePendingStore();
    private final CapturingFlow flow = new CapturingFlow();
    private final DefaultConversationRouter router = new DefaultConversationRouter(pendingStore, flow);

    private static PendingNote somePending() {
        return new PendingNote(null, null, "1720000000.000999", OffsetDateTime.now());
    }

    @Test
    @DisplayName("pending 없음 + 텍스트 → 신규 파이프라인으로 분기한다")
    void routesToNewNoteWhenNoPending() {
        IncomingMessage msg = new IncomingMessage("U1", "C1", "커피베라 예가체프 마셨다", "1720000000.000100");

        router.onMessage(msg);

        assertEquals(List.of(msg), flow.newNotes);
        assertTrue(flow.revisions.isEmpty());
    }

    @Test
    @DisplayName("AC-5: pending 있음 + 텍스트 → 수정 요청으로만 가고 새 노트를 만들지 않는다")
    void routesToReviseWhenPendingExists() {
        pendingStore.setPending(somePending());
        IncomingMessage msg = new IncomingMessage("U1", "C1", "산미는 낮음으로 바꿔줘", "1720000000.000200");

        router.onMessage(msg);

        assertEquals(List.of(msg), flow.revisions);
        // 핵심: pending 중 텍스트는 신규 파이프라인을 절대 태우지 않는다(AC-5).
        assertTrue(flow.newNotes.isEmpty());
    }

    @Test
    @DisplayName("[저장] action_id → confirmSave로 분기한다")
    void routesSaveAction() {
        IncomingAction action = new IncomingAction(
                "U1", "C1", DefaultConversationRouter.ACTION_SAVE, "slug", "1720000000.000999");

        router.onAction(action);

        assertEquals(List.of(action), flow.saves);
        assertTrue(flow.cancels.isEmpty());
    }

    @Test
    @DisplayName("[취소] action_id → cancel로 분기한다")
    void routesCancelAction() {
        IncomingAction action = new IncomingAction(
                "U1", "C1", DefaultConversationRouter.ACTION_CANCEL, null, "1720000000.000999");

        router.onAction(action);

        assertEquals(List.of(action), flow.cancels);
        assertTrue(flow.saves.isEmpty());
    }

    @Test
    @DisplayName("T4-2: 사진 수신 → receiveMedia로 위임한다(세션 그룹핑은 flow가 처리)")
    void routesMediaToFlow() {
        IncomingMedia media = new IncomingMedia(
                "U1", "C1", List.of(new IncomingPhoto("https://slack/f1", "a.jpg")), "1720000000.000300");

        router.onMedia(media);

        assertEquals(List.of(media), flow.media);
    }

    @Test
    @DisplayName("계약에 없는 action_id는 조용히 무시한다 — 파싱 방어")
    void ignoresUnknownAction() {
        IncomingAction action = new IncomingAction("U1", "C1", "무엇인가_다른_액션", null, "ts");

        router.onAction(action);

        assertTrue(flow.saves.isEmpty());
        assertTrue(flow.cancels.isEmpty());
    }
}
