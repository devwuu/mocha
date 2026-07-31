package com.devwuu.mocha.config;

import com.devwuu.mocha.repository.AuditActorContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * 감사 컬럼(created_at/created_by/modified_at/modified_by) 주입 배선
 * (ref: changes/0028-rdb-storage/delta.md#감사-컬럼, Q-5 / tasks.md TΔ4).
 *
 * <p>POLICY: 내부 협력자 조립·전역 인스턴스 생성은 config/가 소유 (ref: plan.md#ADR-63).
 * 필드를 채우는 주체는 {@code AuditingEntityListener}이고, <b>무엇을 채울지</b>는 여기 두 공급자가
 * 소유한다 — 저장소 구현체·변환기는 감사 필드를 건드리지 않는다.
 */
@Configuration
@EnableJpaAuditing(auditorAwareRef = "auditActorProvider", dateTimeProviderRef = "auditingDateTimeProvider")
public class JpaAuditingConfig {

    /**
     * 변경 주체 공급자. 판별은 {@link AuditActorContext}가 소유하고 여기서는 컬럼 표기로만 옮긴다.
     * <p>빈 Optional을 반환하지 않는다 — 컬럼이 NOT NULL이라 주체를 모르는 상태가 INSERT 실패로만
     * 드러나면 원인 추적이 어렵다. 컨텍스트가 언제나 기본값(agent)을 갖는 것이 이 계약이다.
     */
    @Bean
    public AuditorAware<String> auditActorProvider() {
        return () -> Optional.of(AuditActorContext.current().columnValue());
    }

    /**
     * 감사 타임스탬프 공급자.
     * <p>Spring Data 기본 공급자({@code CurrentDateTimeProvider})를 쓰지 않는 이유는 두 가지다:
     * 그쪽은 시스템 기본 시간대를 보므로 이 프로젝트의 Asia/Seoul 고정(ADR-63)과 갈라지고,
     * {@link Clock} 빈을 경유하지 않아 테스트에서 시각을 고정할 수 없다.
     *
     * <p>POLICY: 감사 타임스탬프의 표기는 <b>UTC</b>다 (사용자 확정 2026-07-31, TΔ5a 실측 발단).
     * {@code timestamptz}는 오프셋을 저장하지 않는다 — Postgres가 UTC 인스턴트로 정규화하므로 조회
     * 표기는 드라이버가 정하고(pgjdbc는 UTC), Hibernate에는 이를 바꿀 설정이 없다
     * ({@code hibernate.jdbc.time_zone}은 NATIVE 경로에 영향이 없음을 실측했다). 쓰기 표기를 맞추지
     * 않으면 <b>같은 저장소가 두 표기를 준다</b>: 영속성 컨텍스트에 살아 있는 엔티티는 {@code +09:00},
     * 다시 읽은 엔티티는 {@code Z}. 컬럼이 실제로 담는 것(인스턴트)에 표기를 맞춰 한 벌로 만든다.
     * <p>{@link Clock}(Asia/Seoul 고정, ADR-63)은 여전히 "지금"의 단일 소유자이고 인스턴트도 불변이다 —
     * 바뀌는 것은 표기뿐이다. 시각을 사람에게 보여줄 일이 생기면 그 표시 계층이 Asia/Seoul로 되돌린다.
     */
    @Bean
    public DateTimeProvider auditingDateTimeProvider(Clock clock) {
        return () -> Optional.of(OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC));
    }
}
