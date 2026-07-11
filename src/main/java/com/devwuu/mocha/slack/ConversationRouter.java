package com.devwuu.mocha.slack;

/**
 * 파이프라인 [1] — 수신 이벤트를 pending 유무로 분기하는 라우팅 경계 (ref: plan.md §1 [1], §3; ADR-3).
 * <p>{@link SlackGateway}(얇은 수신 계층)가 파싱한 내부 표현을 넘기면, 구현체가 결정론적으로 분기한다:
 * pending 없음 → 신규 파이프라인 / pending 있음 + 텍스트 → 수정 요청(FR-5) / 버튼 → 저장·취소(FR-4).
 * <p>이 인터페이스는 T3-1이 두는 위임 시임(seam)이고, 실제 분기 로직은 T3-2 구현체가 채운다.
 * 수신 계층과 로직을 분리해 게이트웨이는 Slack SDK 파싱만, 정책은 라우터가 갖게 한다(CLAUDE.md §2).
 */
public interface ConversationRouter {

    /** 일반 메시지 수신 위임. pending 유무에 따라 신규 추출 또는 수정 요청으로 갈린다(ADR-3). */
    void onMessage(IncomingMessage message);

    /** Block Kit 버튼 액션 위임. 저장/취소 등 확인 상태 머신 전이(FR-4). */
    void onAction(IncomingAction action);

    /** 사진(파일 공유) 수신 위임. 시간 윈도우 내 미디어를 같은 노트로 묶는 세션 그룹핑(FR-10, T4-2). */
    void onMedia(IncomingMedia media);
}
