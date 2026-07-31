package com.devwuu.mocha.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.devwuu.mocha.domain.Source;
import com.devwuu.mocha.repository.entity.EntryEntity;
import com.devwuu.mocha.repository.entity.NoteEntity;
import com.devwuu.mocha.repository.entity.SourcedValue;
import com.devwuu.mocha.support.PostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * TΔ4 (changes/0028-rdb-storage) — 감사 컬럼 배선 검증 (Q-5 / delta.md#감사-컬럼).
 *
 * <p>검증 대상은 "저장 시 {@code note}·{@code entry}의 4개 컬럼이 채워지는가"와 "변경 주체가 실제로
 * 판별되는가" 둘이다. 값 주입 경로가 Spring Data Auditing이라 단위 테스트로는 관측되지 않는다 —
 * {@code AuditingEntityListener}가 붙는 실 persist/flush가 필요하다.
 *
 * <p>TΔ10a에서 <b>Testcontainers 기반으로 이관</b>했다(AC-Δ2 — 인메모리 대체 금지). 로컬 Postgres에 붙던
 * 시절에는 앞선 실행이 남긴 행이 그대로 있었지만, 이제 컨테이너는 매 실행 빈 DB에서 시작한다.
 * 테스트 간 격리는 {@code @Transactional} 롤백이 소유한다.
 */
@Transactional
@Import(EntityAuditingTest.FixedClockConfig.class)
class EntityAuditingTest extends PostgresIntegrationTest {

    @Autowired
    EntityManager em;

    @Autowired
    MutableClock clock;

    @Test
    @DisplayName("Q-5: note 저장 시 감사 컬럼 4종이 채워진다 — 주체 기본값은 agent")
    void noteIsAudited() {
        OffsetDateTime at = OffsetDateTime.now(clock);

        NoteEntity note = persistNote();

        assertThat(note.getCreatedAt()).isEqualTo(at);
        assertThat(note.getModifiedAt()).isEqualTo(at);
        assertThat(note.getCreatedBy()).isEqualTo("agent");
        assertThat(note.getModifiedBy()).isEqualTo("agent");
    }

    @Test
    @DisplayName("Q-5: entry 저장 시 감사 컬럼 4종이 채워진다")
    void entryIsAudited() {
        NoteEntity note = persistNote();
        OffsetDateTime at = OffsetDateTime.now(clock);

        EntryEntity entry = new EntryEntity(note.getId(), LocalDate.of(2026, 7, 31));
        em.persist(entry);
        em.flush();

        assertThat(entry.getCreatedAt()).isEqualTo(at);
        assertThat(entry.getModifiedAt()).isEqualTo(at);
        assertThat(entry.getCreatedBy()).isEqualTo("agent");
        assertThat(entry.getModifiedBy()).isEqualTo("agent");
    }

    @Test
    @DisplayName("Q-5: 변경 주체는 상수가 아니라 실행 컨텍스트에서 온다 — user 경로가 실제로 다른 값을 남긴다")
    void auditorComesFromContext() {
        NoteEntity[] holder = new NoteEntity[1];

        AuditActorContext.runAs(AuditActor.USER, () -> holder[0] = persistNote());

        assertThat(holder[0].getCreatedBy()).isEqualTo("user");
        assertThat(holder[0].getModifiedBy()).isEqualTo("user");
        // 스코프를 벗어나면 기본 주체로 돌아온다 — 다음 쓰기에 user가 새지 않는다.
        assertThat(persistNote().getCreatedBy()).isEqualTo("agent");
    }

    @Test
    @DisplayName("Q-5: 수정 시 modified_*만 갱신되고 created_*는 보존된다")
    void updateTouchesModifiedOnly() {
        NoteEntity note = persistNote();
        OffsetDateTime createdAt = note.getCreatedAt();

        clock.advance(Duration.ofMinutes(5));
        AuditActorContext.runAs(AuditActor.USER, () -> {
            note.updateRoastLevel(new SourcedValue("light", Source.USER));
            em.flush();
        });

        assertThat(note.getCreatedAt()).isEqualTo(createdAt);
        assertThat(note.getCreatedBy()).isEqualTo("agent");
        assertThat(note.getModifiedAt()).isEqualTo(createdAt.plusMinutes(5));
        assertThat(note.getModifiedBy()).isEqualTo("user");
    }

    private NoteEntity persistNote() {
        NoteEntity note = new NoteEntity(
                new SourcedValue("에티오피아 첼베사", Source.USER), null, null, null, "에티오피아첼베사", null);
        em.persist(note);
        em.flush();
        return note;
    }

    /**
     * 감사 타임스탬프를 정확한 값으로 단언하려면 시각이 제어 가능해야 한다. 빈 이름을 {@code testClock}으로
     * 두어 {@code CommonConfig.clock}과 충돌시키지 않고 {@code @Primary}로만 앞선다 — 빈 오버라이딩을
     * 켜지 않는다.
     */
    @TestConfiguration
    static class FixedClockConfig {

        @Bean
        @Primary
        MutableClock testClock() {
            return new MutableClock(Instant.parse("2026-07-31T02:00:00Z"), ZoneId.of("Asia/Seoul"));
        }
    }

    static final class MutableClock extends Clock {

        private Instant instant;
        private final ZoneId zone;

        MutableClock(Instant instant, ZoneId zone) {
            this.instant = instant;
            this.zone = zone;
        }

        void advance(Duration amount) {
            this.instant = instant.plus(amount);
        }

        @Override
        public ZoneId getZone() {
            return zone;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return new MutableClock(instant, zone);
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
