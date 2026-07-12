package com.devwuu.mocha.slack;

import com.devwuu.mocha.domain.PendingNote;
import com.devwuu.mocha.pipeline.ContextHint;
import com.devwuu.mocha.pipeline.IntentClassifier;
import com.devwuu.mocha.pipeline.MessageIntent;
import com.devwuu.mocha.repository.PendingStore;
import com.devwuu.mocha.repository.SearchSessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 파이프라인 [1] — 의도 우선 라우팅 {@link ConversationRouter} 구현
 * (ref: plan.md §1 [1]·[1.5], §3; ADR-24; ADR-3 부분 개정, changes/0011).
 * <p>모든 수신 텍스트는 상시 의도 게이트({@link IntentClassifier} 5분류)를 먼저 통과하고 **의도가 라우팅을
 * 결정**한다 — 상태(확인 대기·검색 세션)는 분류 힌트·분기 조건·폴백 기준으로만 쓴다(FR-17). 실제 파이프라인
 * 일과 안내 문구·전송은 {@link ConfirmationFlow} 소유다(라우터는 responder를 직접 부르지 않는다 — ADR-24 구현 배치).
 * <ul>
 *   <li>record — 대기 없으면 신규 파이프라인, 있으면 대기 불변 + "먼저 저장/취소" 안내(단일 대기 원칙, AC-30).
 *   <li>revise — 대기 있으면 수정(AC-5), 없는데 검색 세션 진행 중이면 search로 폴백(수정 전환이 막히지 않게,
 *       FR-21/changes-0012), 둘 다 아니면 안내.
 *   <li>search — 검색 세션 시작/계속(FR-20). end — 세션 있으면 종료, 없으면 other 취급(FR-17).
 *   <li>other — 파이프라인 미진입, 짧은 안내(AC-20).
 *   <li>버튼 액션 → {@code action_id}로만 저장/취소 분기.
 * </ul>
 * <p>POLICY: 저장/취소 커밋은 어떤 세션 상태에서도 버튼(action_id)만 — 자연어로 분류하지 않는다
 * (ref: plan.md#ADR-3 불변, spec FR-4). 게이트는 라우팅만 정한다.
 */
@Component
public class DefaultConversationRouter implements ConversationRouter {

    private static final Logger log = LoggerFactory.getLogger(DefaultConversationRouter.class);

    /**
     * 확인 미리보기 버튼의 action_id 계약 — 미리보기를 조립하는 T3-3이 이 값으로 버튼을 만든다.
     * (Block Kit action_id 문자열은 구현 디테일이라 여기서 정한다. spec의 비즈니스 결정 아님.)
     */
    public static final String ACTION_SAVE = "mocha_save";
    public static final String ACTION_CANCEL = "mocha_cancel";

    private final PendingStore pendingStore;
    private final SearchSessionStore searchSessionStore;
    private final IntentClassifier intentClassifier;
    private final ConfirmationFlow flow;

    public DefaultConversationRouter(
            PendingStore pendingStore,
            SearchSessionStore searchSessionStore,
            IntentClassifier intentClassifier,
            ConfirmationFlow flow) {
        this.pendingStore = pendingStore;
        this.searchSessionStore = searchSessionStore;
        this.intentClassifier = intentClassifier;
        this.flow = flow;
    }

    @Override
    public void onMessage(IncomingMessage message) {
        String userId = message.userId();
        // 상태는 힌트·분기 조건일 뿐 라우팅 결정자가 아니다(ADR-24) — 게이트 호출 전에 한 번만 조회한다.
        Optional<PendingNote> pending = pendingStore.get(userId);
        boolean searchSessionActive = searchSessionStore.get(userId).isPresent();

        MessageIntent intent = classifyOrFallback(message.text(), pending.isPresent(), searchSessionActive, userId);
        switch (intent) {
            case RECORD -> {
                if (pending.isPresent()) {
                    // POLICY: 단일 대기 원칙 — record 의도인데 대기가 있으면 대기 불변 + 안내, 새 대기 미생성
                    //         (ref: spec FR-17/AC-30, plan.md#ADR-24).
                    flow.guidePendingExists(message);
                } else {
                    flow.startNewNote(message);
                }
            }
            case REVISE -> {
                if (pending.isPresent()) {
                    flow.revisePending(message, pending.get());
                } else if (searchSessionActive) {
                    // POLICY: 검색 세션 활성 + REVISE + 대기 없음 → 검색 세션이 문맥의 주인 — "그거 수정할래"가
                    //         게이트에서 revise로 분류돼도 수정 전환(FR-21)이 라우터 층에서 막히지 않게 search로
                    //         흘린다(게이트 폴백 ①검색 세션 중→search와 같은 정신) (ref: plan.md#ADR-27, changes/0012).
                    flow.searchNotes(message);
                } else {
                    flow.guideNothingToRevise(message);
                }
            }
            case SEARCH -> flow.searchNotes(message); // 대기 중에도 검색 가능 — pending 격리(FR-20, AC-29)
            case END -> {
                if (searchSessionActive) {
                    flow.endSearch(message);
                } else {
                    // FR-17: end인데 진행 중 검색 세션이 없으면 other 취급.
                    flow.guideNotARecord(message);
                }
            }
            case OTHER -> flow.guideNotARecord(message);
        }
    }

    // [1.5] 상시 의도 게이트 — 호출/스키마 실패는 폴백 우선순위로 흡수해 입력의 소리 없는 유실을 막는다(AC-21).
    private MessageIntent classifyOrFallback(
            String text, boolean hasPending, boolean searchSessionActive, String userId) {
        try {
            MessageIntent intent = intentClassifier
                    .classify(text, new ContextHint(hasPending, searchSessionActive))
                    .intent();
            // 관측(plan §6): 판정 분포를 로깅해 오분류 프록시로 삼는다.
            log.info("의도 게이트 판정: user={} intent={} hasPending={} searchActive={}",
                    userId, intent.value(), hasPending, searchSessionActive);
            return intent;
        } catch (Exception e) {
            // POLICY: 게이트 폴백은 결정론 우선순위 — ① 검색 세션 중 search ② 대기 있음 revise(수정 유실 방지)
            //         ③ 둘 다 없음 record(기록 유실 방지, fail-open) (ref: spec FR-17/AC-21, plan.md#ADR-24).
            MessageIntent fallback = searchSessionActive ? MessageIntent.SEARCH
                    : hasPending ? MessageIntent.REVISE
                    : MessageIntent.RECORD;
            log.warn("의도 게이트 실패 — 폴백 {}로 라우팅: user={}", fallback.value(), userId, e);
            return fallback;
        }
    }

    @Override
    public void onAction(IncomingAction action) {
        // 저장/취소는 Block Kit 버튼(action_id)으로만 받는다 — 자연어 아님(ADR-3 불변, 게이트 미경유).
        String actionId = action.actionId();
        if (ACTION_SAVE.equals(actionId)) {
            flow.confirmSave(action);
        } else if (ACTION_CANCEL.equals(actionId)) {
            flow.cancel(action);
        } else {
            // 계약에 없는 action_id — 조용히 무시하되 원인 추적용으로 남긴다.
            log.warn("알 수 없는 액션 무시: actionId={} user={}", actionId, action.userId());
        }
    }

    @Override
    public void onMedia(IncomingMedia media) {
        // 버퍼 그룹핑(윈도우 판정·pending 첨부 vs 버퍼)은 오케스트레이션 몫이라 그대로 위임한다(FR-10). 게이트 미경유.
        flow.receiveMedia(media);
    }
}
