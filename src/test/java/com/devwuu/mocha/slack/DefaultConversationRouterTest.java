package com.devwuu.mocha.slack;

import com.devwuu.mocha.domain.PendingNote;
import com.devwuu.mocha.domain.SearchSession;
import com.devwuu.mocha.llm.LlmException;
import com.devwuu.mocha.pipeline.ContextHint;
import com.devwuu.mocha.pipeline.IntentClassifier;
import com.devwuu.mocha.pipeline.IntentResult;
import com.devwuu.mocha.pipeline.MessageIntent;
import com.devwuu.mocha.repository.PendingStore;
import com.devwuu.mocha.repository.SearchSessionStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * TΔ3(changes/0011): 의도 우선 라우팅(ADR-24, FR-17) 검증 — 의도가 라우팅을 결정하고 상태(대기·검색 세션)는
 * 분기 조건·폴백 기준만 거든다. 외부 의존(게이트·store·flow)은 fake/stub으로 대체해 라우팅 결정만
 * 결정론적으로 단언한다(CLAUDE.md §5.2). 버튼 커밋 결정론(ADR-3)은 불변 회귀 가드로 유지한다.
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

    /** 검색 세션 유무만 지정하는 fake — put/clear는 이 테스트 범위 밖(TΔ4·TΔ5 몫). */
    private static final class FakeSearchSessionStore implements SearchSessionStore {
        private Optional<SearchSession> session = Optional.empty();

        void setActive(boolean active) {
            this.session = active
                    ? Optional.of(new SearchSession(List.of("예가체프"), List.of(), 0, OffsetDateTime.now()))
                    : Optional.empty();
        }

        @Override
        public void put(String userId, SearchSession session) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Optional<SearchSession> get(String userId) {
            return session;
        }

        @Override
        public void clear(String userId) {
            throw new UnsupportedOperationException();
        }
    }

    /** 판정 결과를 테스트가 지정하는 stub 게이트 — LLM 미접촉, 받은 텍스트·힌트를 캡처한다. */
    private static final class StubIntentClassifier extends IntentClassifier {
        MessageIntent canned = MessageIntent.RECORD;
        RuntimeException failure;
        String lastText;
        ContextHint lastHint;
        int calls = 0;

        StubIntentClassifier() {
            super(null, null); // LlmClient·ObjectMapper 미사용 — classify를 통째로 대체한다.
        }

        @Override
        public IntentResult classify(String message, ContextHint contextHint) {
            calls++;
            lastText = message;
            lastHint = contextHint;
            if (failure != null) {
                throw failure;
            }
            return new IntentResult(canned);
        }
    }

    /** 어느 분기가 호출됐는지 캡처하는 fake — 텍스트 분기는 메서드명 문자열로 한 번에 단언한다. */
    private static final class CapturingFlow implements ConversationFlows {
        final List<String> textCalls = new ArrayList<>();
        PendingNote revisedWith;
        final List<IncomingAction> saves = new ArrayList<>();
        final List<IncomingAction> cancels = new ArrayList<>();
        final List<IncomingMedia> media = new ArrayList<>();

        @Override
        public void startNewNote(IncomingMessage message) {
            textCalls.add("startNewNote");
        }

        @Override
        public void revisePending(IncomingMessage message, PendingNote pending) {
            textCalls.add("revisePending");
            revisedWith = pending;
        }

        @Override
        public void guidePendingExists(IncomingMessage message) {
            textCalls.add("guidePendingExists");
        }

        @Override
        public void guideNotARecord(IncomingMessage message) {
            textCalls.add("guideNotARecord");
        }

        @Override
        public void searchNotes(IncomingMessage message) {
            textCalls.add("searchNotes");
        }

        @Override
        public void endSearch(IncomingMessage message) {
            textCalls.add("endSearch");
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
    private final FakeSearchSessionStore searchSessionStore = new FakeSearchSessionStore();
    private final StubIntentClassifier intentClassifier = new StubIntentClassifier();
    private final CapturingFlow flow = new CapturingFlow();
    private final DefaultConversationRouter router =
            new DefaultConversationRouter(pendingStore, searchSessionStore, intentClassifier, flow);

    private static PendingNote somePending() {
        return new PendingNote(null, null, "1720000000.000999", OffsetDateTime.now());
    }

    private static IncomingMessage message(String text) {
        return new IncomingMessage("U1", "C1", text, "1720000000.000100");
    }

    // --- TΔ3: 의도×상태 라우팅 매트릭스(5의도 × 대기 유/무 × 검색 세션 유/무 = 20조합) ---

    static Stream<Arguments> intentStateMatrix() {
        return Stream.of(
                // record: 대기 없으면 신규 파이프라인(AC-1 경로 불변), 있으면 대기 불변 + 안내(단일 대기 원칙, AC-30)
                arguments(MessageIntent.RECORD, false, false, "startNewNote"),
                arguments(MessageIntent.RECORD, false, true, "startNewNote"),
                arguments(MessageIntent.RECORD, true, false, "guidePendingExists"),
                arguments(MessageIntent.RECORD, true, true, "guidePendingExists"),
                // revise: 대기 있으면 수정(FR-5/AC-5), 대기 없으면 검색 세션으로 위임 — 진행 중이면 계속(changes/0012),
                // 없으면 자동 시작(changes/0016 TΔ7)해 수정 전환(FR-21)이 라우터 층에서 막히지 않는다(FR-17/AC-51)
                arguments(MessageIntent.REVISE, true, false, "revisePending"),
                arguments(MessageIntent.REVISE, true, true, "revisePending"),
                arguments(MessageIntent.REVISE, false, false, "searchNotes"),
                arguments(MessageIntent.REVISE, false, true, "searchNotes"),
                // search: 상태 무관 검색 세션 시작/계속(FR-20) — 대기 중에도 검색 가능(AC-29 재료)
                arguments(MessageIntent.SEARCH, false, false, "searchNotes"),
                arguments(MessageIntent.SEARCH, true, false, "searchNotes"),
                arguments(MessageIntent.SEARCH, false, true, "searchNotes"),
                arguments(MessageIntent.SEARCH, true, true, "searchNotes"),
                // end: 검색 세션 있으면 종료, 없으면 other 취급(FR-17)
                arguments(MessageIntent.END, false, true, "endSearch"),
                arguments(MessageIntent.END, true, true, "endSearch"),
                arguments(MessageIntent.END, false, false, "guideNotARecord"),
                arguments(MessageIntent.END, true, false, "guideNotARecord"),
                // other: 상태 무관 안내만(AC-20)
                arguments(MessageIntent.OTHER, false, false, "guideNotARecord"),
                arguments(MessageIntent.OTHER, true, false, "guideNotARecord"),
                arguments(MessageIntent.OTHER, false, true, "guideNotARecord"),
                arguments(MessageIntent.OTHER, true, true, "guideNotARecord"));
    }

    @ParameterizedTest(name = "{0} × pending={1} × searchSession={2} → {3}")
    @MethodSource("intentStateMatrix")
    @DisplayName("FR-17/ADR-24: 의도가 라우팅을 결정한다 — 의도×상태 20조합 각각 단일 분기")
    void routesByIntentAndState(
            MessageIntent intent, boolean hasPending, boolean searchActive, String expectedCall) {
        if (hasPending) {
            pendingStore.setPending(somePending());
        }
        searchSessionStore.setActive(searchActive);
        intentClassifier.canned = intent;

        router.onMessage(message("아무 텍스트"));

        assertEquals(List.of(expectedCall), flow.textCalls, "의도별로 정확히 한 분기만 탄다");
    }

    @Test
    @DisplayName("ADR-24: 게이트에 원문과 (hasPending, searchSessionActive) 힌트가 상태 그대로 실려 간다")
    void passesContextHintReflectingState() {
        pendingStore.setPending(somePending());
        searchSessionStore.setActive(true);
        intentClassifier.canned = MessageIntent.SEARCH;

        router.onMessage(message("저번에 마신 예가체프 찾아줘"));

        assertEquals(1, intentClassifier.calls, "모든 텍스트는 게이트를 정확히 1회 경유한다(상시 게이트)");
        assertEquals("저번에 마신 예가체프 찾아줘", intentClassifier.lastText, "원문 그대로 넘긴다");
        assertEquals(new ContextHint(true, true), intentClassifier.lastHint, "상태가 힌트로 정확히 실린다");
    }

    @Test
    @DisplayName("AC-5: revise 분기는 라우터가 조회한 pending을 재조회 없이 그대로 넘긴다")
    void revisePassesLoadedPending() {
        PendingNote pending = somePending();
        pendingStore.setPending(pending);
        intentClassifier.canned = MessageIntent.REVISE;

        router.onMessage(message("산미는 낮음으로 바꿔줘"));

        assertEquals(List.of("revisePending"), flow.textCalls);
        assertSame(pending, flow.revisedWith, "조회한 pending 인스턴스를 그대로 넘긴다");
    }

    // --- AC-21/AC-Δ5: 게이트 실패 폴백 — 결정론 우선순위 3상황 ---

    @Test
    @DisplayName("AC-21 폴백①: 게이트 실패 + 검색 세션 중 → search 계속(대기가 있어도 검색이 우선)")
    void fallsBackToSearchWhenSessionActive() {
        pendingStore.setPending(somePending()); // 대기가 있어도 검색 세션이 우선순위 1이다
        searchSessionStore.setActive(true);
        intentClassifier.failure = new LlmException("게이트 호출 실패");

        router.onMessage(message("그때 그 커피"));

        assertEquals(List.of("searchNotes"), flow.textCalls);
    }

    @Test
    @DisplayName("AC-21 폴백②: 게이트 실패 + 대기 있음(검색 세션 없음) → revise(수정 유실 방지)")
    void fallsBackToReviseWhenPendingExists() {
        pendingStore.setPending(somePending());
        searchSessionStore.setActive(false);
        intentClassifier.failure = new LlmException("게이트 호출 실패");

        router.onMessage(message("산미는 낮음으로"));

        assertEquals(List.of("revisePending"), flow.textCalls);
    }

    @Test
    @DisplayName("AC-21 폴백③: 게이트 실패 + 대기·세션 모두 없음 → record(기록 유실 방지, fail-open)")
    void fallsBackToRecordWhenNoState() {
        intentClassifier.failure = new LlmException("게이트 호출 실패");

        router.onMessage(message("커피베라 예가체프 마셨다"));

        assertEquals(List.of("startNewNote"), flow.textCalls);
    }

    // --- ADR-3 불변 회귀 가드: 버튼 커밋 결정론·미디어 위임은 게이트를 타지 않는다 ---

    @Test
    @DisplayName("ADR-3 불변: [저장] action_id → confirmSave로만 분기하고 의도 게이트를 호출하지 않는다")
    void routesSaveAction() {
        IncomingAction action = new IncomingAction(
                "U1", "C1", DefaultConversationRouter.ACTION_SAVE, "slug", "1720000000.000999");

        router.onAction(action);

        assertEquals(List.of(action), flow.saves);
        assertTrue(flow.cancels.isEmpty());
        assertEquals(0, intentClassifier.calls, "버튼 커밋은 게이트 미경유(ADR-3)");
    }

    @Test
    @DisplayName("ADR-3 불변: [취소] action_id → cancel로만 분기하고 의도 게이트를 호출하지 않는다")
    void routesCancelAction() {
        IncomingAction action = new IncomingAction(
                "U1", "C1", DefaultConversationRouter.ACTION_CANCEL, null, "1720000000.000999");

        router.onAction(action);

        assertEquals(List.of(action), flow.cancels);
        assertTrue(flow.saves.isEmpty());
        assertEquals(0, intentClassifier.calls, "버튼 커밋은 게이트 미경유(ADR-3)");
    }

    @Test
    @DisplayName("계약에 없는 action_id는 조용히 무시한다 — 파싱 방어")
    void ignoresUnknownAction() {
        IncomingAction action = new IncomingAction("U1", "C1", "무엇인가_다른_액션", null, "ts");

        router.onAction(action);

        assertTrue(flow.saves.isEmpty());
        assertTrue(flow.cancels.isEmpty());
    }

    @Test
    @DisplayName("T4-2 불변: 사진 수신 → receiveMedia로 위임한다(버퍼 그룹핑은 flow가 처리, 게이트 미경유)")
    void routesMediaToFlow() {
        IncomingMedia media = new IncomingMedia(
                "U1", "C1", List.of(new IncomingPhoto("https://slack/f1", "a.jpg", "image/jpeg", List.of())), "1720000000.000300");

        router.onMedia(media);

        assertEquals(List.of(media), flow.media);
        assertEquals(0, intentClassifier.calls, "미디어는 게이트를 타지 않는다");
    }
}
