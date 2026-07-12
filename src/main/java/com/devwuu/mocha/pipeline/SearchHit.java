package com.devwuu.mocha.pipeline;

import java.time.LocalDate;

/**
 * 검색 결과 1건의 표시 재료 — 후보 목록(커피명·로스터리·최근 시음일, spec AC-32)과 단일 매치 카드
 * 재전송({@code cards/<slug>/<latestDate>.jpg}, spec AC-31)이 쓴다 (ref: spec FR-20, plan.md#ADR-25).
 *
 * @param slug       대상 노트 slug.
 * @param coffeeName 표시용 커피 이름(결손 시 null — 표시 폴백은 호출부).
 * @param roastery   로스터리(결손 시 null).
 * @param latestDate 그 노트의 최근 시음일(엔트리 최댓값) — 카드 재전송 대상 엔트리(기본 최신, FR-20).
 */
public record SearchHit(String slug, String coffeeName, String roastery, LocalDate latestDate) {
}
