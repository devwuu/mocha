package com.devwuu.mocha.config;

import com.devwuu.mocha.agent.AgentClient;
import com.devwuu.mocha.agent.OpenAiAgentClient;
import com.devwuu.mocha.agent.conversation.ConversationTranscript;
import com.devwuu.mocha.llm.OpenAiUtteranceSegmenter;
import com.devwuu.mocha.llm.UtteranceSegmenter;
import com.openai.client.OpenAIClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;

/**
 * 에이전트 루프·트랜스크립트 빈 배선 (ref: plan.md#ADR-44/ADR-46/ADR-50, changes/0018 TΔ4).
 * <p>루프 상한·트랜스크립트 파라미터는 설정 키로 승격됐다(ADR-50 — "튜닝 파라미터는 코드 상수" 관례의
 * 사용자 확정 예외, 초기 운영에서 yml 조정 대상). 라우터 배선은 TΔ7b에서.
 * <p>POLICY: 새 mocha.* 키는 코드 default를 반드시 갖는다 — 설정 부재로 기동이 막히지 않는다
 * (ref: plan.md#ADR-50).
 */
@Configuration
public class AgentConfig {

    // 에이전트 루프 모델: 초기값 gpt-5.4-mini(0018 TΔ0a 실측)에서 gpt-5.4로 교체(2026-07-21 사용자 확정) —
    // 경량이 다중 날짜 분리 규칙(AC-77)을 프롬프트·스키마 보강에도 반복 위반(changes/0021 TΔ3b 스모크 관측).
    // 턴 상한 3종(ADR-44·62): tool 호출 수 + 누적 토큰 + 경과 시간 — 기본값은 관측 표본 부족(6건)으로
    // 보수적 시작, 누적 usage 관측 축적 후 yml 조정(plan §5·§6).
    @Bean
    public AgentClient agentClient(
            OpenAIClient openAiClient,
            @Value("${mocha.agent.model:gpt-5.4}") String model,
            @Value("${mocha.agent.max-tool-calls:8}") int maxToolCalls,
            @Value("${mocha.agent.max-turn-tokens:100000}") int maxTurnTokens,
            @Value("${mocha.agent.turn-timeout:60s}") Duration turnTimeout,
            ObjectMapper mapper) {
        return new OpenAiAgentClient(openAiClient, model, maxToolCalls, maxTurnTokens, turnTimeout, mapper);
    }

    // 다중 날짜 세그먼트 분리 경계(ADR-61) — 탐지기가 절대 날짜 2개 이상을 찾은 턴에만 루프 전 1콜.
    // 분리만 하는 좁은 작업이라 전용 경량 키(mocha.agent.segmenter-model — plan §5). 라우터 통합은 TΔ3b.
    @Bean
    public UtteranceSegmenter utteranceSegmenter(
            OpenAIClient openAiClient,
            @Value("${mocha.agent.segmenter-model:gpt-5.4-mini}") String model,
            ObjectMapper mapper) {
        return new OpenAiUtteranceSegmenter(openAiClient, model, mapper);
    }

    @Bean
    public ConversationTranscript conversationTranscript(
            @Value("${mocha.agent.transcript-max-turns:20}") int maxTurns,
            @Value("${mocha.agent.transcript-ttl:1h}") Duration ttl,
            Clock clock) {
        return new ConversationTranscript(maxTurns, ttl, clock);
    }
}
