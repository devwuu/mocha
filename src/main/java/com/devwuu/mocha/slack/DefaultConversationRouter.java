package com.devwuu.mocha.slack;

import com.devwuu.mocha.domain.PendingNote;
import com.devwuu.mocha.repository.PendingStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 파이프라인 [1] — pending 유무로 결정론 분기하는 {@link ConversationRouter} 구현 (ref: plan.md §1 [1], §3; ADR-3).
 * <p>ADR-3의 핵심: 저장/취소를 자연어로 분류하지 않는다. 텍스트는 {@link PendingStore} 조회 결과만으로,
 * 액션은 {@code action_id}만으로 결정론적으로 라우팅한다. 실제 파이프라인 일은 {@link ConfirmationFlow}에 위임한다.
 * <ul>
 *   <li>pending 없음 + 텍스트 → 신규 파이프라인({@link ConfirmationFlow#startNewNote}).
 *   <li>pending 있음 + 텍스트 → 수정 요청({@link ConfirmationFlow#revisePending}) — 새 노트를 만들지 않는다(AC-5).
 *   <li>버튼 액션 → {@code action_id}로 저장/취소 분기.
 * </ul>
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
    private final ConfirmationFlow flow;

    public DefaultConversationRouter(PendingStore pendingStore, ConfirmationFlow flow) {
        this.pendingStore = pendingStore;
        this.flow = flow;
    }

    @Override
    public void onMessage(IncomingMessage message) {
        // ADR-3: pending 유무만으로 분기 — 자연어 의도 분류를 하지 않는다.
        Optional<PendingNote> pending = pendingStore.get(message.userId());
        if (pending.isPresent()) {
            // POLICY: pending 중 텍스트는 전부 수정 요청 — 새 노트를 만들지 않는다 (ref: spec FR-5/AC-5, plan#ADR-3).
            flow.revisePending(message, pending.get());
        } else {
            flow.startNewNote(message);
        }
    }

    @Override
    public void onAction(IncomingAction action) {
        // 저장/취소는 Block Kit 버튼(action_id)으로만 받는다 — 자연어 아님(ADR-3).
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
        // 세션 그룹핑(윈도우 판정·pending 첨부 vs 버퍼)은 오케스트레이션 몫이라 그대로 위임한다(FR-10, ADR-3 정신).
        flow.receiveMedia(media);
    }
}
