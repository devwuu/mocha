package com.devwuu.mocha.repository;

import com.devwuu.mocha.domain.SearchSession;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 메모리 전용 {@link SearchSessionStore} — 필드 Map에만 보관해 프로세스 재시작 시 소멸한다
 * (ref: plan.md#ADR-25, spec NFR-2 예외/AC-35, changes/0011).
 * <p>POLICY: 검색 세션은 {@code data/} 아래 어떤 파일도 만들지 않는다 — 메모리 전용 (ref: plan.md#ADR-25).
 * <p>TTL({@code mocha.search-session.ttl}) 만료 판정은 다음 수신 시점의 {@link #get}에서 한다 —
 * pending({@code JsonFilePendingStore})과 동일 패턴(백그라운드 스케줄러 없음).
 */
public class InMemorySearchSessionStore implements SearchSessionStore {

    // 날짜/타임스탬프는 Asia/Seoul 기준 — pending과 동일(V-3).
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final Map<String, SearchSession> sessions = new ConcurrentHashMap<>();
    private final Duration ttl;
    private final Clock clock;

    public InMemorySearchSessionStore(Duration ttl) {
        this(ttl, Clock.system(SEOUL));
    }

    InMemorySearchSessionStore(Duration ttl, Clock clock) {
        this.ttl = ttl;
        this.clock = clock;
    }

    @Override
    public void put(String userId, SearchSession session) {
        sessions.put(userId, session);
    }

    @Override
    public Optional<SearchSession> get(String userId) {
        SearchSession session = sessions.get(userId);
        if (session == null) {
            return Optional.empty();
        }
        // POLICY: TTL 초과 세션은 유효한 검색 세션으로 취급하지 않는다 (ref: plan.md#ADR-25, data-model.md#2.5).
        if (isExpired(session)) {
            sessions.remove(userId);
            return Optional.empty();
        }
        return Optional.of(session);
    }

    @Override
    public void clear(String userId) {
        sessions.remove(userId);
    }

    private boolean isExpired(SearchSession session) {
        Duration age = Duration.between(session.createdAt(), OffsetDateTime.now(clock));
        return age.compareTo(ttl) > 0;
    }
}
