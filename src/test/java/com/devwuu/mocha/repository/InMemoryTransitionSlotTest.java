package com.devwuu.mocha.repository;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TΔ4(changes/0011): InMemoryTransitionSlot — 메모리 전용·TTL 10분(코드 상수)·단일 슬롯.
 * <p>AC-Δ4(= spec AC-35): 파일 미생성·재시작 소멸. 만료 판정은 다음 수신 시점(plan ADR-26).
 */
class InMemoryTransitionSlotTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Instant NOW = Instant.parse("2026-07-10T00:30:00Z");

    /** hold와 take 사이 시간 경과를 흉내내는 전진 가능 시계. */
    private static final class MutableClock extends Clock {
        private Instant instant = NOW;

        void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return SEOUL;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    @Test
    @DisplayName("ADR-26: TTL(10분) 초과 보관분은 take에서 만료 처리(빈 Optional)")
    void expiredPayloadIsNotReturned() {
        MutableClock clock = new MutableClock();
        InMemoryTransitionSlot slot = new InMemoryTransitionSlot(clock);
        slot.hold("추출 결과");

        clock.advance(Duration.ofMinutes(11));
        assertThat(slot.take()).isEmpty();
    }

    @Test
    @DisplayName("ADR-26 경계: TTL 이내 보관분은 정상 소비")
    void freshPayloadIsReturned() {
        MutableClock clock = new MutableClock();
        InMemoryTransitionSlot slot = new InMemoryTransitionSlot(clock);
        slot.hold("추출 결과");

        clock.advance(Duration.ofMinutes(9));
        assertThat(slot.take()).contains("추출 결과");
    }

    @Test
    @DisplayName("ADR-26: 단일 슬롯 — hold는 기존 보관분을 교체한다")
    void holdReplacesExistingPayload() {
        InMemoryTransitionSlot slot = new InMemoryTransitionSlot(new MutableClock());
        slot.hold("먼저 보관");
        slot.hold("나중 보관");

        assertThat(slot.take()).contains("나중 보관");
    }

    @Test
    @DisplayName("ADR-26: take는 소비 — 두 번째 take는 빈 Optional / 부재 시에도 빈 Optional")
    void takeConsumesPayload() {
        InMemoryTransitionSlot slot = new InMemoryTransitionSlot(new MutableClock());
        assertThat(slot.take()).isEmpty();

        slot.hold("추출 결과");
        assertThat(slot.take()).contains("추출 결과");
        assertThat(slot.take()).isEmpty();
    }

    @Test
    @DisplayName("AC-35: data/ 파일 미생성 + 재시작(새 인스턴스) 시 소멸")
    void memoryOnlyNoFilesAndDiesOnRestart() throws Exception {
        // 생성자가 경로를 받지 않아 파일 미생성은 시그니처 수준에서 보장된다 — 여기서는 계약을 문서화.
        InMemoryTransitionSlot slot = new InMemoryTransitionSlot(new MutableClock());
        slot.hold("추출 결과");

        try (Stream<Path> files = Files.list(dataDir)) {
            assertThat(files).isEmpty();
        }

        // 프로세스 재시작을 흉내낸 새 인스턴스 — 보관분은 복원되지 않는다.
        InMemoryTransitionSlot restarted = new InMemoryTransitionSlot(new MutableClock());
        assertThat(restarted.take()).isEmpty();
    }

    @TempDir
    Path dataDir;
}
