package com.devwuu.mocha.config;

import com.devwuu.mocha.json.MochaObjectMapper;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.ZoneId;

/**
 * 전역 성격 공통 빈 배선 (ref: plan.md#ADR-63, changes/0024 TΔ1a).
 * <p>POLICY: 내부 협력자 조립·전역 인스턴스(Clock·ObjectMapper) 생성은 config/가 소유 —
 * 수신·라우터·tool 계층에서 협력자 new·전역 인스턴스 자체 생성 금지 (ref: plan.md#ADR-63).
 */
@Configuration
public class CommonConfig {

    // 날짜/타임스탬프는 Asia/Seoul 기준(V-3) — 시계 생성의 단일 소유 지점(ADR-63).
    // 모델 대면 문자열 "Asia/Seoul" 2곳(TurnPromptAssembler.TIMEZONE_LABEL, web_search 위치
    // 파라미터)은 계약 영역이라 이 빈으로 교체하지 않는다 — 의미 연결은 이 주석으로만.
    @Bean
    public Clock clock() {
        return Clock.system(ZoneId.of("Asia/Seoul"));
    }

    // 도메인 JSON 규칙(snake_case·오프셋 보존)의 단일 생성 지점(ADR-63) — 규칙 자체는
    // MochaObjectMapper가 소유하고, 여기는 인스턴스 생성만 소유한다. Jackson 3 ObjectMapper는
    // 불변이라 싱글턴 공유가 안전하다.
    // 선언 타입은 구체 JsonMapper여야 한다 — Boot Jackson 자동구성(@ConditionalOnMissingBean,
    // 추론 타입 JsonMapper)이 이 빈을 보고 물러나 앱의 유일한 매퍼가 된다. 상위 ObjectMapper로
    // 선언하면 Boot의 @Primary 기본 매퍼(snake_case 아님)가 전 주입 지점을 가로챈다 —
    // ConfigDefaultsTest의 ADR-63 가드가 이 조건을 자동구성 포함 컨텍스트로 박는다.
    // POLICY 전환(ADR-63): 종전 불변식 "Spring 기본 ObjectMapper와 분리해 snake_case·오프셋 보존
    // 규칙이 다른 곳에 새지 않게 한다"(구 RepositoryConfig)는 이 승격으로 의도적으로 폐기 —
    // 컨텍스트 매퍼를 소비하는 기능(actuator·web 계층 등)을 들이면 도메인 JSON 규칙이 그 표면에도
    // 적용된다. 그런 기능 도입 시 매퍼 분리를 재론한다.
    @Bean
    public JsonMapper objectMapper() {
        return MochaObjectMapper.create();
    }
}
