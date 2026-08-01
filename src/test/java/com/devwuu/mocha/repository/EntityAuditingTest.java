package com.devwuu.mocha.repository;

import static org.assertj.core.api.Assertions.assertThat;

import com.devwuu.mocha.SingleUser;
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
 * TΔ4 (changes/0028-rdb-storage) — 감사 컬럼 배선 검증
 * (delta.md#감사-컬럼, 축 개정은 changes/0029-app-interface/delta.md#D-13).
 *
 * <p>검증 대상은 "저장 시 {@code note}·{@code entry}의 4개 컬럼이 채워지는가"와 "수정이 무엇을
 * 건드리는가" 둘이다. 값 주입 경로가 Spring Data Auditing이라 단위 테스트로는 관측되지 않는다 —
 * {@code AuditingEntityListener}가 붙는 실 persist/flush가 필요하다.
 *
 * <p><b>D-13에서 축 하나가 사라졌다</b>: 구 {@code _by}는 «변경 주체»({@code agent}/{@code user})라
 * 그 판별이 실행 컨텍스트에서 오는지가 검증 대상이었는데, 이제 <b>이 행을 쓴 사용자</b>라 1인용에서는
 * 한 값이다. 그래서 <i>"컨텍스트에서 온다"</i> 케이스가 <i>"단일 사용자 식별자가 들어간다"</i>로 바뀌었다.
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
    @DisplayName("D-13: note 저장 시 감사 컬럼 4종이 채워진다 — 주체는 이 행을 쓴 사용자다")
    void noteIsAudited() {
        OffsetDateTime at = OffsetDateTime.now(clock);

        NoteEntity note = persistNote();

        assertThat(note.getCreatedAt()).isEqualTo(at);
        assertThat(note.getModifiedAt()).isEqualTo(at);
        assertThat(note.getCreatedBy()).isEqualTo(SingleUser.ID);
        assertThat(note.getModifiedBy()).isEqualTo(SingleUser.ID);
    }

    @Test
    @DisplayName("D-13: entry 저장 시 감사 컬럼 4종이 채워진다")
    void entryIsAudited() {
        NoteEntity note = persistNote();
        OffsetDateTime at = OffsetDateTime.now(clock);

        EntryEntity entry = new EntryEntity(note.getId(), LocalDate.of(2026, 7, 31));
        em.persist(entry);
        em.flush();

        assertThat(entry.getCreatedAt()).isEqualTo(at);
        assertThat(entry.getModifiedAt()).isEqualTo(at);
        assertThat(entry.getCreatedBy()).isEqualTo(SingleUser.ID);
        assertThat(entry.getModifiedBy()).isEqualTo(SingleUser.ID);
    }

    @Test
    @DisplayName("D-13: 쓰기 경로가 달라도 주체는 같다 — 에이전트 커밋도 UI 수정도 결국 이 사용자가 확정한다")
    void everyWriteCarriesTheSameUser() {
        // 구 축은 여기서 agent/user가 갈렸다. 갈릴 것이 없어진 것이 D-13의 실체다 — 값의 출처를 가르는
        // 일은 필드 단위 Source(user/photo/search)가 하고, 행이 답하는 것은 "누가 썼나"뿐이다.
        assertThat(persistNote().getCreatedBy()).isEqualTo(SingleUser.ID);
        assertThat(persistNote().getModifiedBy()).isEqualTo(SingleUser.ID);
    }

    @Test
    @DisplayName("D-13: 수정 시 modified_*만 갱신되고 created_*는 보존된다")
    void updateTouchesModifiedOnly() {
        NoteEntity note = persistNote();
        OffsetDateTime createdAt = note.getCreatedAt();

        clock.advance(Duration.ofMinutes(5));
        note.updateRoastLevel(new SourcedValue("light", Source.USER));
        em.flush();

        assertThat(note.getCreatedAt()).isEqualTo(createdAt);
        assertThat(note.getCreatedBy()).isEqualTo(SingleUser.ID);
        assertThat(note.getModifiedAt()).isEqualTo(createdAt.plusMinutes(5));
        assertThat(note.getModifiedBy()).isEqualTo(SingleUser.ID);
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
