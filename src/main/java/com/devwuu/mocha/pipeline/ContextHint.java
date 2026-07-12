package com.devwuu.mocha.pipeline;

/**
 * 의도 게이트 요청에 서버가 주입하는 컨텍스트 힌트
 * (ref: data-model.md#4.1 {@code context}, plan.md#ADR-24, changes/0011 delta.md).
 * <p>POLICY: 힌트는 분류의 참고 정보일 뿐 라우팅 결정자가 아니다 — 라우팅은 의도가 결정하고,
 * 상태는 폴백 우선순위(검색 세션 중→search, 대기 있음→revise, 없음→record)의 기준으로만 쓴다 (ADR-24, FR-17).
 * <p>snake_case 직렬화({@code has_pending}, {@code search_session_active})는 MochaObjectMapper 규칙을 따른다.
 *
 * @param hasPending          확인 대기 기록(record/edit) 존재 여부.
 * @param searchSessionActive 검색 세션 진행 여부.
 */
public record ContextHint(boolean hasPending, boolean searchSessionActive) {
}
