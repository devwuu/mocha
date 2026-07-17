package com.devwuu.mocha.config;

import com.devwuu.mocha.agent.AgentClient;
import com.devwuu.mocha.agent.ConversationTranscript;
import com.devwuu.mocha.agent.OpenAiAgentClient;
import com.devwuu.mocha.llm.LlmClient;
import com.devwuu.mocha.llm.VisionClient;
import com.devwuu.mocha.pipeline.AliasGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TΔ4(changes/0018) — 설정 키 재편의 default 기동 단언 (ref: plan.md#ADR-50 POLICY).
 * <p>새 키 6종({@code mocha.agent.model}·{@code max-tool-calls}·{@code transcript-max-turns}·
 * {@code transcript-ttl}·{@code mocha.vision.model}·{@code mocha.alias.model})이 전부 미설정이어도
 * 코드 default로 빈이 뜨는지 검증한다. default가 없는 구 필수 키({@code mocha.llm.model})만 채운다
 * (TΔ8b 폐기 전까지 존치).
 */
class ConfigDefaultsTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            // Boot 런타임과 동일한 문자열→Duration("1h") 변환을 켠다 — 실제 앱 컨텍스트와 조건을 맞춘다.
            .withInitializer(context -> context.getBeanFactory()
                    .setConversionService(ApplicationConversionService.getSharedInstance()))
            .withUserConfiguration(LlmConfig.class, PipelineConfig.class, AgentConfig.class)
            .withPropertyValues("mocha.llm.model=test-model");

    @Test
    @DisplayName("ADR-50: mocha.agent.* 미설정 시 루프·트랜스크립트 빈이 코드 default로 뜬다")
    void agentBeansStartWithDefaults() {
        runner.run(context -> {
            AgentClient agentClient = context.getBean(AgentClient.class);
            assertThat(agentClient).isInstanceOf(OpenAiAgentClient.class);
            assertThat(ReflectionTestUtils.getField(agentClient, "model")).isEqualTo("gpt-5.4-mini");
            assertThat(ReflectionTestUtils.getField(agentClient, "maxToolCalls")).isEqualTo(8);

            ConversationTranscript transcript = context.getBean(ConversationTranscript.class);
            assertThat(ReflectionTestUtils.getField(transcript, "maxTurns")).isEqualTo(20);
            assertThat(ReflectionTestUtils.getField(transcript, "ttl")).isEqualTo(Duration.ofHours(1));
        });
    }

    @Test
    @DisplayName("ADR-50: mocha.vision.model 미설정 시 vision 경계가 전용 경량 default로 뜬다")
    void visionClientDefaultsToLightweightModel() {
        runner.run(context -> {
            VisionClient visionClient = context.getBean(VisionClient.class);
            assertThat(ReflectionTestUtils.getField(visionClient, "model")).isEqualTo("gpt-5.4-mini");
        });
    }

    @Test
    @DisplayName("ADR-50: mocha.alias.model 미설정 시 AliasGenerator가 최경량 default 전용 클라이언트로 배선된다")
    void aliasGeneratorWiredToDedicatedDefaultClient() {
        runner.run(context -> {
            AliasGenerator aliasGenerator = context.getBean(AliasGenerator.class);
            Object aliasClient = ReflectionTestUtils.getField(aliasGenerator, "llmClient");
            assertThat(ReflectionTestUtils.getField(aliasClient, "model")).isEqualTo("gpt-5.4-nano");
        });
    }

    @Test
    @DisplayName("재배선 후에도 기존 단발 콜 주입은 @Primary(mocha.llm.model) 클라이언트를 유지한다")
    void primaryLlmClientKeepsLegacyModel() {
        runner.run(context -> {
            LlmClient primary = context.getBean(LlmClient.class);
            assertThat(ReflectionTestUtils.getField(primary, "model")).isEqualTo("test-model");
        });
    }
}
