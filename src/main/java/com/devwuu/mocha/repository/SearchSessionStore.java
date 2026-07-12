package com.devwuu.mocha.repository;

import com.devwuu.mocha.domain.SearchSession;

import java.util.Optional;

/**
 * 검색 세션 보관 경계 — 사용자당 1건 (ref: plan.md §3, #ADR-25; spec FR-20).
 * <p>POLICY: 검색 세션은 메모리 전용 — {@code data/} 아래 어떤 파일도 만들지 않고 재시작 시 소멸한다
 * (ref: spec NFR-2 예외, AC-35; plan.md#ADR-25). pending과 달리 영속화하지 않는다.
 * <p>검색 세션은 확인 대기(pending)를 절대 쓰지 않는다 — 격리(FR-20). 라우팅 층은 {@link #get}의
 * 존재 여부를 게이트 힌트({@code search_session_active})·폴백 기준으로 쓴다(ADR-24).
 */
public interface SearchSessionStore {

    /** 진행 중 세션을 교체 저장한다 — 사용자당 1건(단일 세션). */
    void put(String userId, SearchSession session);

    /** 진행 중 세션. TTL(1h) 만료분은 빈 Optional로 수렴한다(만료 판정은 다음 수신 시점 — pending과 동일 패턴). */
    Optional<SearchSession> get(String userId);

    /** 세션 종료·폐기({@code end} 의도, TTL 정리). */
    void clear(String userId);
}
