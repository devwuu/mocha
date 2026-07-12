package com.devwuu.mocha.domain;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 검색 세션 상태 — 사용자당 최대 1건, 메모리 전용 (ref: data-model.md#2.5, plan.md#ADR-25, spec FR-20).
 * <p>재시작 시 소멸해도 다시 물으면 되는 임시 문맥이라 파일로 영속하지 않는다(NFR-2 예외 — pending과 다른 결).
 *
 * @param clues          누적 단서(최초 질의·재질문 답변) — 재탐색 시 함께 후보 선정 컨텍스트로 쓴다.
 * @param candidateSlugs 최근 제시한 후보 노트 slug 목록 — "두 번째" 같은 선택 답변의 해석 기준.
 * @param createdAt      세션 시작 시각 — TTL(1h, {@code mocha.search-session.ttl}) 만료 판정 기준.
 */
public record SearchSession(List<String> clues, List<String> candidateSlugs, OffsetDateTime createdAt) {
}
