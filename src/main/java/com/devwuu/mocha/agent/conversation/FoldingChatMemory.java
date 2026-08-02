package com.devwuu.mocha.agent.conversation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 작업 트랜스크립트 — 에이전트 턴의 대화 문맥을 사용자당 1건 유지한다
 * (ref: specs/coffee-note-agent/plan.md#ADR-46, spec FR-23, data-model.md#2.5).
 * <p>POLICY: 트랜스크립트는 메모리 전용·결정론 접힘(커밋·TTL) — {@code data/} 아래
 * 파일 생성 금지, 재시작 시 소멸 (ref: specs/coffee-note-agent/plan.md#ADR-46, spec NFR-2 예외).
 * <p>changes/0029 TΔ3: 접힘 3규칙 → 2규칙 — <b>제안 tool 성공 시 접힘(구 규칙 ①)이 폐기</b>됐다. draft는
 * 이제 턴 입력으로 매번 실려 오므로(TΔ2) 문맥을 대신하는 것이 아니라 대화와 함께 살아 있고, 다중 날짜
 * 이어가기(ADR-61)의 남은 날짜 문맥이 [저장] 시점까지 유지된다(baseline.md §4.3).
 * <p>접힘은 전부 관측 가능한 결정론 이벤트다(LLM 판단·요약 콜 없음) — 명시 접힘은
 * {@link #clear(String, FoldTrigger)}(배선 지점은 {@link FoldTrigger} 참조), TTL 소멸·턴 상한 드롭은
 * 이 클래스가 내부 판정한다. TTL 만료 판정은 다음 접근 시점에 지연 수행한다(백그라운드 스케줄러
 * 없음 — 구 검색 세션·pending과 동일 패턴).
 */
public class FoldingChatMemory {

    private static final Logger log = LoggerFactory.getLogger(FoldingChatMemory.class);

    /**
     * 명시 접힘 트리거 — 배선 지점 정의 (ref: plan.md#ADR-46 접힘 규칙, 0029 TΔ3 개정본의 커밋 규칙).
     * TTL 소멸은 호출부 트리거가 아니라 내부 판정이라 여기 없다.
     */
    public enum FoldTrigger {
        /** [저장] 커밋 — 배선: {@code POST /api/notes}(ADR-3 커밋 경로). */
        SAVE_COMMIT,
        /**
         * 폼이 닫혀 그 작업의 문맥이 끝났다 — 배선: {@code POST /api/agent/cancel}.
         *
         * <p><b>구 이름은 {@code CANCEL_COMMIT}이었다</b>(changes/0029 TΔ30 개명): TΔ28b가 <b>수정 저장(PATCH)
         * 뒤 뒷정리</b>에도 같은 통지를 쓰면서 로그의 사유가 실제(저장 확정)와 어긋났다. 어긋남의 원인은
         * 배선이 아니라 <b>라벨이 서버가 아는 것보다 많이 말한 것</b>이다 — 취소인지 저장 후 정리인지는
         * 클라이언트만 알고, 서버에 도착하는 사실은 <i>"이 작업은 끝났다"</i>뿐이다. 라벨을 그 수준으로
         * 낮추자 어긋날 것이 없어졌고, <b>클라이언트 계약({@code agent-cancel.contract.json} —
         * "본문도 응답도 없다")은 한 바이트도 열리지 않았다.</b>
         */
        FORM_CLOSED
    }

    /** 사용자 1명의 트랜스크립트 상태 — 턴 목록 + TTL 판정용 타임스탬프(data-model §2.5). */
    private static final class State {
        final Deque<TranscriptTurn> turns = new ArrayDeque<>();
        final OffsetDateTime createdAt;
        OffsetDateTime lastActiveAt;

        State(OffsetDateTime now) {
            this.createdAt = now;
            this.lastActiveAt = now;
        }
    }

    private final Map<String, State> transcripts = new ConcurrentHashMap<>();
    private final int maxTurns;
    private final Duration ttl;
    private final Clock clock;

    /**
     * @param maxTurns 턴 수 상한(mocha.agent.transcript-max-turns) — 초과분은 오래된 턴부터 드롭
     * @param ttl      TTL(mocha.agent.transcript-ttl) — 경과 시 view가 빈 문맥 반환
     * @param clock    시계(Asia/Seoul — V-3, pending과 동일) — config 공통 빈 주입(ADR-63), 테스트에서 시간 고정용
     */
    public FoldingChatMemory(int maxTurns, Duration ttl, Clock clock) {
        if (maxTurns < 1) {
            throw new IllegalArgumentException("maxTurns는 1 이상이어야 함: " + maxTurns);
        }
        Objects.requireNonNull(ttl, "ttl");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl은 양수여야 함: " + ttl);
        }
        this.maxTurns = maxTurns;
        this.ttl = ttl;
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /** 턴 1건 추가 — 만료된 트랜스크립트 위에는 새로 시작하고, 상한 초과분은 오래된 턴부터 버린다. */
    public void append(String userId, TranscriptTurn turn) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(turn, "turn");
        transcripts.compute(userId, (id, existing) -> {
            OffsetDateTime now = OffsetDateTime.now(clock);
            State state = existing;
            if (state == null || isExpired(state, now)) {
                if (state != null) {
                    logExpiry(id, state);
                }
                state = new State(now);
            }
            state.turns.addLast(turn);
            if (state.turns.size() > maxTurns) {
                state.turns.removeFirst();
                // 요약 비도입 — 드롭 후 문맥 절단 불편 관측 시 재론 (ref: spec FR-23, plan.md#§6).
                log.info("transcript 턴 상한 드롭: user={} maxTurns={}", id, maxTurns);
            }
            state.lastActiveAt = now;
            return state;
        });
    }

    /** 현재 문맥 조회 — TTL 경과 시 상태를 소멸시키고 빈 문맥을 반환한다(ADR-46 접힘 TTL 규칙). */
    public List<TranscriptTurn> view(String userId) {
        Objects.requireNonNull(userId, "userId");
        State state = transcripts.get(userId);
        if (state == null) {
            return List.of();
        }
        // POLICY: TTL 판정은 last_active_at 기준 — 진행 중 대화가 절대 시간으로 끊기지 않게
        // (ref: specs/coffee-note-agent/data-model.md#2.5).
        if (isExpired(state, OffsetDateTime.now(clock))) {
            transcripts.remove(userId);
            logExpiry(userId, state);
            return List.of();
        }
        return List.copyOf(state.turns);
    }

    /** 명시 접힘 — [저장]/[취소] 커밋 시 문맥을 비운다(ADR-46 접힘 커밋 규칙). 없으면 no-op. */
    public void clear(String userId, FoldTrigger trigger) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(trigger, "trigger");
        State removed = transcripts.remove(userId);
        if (removed != null) {
            // 접힘 이벤트 로깅 — AC-Δ9 턴 관측 (ref: plan.md#§6 트랜스크립트 관측).
            log.info("transcript 접힘: user={} trigger={} turns={}", userId, trigger, removed.turns.size());
        }
    }

    private boolean isExpired(State state, OffsetDateTime now) {
        return Duration.between(state.lastActiveAt, now).compareTo(ttl) > 0;
    }

    private void logExpiry(String userId, State state) {
        log.info("transcript TTL 소멸: user={} ttl={} turns={} createdAt={}",
                userId, ttl, state.turns.size(), state.createdAt);
    }
}
