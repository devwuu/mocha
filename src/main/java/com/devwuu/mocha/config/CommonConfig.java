package com.devwuu.mocha.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
    // 모델 대면 문자열 "Asia/Seoul" 2곳(AgentContextAssembler.TIMEZONE_LABEL, web_search 위치
    // 파라미터)은 계약 영역이라 이 빈으로 교체하지 않는다 — 의미 연결은 이 주석으로만.
    @Bean
    public Clock clock() {
        return Clock.system(ZoneId.of("Asia/Seoul"));
    }
}
