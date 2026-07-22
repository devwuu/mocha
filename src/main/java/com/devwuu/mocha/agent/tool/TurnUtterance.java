package com.devwuu.mocha.agent.tool;

import java.time.LocalDate;
import java.util.List;

/**
 * 턴의 사용자 원문(+세그먼트 컨텍스트)을 제안 검증기까지 나르는 SDK 무관 홀더 — 다중 날짜 게이트(V-16)의
 * 판정 입력이다 (ref: specs/coffee-note-agent/changes/0023-harness-baseline/delta.md#ADR-60, TΔ2b).
 * <p>라우터가 턴 시작 시 1회 만들어 tool 조립({@code AgentToolkit.forTurn})에 넘긴다 — 턴 안에서
 * 원문·세그먼트가 일관되고(FR-5 갱신 재호출 포함), 다음 턴은 새 조립으로 자연 갱신된다.
 * <p>{@code segments}는 다중 날짜 자동 분해(ADR-61, TΔ3b) 산물 — 라우터가 컨텍스트 조립기에 넘긴 것과
 * 같은 값을 싣는다(모델 컨텍스트 ↔ 게이트 판정의 드리프트 방지, findings-TΔ0 §C-5).
 *
 * @param rawText  이번 턴의 사용자 원문({@code IncomingMessage.text()}) — 사진 캡션은 별도 메시지로
 *                 합류하므로 이 값 하나로 충분하다(§C-5).
 * @param segments 다중 날짜 자동 분해 결과(날짜별 원문 발췌) — 분해 미수행(단일 날짜)·세그먼터 실패 턴은 null.
 */
public record TurnUtterance(String rawText, List<Segment> segments) {

    public TurnUtterance {
        segments = segments == null ? null : List.copyOf(segments);
    }

    /** 날짜별 세그먼트 — 원문 무편집 발췌(요약·추출 금지, data-model §4.3 — plan ADR-61 POLICY). */
    public record Segment(LocalDate date, String text) {
    }
}
