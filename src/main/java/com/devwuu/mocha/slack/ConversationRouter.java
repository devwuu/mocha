package com.devwuu.mocha.slack;

import com.devwuu.mocha.slack.inbound.IncomingAction;
import com.devwuu.mocha.slack.inbound.IncomingMedia;
import com.devwuu.mocha.slack.inbound.IncomingMessage;

/**
 * 파이프라인 [1] — 수신 이벤트를 종류별로 갈라 위임하는 라우팅 경계 (ref: plan.md §1 [1], §3; ADR-3).
 * <p>{@link SlackGateway}(얇은 수신 계층)가 파싱한 내부 표현을 넘기면, 구현체가 결정론적으로 분기한다:
 * 텍스트 → 에이전트 턴 / 사진 → 버퍼 그룹핑.
 * <p>0029 TΔ4에서 <b>pending 유무 분기가 소멸</b>했다 — 작성 중 데이터가 서버에서 사라져 라우터가
 * "진행 중인지"를 알 수 없고, 알 필요도 없다(발화마다 draft가 요청과 함께 온다, TΔ2).
 * <p>수신 계층과 로직을 분리해 게이트웨이는 Slack SDK 파싱만, 정책은 라우터가 갖게 한다(CLAUDE.md §2).
 */
public interface ConversationRouter {

    /** 일반 메시지 수신 위임 — 에이전트 턴(ADR-44). */
    void onMessage(IncomingMessage message);

    /**
     * Block Kit 버튼 액션 위임.
     * <p>0029 TΔ4 이후 <b>도달하지 않는 경로</b>다 — 버튼을 실은 확인 미리보기가 pending과 함께 사라졌다.
     * 수신 시임은 게이트웨이 계약이라 남겨 두고, 실제 저장·취소 확정은 앱의 폼이 가져간다(TΔ6).
     */
    void onAction(IncomingAction action);

    /** 사진(파일 공유) 수신 위임. 시간 윈도우 내 미디어를 같은 노트로 묶는 버퍼 그룹핑(FR-10, T4-2). */
    void onMedia(IncomingMedia media);
}
