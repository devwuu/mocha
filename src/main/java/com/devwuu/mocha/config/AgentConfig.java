package com.devwuu.mocha.config;

import com.devwuu.mocha.agent.AgentClient;
import com.devwuu.mocha.agent.OpenAiAgentClient;
import com.devwuu.mocha.agent.conversation.ConversationTranscript;
import com.devwuu.mocha.json.MochaObjectMapper;
import com.openai.client.OpenAIClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
    @Bean
    public AgentClient agentClient(
            OpenAIClient openAiClient,
            @Value("${mocha.agent.model:gpt-5.4}") String model,
            @Value("${mocha.agent.max-tool-calls:8}") int maxToolCalls) {
        return new OpenAiAgentClient(openAiClient, model, maxToolCalls, MochaObjectMapper.create());
    }

    @Bean
    public ConversationTranscript conversationTranscript(
            @Value("${mocha.agent.transcript-max-turns:20}") int maxTurns,
            @Value("${mocha.agent.transcript-ttl:1h}") Duration ttl) {
        return new ConversationTranscript(maxTurns, ttl);
    }
}
