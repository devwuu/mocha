package com.devwuu.mocha.config;

import com.devwuu.mocha.repository.AuditActorContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.domain.AuditorAware;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import java.time.Clock;
import java.time.OffsetDateTime;
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
     */
    @Bean
    public DateTimeProvider auditingDateTimeProvider(Clock clock) {
        return () -> Optional.of(OffsetDateTime.now(clock));
    }
}
