package com.devwuu.mocha.agent.turn;

import com.devwuu.mocha.domain.MatchInfo;
import com.devwuu.mocha.domain.Note;

import java.util.Objects;

/**
 * 턴 입력의 <b>draft</b> — 사용자가 보고 고칠 수 있었던 <i>현재 폼 상태</i>를 에이전트 턴까지 나르는
 * SDK 무관 홀더 (ref: specs/coffee-note-agent/changes/0029-app-interface/open-questions.md#OQ-4R,
 * delta 0029 D-1·D-2; tasks TΔ2).
 * <p><b>왜 필요한가</b>: 0029에서 pending이 서버 상태에서 클라이언트 상태로 옮겨간다(OQ-1 ㉡). 그러면
 * 서버는 폼에 무엇이 담겼는지 모르고, 추가 발화 턴에서 에이전트가 트랜스크립트만 보고 재제안하면
 * <b>사용자가 폼에서 고친 값이 되돌아간다</b> — 이 델타가 없애려는 실패(delta §1.2)의 재발이다.
 * 그래서 추가 발화 요청이 현재 draft 전체를 함께 실어 보내고, 에이전트의 과업은
 * <i>"이 draft 위에 이 발화를 반영하라"</i>가 된다.
 * <p>두 곳에서 소비된다 — 컨텍스트 조립({@code TurnPromptAssembler}, 모델에게 draft를 보여준다)과
 * 제안 검증({@code RecordProposalValidator}, V-6 draft 대조로 하위 출처 덮어쓰기를 거부한다). 라우터가
 * 턴 시작 시 1회 만들어 양쪽에 같은 값을 넘긴다 — {@link TurnUserMessage}와 같은 규율이다
 * (모델 컨텍스트 ↔ 게이트 판정의 드리프트 방지).
 * <p><b>API 계약</b>: 이 타입의 JSON 직렬화형이 곧 {@code POST /api/agent/turn}의 {@code draft} 필드다
 * (TΔ6에서 노출). 형태는 {@code TurnDraftContractTest}가 스냅샷으로 고정한다 — 클라이언트와 공유하는
 * 계약이라 필드를 늘리거나 이름을 바꾸면 그 테스트가 먼저 깨진다.
 * <p>POLICY: agent/tool/은 tool 정의·인자·검증만 — 턴 전처리·컨텍스트 운반체는 agent/turn/에,
 * 새 인터페이스 없이 구체 클래스로 (ref: plan.md#ADR-64).
 *
 * @param note  작성 중인 노트(entries 포함) — 저장 전이라 {@code id}는 신규면 null이다(0028 D-1).
 * @param match 폼의 매칭 배지 상태(신규/기존) — 사용자가 배지에서 바꿀 수 있으므로 draft의 일부다(OQ-2 ㉢).
 */
public record TurnDraft(Note note, MatchInfo match) {

    // draft가 "있다"는 것은 폼이 떠 있다는 뜻이고, 폼에는 항상 노트가 있다 — 부재는 이 타입 자체를
    // null로 넘겨 표현한다(빈 draft라는 제3의 상태를 만들지 않는다).
    public TurnDraft {
        Objects.requireNonNull(note, "note");
    }
}
