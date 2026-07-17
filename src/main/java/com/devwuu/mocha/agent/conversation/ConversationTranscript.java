package com.devwuu.mocha.agent.conversation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 작업 트랜스크립트 — 에이전트 턴의 대화 문맥을 사용자당 1건 유지한다
 * (ref: specs/coffee-note-agent/plan.md#ADR-46, spec FR-23, data-model.md#2.5).
 * <p>POLICY: 트랜스크립트는 메모리 전용·결정론 접힘(제안 성공·커밋·TTL) — {@code data/} 아래
 * 파일 생성 금지, 재시작 시 소멸 (ref: specs/coffee-note-agent/plan.md#ADR-46, spec NFR-2 예외).
 * <p>접힘은 전부 관측 가능한 결정론 이벤트다(LLM 판단·요약 콜 없음) — 명시 접힘은
 * {@link #clear(String, FoldTrigger)}(배선 지점은 {@link FoldTrigger} 참조), TTL 소멸·턴 상한 드롭은
 * 이 클래스가 내부 판정한다. TTL 만료 판정은 다음 접근 시점에 지연 수행한다(백그라운드 스케줄러
 * 없음 — 구 검색 세션·pending과 동일 패턴).
 */
public class ConversationTranscript {

    private static final Logger log = LoggerFactory.getLogger(ConversationTranscript.class);

    // 날짜/타임스탬프는 Asia/Seoul 기준 — pending과 동일(V-3).
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    /**
     * 명시 접힘 트리거 — 배선 지점 정의 (ref: plan.md#ADR-46 접힘 규칙 ①②).
     * TTL 소멸(규칙 ③)은 호출부 트리거가 아니라 내부 판정이라 여기 없다.
     */
    public enum FoldTrigger {
        /** ① 제안 tool 성공(pending 생성·갱신) — 배선: propose_record/propose_edit tool 구현체(ADR-45). 이후 문맥은 pending draft가 대신한다. */
        PROPOSAL_ACCEPTED,
        /** ② [저장] 커밋 — 배선: 저장 버튼 액션 핸들러(ADR-3 커밋 경로). */
        SAVE_COMMIT,
        /** ② [취소] 커밋 — 배선: 취소 버튼 액션 핸들러. */
        CANCEL_COMMIT
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
     */
    public ConversationTranscript(int maxTurns, Duration ttl) {
        this(maxTurns, ttl, Clock.system(SEOUL));
    }

    ConversationTranscript(int maxTurns, Duration ttl, Clock clock) {
        if (maxTurns < 1) {
            throw new IllegalArgumentException("maxTurns는 1 이상이어야 함: " + maxTurns);
        }
        Objects.requireNonNull(ttl, "ttl");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl은 양수여야 함: " + ttl);
        }
        this.maxTurns = maxTurns;
        this.ttl = ttl;
        this.clock = clock;
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

    /** 현재 문맥 조회 — TTL 경과 시 상태를 소멸시키고 빈 문맥을 반환한다(ADR-46 접힘 규칙 ③). */
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

    /** 명시 접힘 — 제안 성공·[저장]/[취소] 커밋 시 문맥을 비운다(ADR-46 접힘 규칙 ①②). 없으면 no-op. */
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
