package com.devwuu.mocha.agent;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ConversationTranscript의 접힘·TTL·상한 계약 검증 — 결정론 이벤트(제안 성공·커밋·TTL·턴 상한)로만
 * 문맥이 접히는지 확인한다 (ref: changes/0018 tasks.md TΔ3, plan.md#ADR-46, spec FR-23, AC-Δ6).
 */
class ConversationTranscriptTest {

    private static final String USER = "U123";
    private static final Duration TTL = Duration.ofHours(1);

    /** 테스트에서 시간을 임의로 흘리는 시계 — TTL 판정을 결정론적으로 만든다(CLAUDE.md §5.2). */
    static class SteppingClock extends Clock {
        private Instant current = Instant.parse("2026-07-17T10:00:00Z");
        private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

        void advance(Duration step) {
            current = current.plus(step);
        }

        @Override
        public Instant instant() {
            return current;
        }

        @Override
        public ZoneId getZone() {
            return SEOUL;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            throw new UnsupportedOperationException();
        }
    }

    private SteppingClock steppingClock;
    private ConversationTranscript transcript;
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() {
        steppingClock = new SteppingClock();
        transcript = new ConversationTranscript(20, TTL, steppingClock);
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(ConversationTranscript.class)).addAppender(logs);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(ConversationTranscript.class)).detachAppender(logs);
    }

    @Test
    @DisplayName("FR-23: append한 턴이 순서대로 view에 나온다")
    void appendAndViewKeepsOrder() {
        transcript.append(USER, new TranscriptTurn("예가체프 있잖아", "어떤 노트 말하는 거멍?"));
        transcript.append(USER, new TranscriptTurn("첼베사 말이야", "첼베사 카드 보냈멍"));

        List<TranscriptTurn> turns = transcript.view(USER);

        assertThat(turns).extracting(TranscriptTurn::userMessage)
                .containsExactly("예가체프 있잖아", "첼베사 말이야");
    }

    @Test
    @DisplayName("FR-23: 트랜스크립트는 사용자별로 격리된다 — 사용자당 1건")
    void transcriptsAreIsolatedPerUser() {
        transcript.append(USER, new TranscriptTurn("u1 발화", "u1 응답"));

        assertThat(transcript.view("U999")).isEmpty();
        assertThat(transcript.view(USER)).hasSize(1);
    }

    @ParameterizedTest
    @EnumSource(ConversationTranscript.FoldTrigger.class)
    @DisplayName("AC-Δ6/AC-61: 접힘 이벤트(제안 성공·[저장]·[취소]) 각각에서 문맥이 비워진다")
    void clearFoldsTranscriptOnEachTrigger(ConversationTranscript.FoldTrigger trigger) {
        transcript.append(USER, new TranscriptTurn("검색 왕복 1", "후보 목록"));
        transcript.append(USER, new TranscriptTurn("두 번째 거", "미리보기 보냈멍"));

        transcript.clear(USER, trigger);

        assertThat(transcript.view(USER)).isEmpty();
        // 접힘 이벤트 로깅 — AC-Δ9 관측 (plan §6).
        assertThat(logs.list).anyMatch(event ->
                event.getFormattedMessage().contains("접힘") && event.getFormattedMessage().contains(trigger.name()));
    }

    @Test
    @DisplayName("접힘: 트랜스크립트가 없는 사용자 clear는 no-op(예외·로그 없음)")
    void clearWithoutTranscriptIsNoOp() {
        transcript.clear(USER, ConversationTranscript.FoldTrigger.SAVE_COMMIT);

        assertThat(transcript.view(USER)).isEmpty();
        assertThat(logs.list).isEmpty();
    }

    @Test
    @DisplayName("AC-35: TTL 경과 시 트랜스크립트가 소멸하고 view는 빈 문맥을 반환한다")
    void ttlExpiryRemovesTranscript() {
        transcript.append(USER, new TranscriptTurn("그 커피 있잖아", "어떤 커피멍?"));

        steppingClock.advance(TTL.plusMinutes(1));

        assertThat(transcript.view(USER)).isEmpty();
        assertThat(logs.list).anyMatch(event -> event.getFormattedMessage().contains("TTL 소멸"));
    }

    @Test
    @DisplayName("TTL 판정은 마지막 활동 기준 — 진행 중 대화는 절대 시간으로 끊기지 않는다 (data-model §2.5)")
    void ttlIsJudgedFromLastActivity() {
        transcript.append(USER, new TranscriptTurn("첫 발화", "응답"));
        steppingClock.advance(Duration.ofMinutes(50));
        transcript.append(USER, new TranscriptTurn("둘째 발화", "응답"));
        steppingClock.advance(Duration.ofMinutes(50));

        // 시작으로부터 100분이지만 마지막 활동 후 50분 — 살아 있다.
        assertThat(transcript.view(USER)).hasSize(2);
    }

    @Test
    @DisplayName("TTL 경과 후 append는 이전 문맥 위가 아니라 새 트랜스크립트로 시작한다")
    void appendAfterExpiryStartsFresh() {
        transcript.append(USER, new TranscriptTurn("옛 문맥", "응답"));
        steppingClock.advance(TTL.plusMinutes(1));

        transcript.append(USER, new TranscriptTurn("새 문맥", "응답"));

        assertThat(transcript.view(USER)).extracting(TranscriptTurn::userMessage)
                .containsExactly("새 문맥");
    }

    @Test
    @DisplayName("FR-23: 턴 수 상한 초과분은 오래된 턴부터 드롭된다(요약 비도입)")
    void maxTurnsDropsOldestFirst() {
        ConversationTranscript small = new ConversationTranscript(3, TTL, steppingClock);
        small.append(USER, new TranscriptTurn("t1", "r1"));
        small.append(USER, new TranscriptTurn("t2", "r2"));
        small.append(USER, new TranscriptTurn("t3", "r3"));
        small.append(USER, new TranscriptTurn("t4", "r4"));

        assertThat(small.view(USER)).extracting(TranscriptTurn::userMessage)
                .containsExactly("t2", "t3", "t4");
        // 상한 드롭 로깅 — 문맥 절단 불편 관측 재료 (plan §6).
        assertThat(logs.list).anyMatch(event -> event.getFormattedMessage().contains("턴 상한 드롭"));
    }
}
