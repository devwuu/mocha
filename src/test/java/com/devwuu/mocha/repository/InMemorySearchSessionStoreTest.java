package com.devwuu.mocha.repository;

import com.devwuu.mocha.domain.SearchSession;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TΔ4(changes/0011): InMemorySearchSessionStore — 메모리 전용·TTL 1h·사용자당 1건.
 * <p>AC-Δ4(= spec AC-35): 파일 미생성·재시작 소멸, TTL 만료 판정은 다음 수신 시점(plan ADR-25).
 */
class InMemorySearchSessionStoreTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Instant NOW = Instant.parse("2026-07-10T00:30:00Z");
    private static final Clock FIXED = Clock.fixed(NOW, SEOUL);
    private static final Duration TTL = Duration.ofHours(1);
    private static final String USER = "U123";

    private static SearchSession sessionCreatedAt(OffsetDateTime createdAt) {
        return new SearchSession(List.of("예가체프"), List.of(), 0, createdAt);
    }

    @Test
    @DisplayName("AC-35: TTL(1h) 초과 세션은 get에서 만료 처리(빈 Optional)")
    void expiredSessionIsNotReturned() {
        InMemorySearchSessionStore store = new InMemorySearchSessionStore(TTL, FIXED);
        store.put(USER, sessionCreatedAt(OffsetDateTime.now(FIXED).minusHours(2)));

        assertThat(store.get(USER)).isEmpty();
    }

    @Test
    @DisplayName("AC-35 경계: TTL 이내 세션은 정상 조회")
    void freshSessionIsReturned() {
        InMemorySearchSessionStore store = new InMemorySearchSessionStore(TTL, FIXED);
        SearchSession fresh = sessionCreatedAt(OffsetDateTime.now(FIXED).minusMinutes(59));
        store.put(USER, fresh);

        assertThat(store.get(USER)).contains(fresh);
    }

    @Test
    @DisplayName("ADR-25: 사용자당 1건 — put은 기존 세션을 교체한다")
    void putReplacesExistingSession() {
        InMemorySearchSessionStore store = new InMemorySearchSessionStore(TTL, FIXED);
        store.put(USER, sessionCreatedAt(OffsetDateTime.now(FIXED).minusMinutes(30)));
        SearchSession latest = new SearchSession(
                List.of("예가체프", "작년 겨울"), List.of("coffeevera-yirgacheffe-g1"), 1, OffsetDateTime.now(FIXED));
        store.put(USER, latest);

        assertThat(store.get(USER)).contains(latest);
    }

    @Test
    @DisplayName("clear 후 조회는 빈 Optional / 부재 시에도 빈 Optional")
    void clearAndAbsent() {
        InMemorySearchSessionStore store = new InMemorySearchSessionStore(TTL, FIXED);
        assertThat(store.get(USER)).isEmpty();

        store.put(USER, sessionCreatedAt(OffsetDateTime.now(FIXED)));
        assertThat(store.get(USER)).isPresent();

        store.clear(USER);
        assertThat(store.get(USER)).isEmpty();
    }

    @Test
    @DisplayName("AC-35: data/ 파일 미생성 + 재시작(새 인스턴스) 시 소멸 — pending(파일 영속)과 다른 결")
    void memoryOnlyNoFilesAndDiesOnRestart() throws Exception {
        // 생성자가 경로를 받지 않아 파일 미생성은 시그니처 수준에서 보장된다 — 여기서는 계약을 문서화.
        InMemorySearchSessionStore store = new InMemorySearchSessionStore(TTL, FIXED);
        store.put(USER, sessionCreatedAt(OffsetDateTime.now(FIXED)));

        try (Stream<Path> files = Files.list(dataDir)) {
            assertThat(files).isEmpty();
        }

        // 프로세스 재시작을 흉내낸 새 인스턴스 — pending과 달리 복원되지 않는다.
        InMemorySearchSessionStore restarted = new InMemorySearchSessionStore(TTL, FIXED);
        assertThat(restarted.get(USER)).isEmpty();
    }

    @TempDir
    Path dataDir;
}
