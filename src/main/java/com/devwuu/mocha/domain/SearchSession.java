package com.devwuu.mocha.domain;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 검색 세션 상태 — 사용자당 최대 1건, 메모리 전용 (ref: data-model.md#2.5, plan.md#ADR-25, spec FR-20).
 * <p>재시작 시 소멸해도 다시 물으면 되는 임시 문맥이라 파일로 영속하지 않는다(NFR-2 예외 — pending과 다른 결).
 *
 * @param clues           누적 단서(최초 질의·재질문 답변) — 재탐색 시 함께 후보 선정 컨텍스트로 쓴다.
 * @param candidateSlugs  최근 제시한 후보 노트 slug 목록 — "두 번째" 같은 선택 답변의 해석 기준.
 * @param requeryCount    이 세션에서 보낸 무후보 재질문 횟수 — 상한({@code mocha.search-session.max-requery},
 *                        기본 0=무제한) 판정 기준(spec FR-20/AC-33).
 * @param createdAt       세션 시작 시각 — TTL(1h, {@code mocha.search-session.ttl}) 만료 판정 기준.
 * @param pendingEditSlug 수정 전환 대상으로 확정된 노트 slug — 엔트리 복수 노트의 날짜 목록 선택 대기 상태
 *                        표시(FR-21/AC-42, changes/0012). 수정 전환 중이 아니면 null.
 */
public record SearchSession(
        List<String> clues, List<String> candidateSlugs, int requeryCount, OffsetDateTime createdAt,
        String pendingEditSlug) {

    /** 수정 전환 상태가 없는 일반 검색 세션 — 0012 이전 호출부 형태 유지. */
    public SearchSession(List<String> clues, List<String> candidateSlugs, int requeryCount, OffsetDateTime createdAt) {
        this(clues, candidateSlugs, requeryCount, createdAt, null);
    }
}
