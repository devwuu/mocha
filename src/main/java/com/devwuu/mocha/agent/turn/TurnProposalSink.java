package com.devwuu.mocha.agent.turn;

import java.util.Optional;

/**
 * 턴 1회의 <b>제안 결과</b> 수거함 — 제안 tool({@code propose_record})이 검증을 통과시킨 내용을 담아
 * 호출부(라우터·TΔ6 컨트롤러)가 턴 종료 후 꺼내 간다
 * (ref: specs/coffee-note-agent/changes/0029-app-interface/tasks.md TΔ4, delta 0029 D-2).
 * <p><b>왜 생겼는가</b>: 종전에는 제안의 효과가 <i>서버의 pending 행 + Slack 미리보기 전송</i>이었다.
 * pending이 클라이언트 상태가 되면서(OQ-1) 서버는 작성 중 데이터를 기억하지 않고, 제안은
 * <b>응답으로 돌려주는 값</b>이 된다. tool 콜백은 모델에게 돌려줄 문자열만 반환하므로, 그 값을 턴 밖으로
 * 꺼낼 자리가 따로 필요하다 — 그것이 이 수거함이다.
 * <p>담기는 타입이 {@link TurnDraft}인 이유: <b>이번 턴의 제안 결과가 곧 다음 턴의 draft</b>이기 때문이다.
 * 클라이언트는 이 값을 폼에 채우고, 추가 발화 때 그 폼을 draft로 되돌려 보낸다(TΔ2 계약). 형태가 같은데
 * 방향만 다른 값이라 타입을 따로 만들지 않는다.
 * <p>턴 1회 스코프의 가변 홀더다 — 툴킷({@code ToolCallbackProvider})은 애플리케이션 수명 객체라
 * 상태를 들 수 없고, 턴마다 새로 만들어 {@code forTurn} 인자로 바인딩한다({@link TurnUserMessage}·
 * {@link TurnDraft}와 같은 규율, findings-TΔ0 §C-2).
 * <p>한 턴에서 제안 tool이 여러 번 성공하면(검증 거부 후 정정 재호출 등) <b>마지막 것이 남는다</b> —
 * 사용자에게 보여줄 것은 모델이 최종적으로 확정한 내용이다.
 * <p>POLICY: agent/tool/은 tool 정의·인자·검증만 — 턴 전처리·컨텍스트 운반체는 agent/turn/에,
 * 새 인터페이스 없이 구체 클래스로 (ref: plan.md#ADR-64).
 */
public final class TurnProposalSink {

    private TurnDraft proposal;

    /** 제안 tool이 검증을 통과시킨 내용을 싣는다 — 같은 턴의 재제안은 앞의 것을 덮는다. */
    public void accept(TurnDraft accepted) {
        this.proposal = accepted;
    }

    /** 이번 턴의 제안 결과 — 제안 tool이 성공하지 않은 턴(잡담·조회·검증 거부)은 빈 값이다. */
    public Optional<TurnDraft> proposal() {
        return Optional.ofNullable(proposal);
    }
}
