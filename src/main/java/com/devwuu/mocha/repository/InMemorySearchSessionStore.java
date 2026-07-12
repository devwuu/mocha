package com.devwuu.mocha.repository;

import com.devwuu.mocha.domain.SearchSession;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 메모리 전용 {@link SearchSessionStore} — 필드 Map에만 보관해 프로세스 재시작 시 소멸한다
 * (ref: plan.md#ADR-25, spec NFR-2 예외/AC-35, changes/0011).
 * <p>POLICY: 검색 세션은 {@code data/} 아래 어떤 파일도 만들지 않는다 — 메모리 전용 (ref: plan.md#ADR-25).
 * <p>TTL(1h, {@code mocha.search-session.ttl}) 만료 판정은 TΔ4에서 붙는다(changes/0011 tasks) —
 * 지금은 라우팅 힌트가 필요로 하는 보관·조회·폐기 계약만 제공한다.
 */
public class InMemorySearchSessionStore implements SearchSessionStore {

    private final Map<String, SearchSession> sessions = new ConcurrentHashMap<>();

    @Override
    public void put(String userId, SearchSession session) {
        sessions.put(userId, session);
    }

    @Override
    public Optional<SearchSession> get(String userId) {
        return Optional.ofNullable(sessions.get(userId));
    }

    @Override
    public void clear(String userId) {
        sessions.remove(userId);
    }
}
