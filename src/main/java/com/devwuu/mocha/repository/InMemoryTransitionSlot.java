package com.devwuu.mocha.repository;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 메모리 전용 {@link TransitionSlot} — 필드 참조 하나에만 보관해 프로세스 재시작 시 소멸한다
 * (ref: plan.md#ADR-26, spec NFR-2 예외/AC-35, changes/0011).
 * <p>POLICY: 전환 슬롯은 {@code data/} 아래 어떤 파일도 만들지 않는다 — 메모리 전용 (ref: plan.md#ADR-25 POLICY).
 * <p>POLICY: TTL 10분은 코드 상수 — 설정 키를 두지 않는다 (ref: data-model.md#2.5, plan.md#ADR-26).
 */
public class InMemoryTransitionSlot implements TransitionSlot {

    // 날짜/타임스탬프는 Asia/Seoul 기준 — pending과 동일(V-3).
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    static final Duration TTL = Duration.ofMinutes(10);

    private final AtomicReference<Held> slot = new AtomicReference<>();
    private final Clock clock;

    public InMemoryTransitionSlot() {
        this(Clock.system(SEOUL));
    }

    InMemoryTransitionSlot(Clock clock) {
        this.clock = clock;
    }

    @Override
    public void hold(Object payload) {
        slot.set(new Held(payload, OffsetDateTime.now(clock)));
    }

    @Override
    public Optional<Object> take() {
        Held held = slot.getAndSet(null);
        if (held == null || isExpired(held)) {
            return Optional.empty();
        }
        return Optional.of(held.payload());
    }

    private boolean isExpired(Held held) {
        Duration age = Duration.between(held.heldAt(), OffsetDateTime.now(clock));
        return age.compareTo(TTL) > 0;
    }

    private record Held(Object payload, OffsetDateTime heldAt) {
    }
}
