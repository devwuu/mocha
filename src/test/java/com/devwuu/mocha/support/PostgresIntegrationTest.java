package com.devwuu.mocha.support;

import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

/**
 * TΔ10a (changes/0028-rdb-storage) — 실 Postgres에 붙는 통합 테스트의 공통 기반 (AC-Δ2).
 *
 * <p>상속하면 {@code test} 프로파일(={@code application-test.yaml})과 Slack 소켓을 끄는 토큰 오버라이드가
 * 함께 붙는다. 접속 대상은 <b>로컬 개발 Postgres(docker-compose.yml)의 {@code test} 스키마</b>다 — 개발
 * 데이터가 사는 {@code public}과 스키마로 갈라 둔다.
 *
 * <p><b>인메모리(H2)로 대체하지 않는다</b>(백엔드 CLAUDE.md §5.2 정신). 검증 대상이 CHECK/UNIQUE 제약,
 * {@code numeric}·{@code jsonb}·{@code date} 타입 왕복, 직렬화라서 방언이 다른 엔진에서는 그린이 아무것도
 * 보증하지 않는다. 같은 이유로 스키마는 Hibernate가 아니라 <b>Flyway가 소유</b>한다({@code ddl-auto: validate}) —
 * 엔티티에는 제약 선언이 없어 {@code create-drop}으로 만든 스키마에는 V1의 제약 27건이 하나도 없다.
 *
 * <p><b>Testcontainers를 쓰지 않는다</b>(사용자 확정 2026-07-31). 1차 구현은 싱글턴 컨테이너였고, 실사용에서
 * 실행이 갈수록 느려지는 체감이 관측돼 로컬 인스턴스 재사용으로 바꿨다. 대가는 실행 전
 * {@code docker compose up -d}가 선행이라는 것이다 — 컨테이너가 지던 "환경 없이도 돈다"는 성질은 포기했다.
 *
 * <p>테스트 간 데이터 격리는 상속 클래스가 정한다({@code @Transactional} 롤백 등) — 트랜잭션 경계 자체가
 * 검증 대상인 저장소 테스트가 있어(TΔ5) 여기서 일괄로 감싸지 않는다.
 */
@SpringBootTest
@ActiveProfiles("test")
@Import(PostgresIntegrationTest.FreshTestSchema.class)
// 실 Slack 소켓에 연결하지 않도록 토큰을 빈 값으로 덮는다 — .env.local의 실토큰이 주입되면
// SmartLifecycle 시작 시 Socket Mode 연결을 시도한다.
@TestPropertySource(properties = {"mocha.slack.bot-token=", "mocha.slack.app-token="})
public abstract class PostgresIntegrationTest {

    /**
     * 스키마를 매 컨텍스트 기동마다 새로 만든다 — 앞선 실행이 남긴 행이나 옛 DDL이 다음 실행의 전제가
     * 되지 않게 한다. V1을 고쳐 체크섬이 깨져도 여기서 흡수된다(로컬 개발 DB와 달리 테스트 스키마에는
     * 보존할 이력이 없다).
     *
     * <p>{@code @TestConfiguration} 중첩 클래스라 컴포넌트 스캔에 잡히지 않는다 — 이 기반을 상속한
     * 컨텍스트에만 적용된다.
     */
    @TestConfiguration
    static class FreshTestSchema {

        @Bean
        FlywayMigrationStrategy cleanAndMigrate() {
            return flyway -> {
                flyway.clean();
                flyway.migrate();
            };
        }
    }
}
