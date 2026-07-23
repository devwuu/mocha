package com.devwuu.mocha.config;

import com.devwuu.mocha.agent.AgentClient;
import com.devwuu.mocha.agent.OpenAiAgentClient;
import com.devwuu.mocha.agent.conversation.ConversationTranscript;
import com.devwuu.mocha.llm.AliasGenerator;
import com.devwuu.mocha.llm.OpenAiAliasGenerator;
import com.devwuu.mocha.llm.OpenAiUtteranceSegmenter;
import com.devwuu.mocha.llm.UtteranceSegmenter;
import com.devwuu.mocha.llm.VisionClient;
import com.devwuu.mocha.render.CardImageRenderer;
import com.devwuu.mocha.render.NoteRenderer;
import com.devwuu.mocha.render.Theme;
import com.devwuu.mocha.repository.NoteRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TΔ4·TΔ8b(changes/0018) — 설정 키 재편의 default 기동 단언 (ref: plan.md#ADR-50 POLICY).
 * <p>새 키 6종({@code mocha.agent.model}·{@code max-tool-calls}·{@code transcript-max-turns}·
 * {@code transcript-ttl}·{@code mocha.vision.model}·{@code mocha.alias.model})이 전부 미설정이어도
 * 코드 default로 빈이 뜨는지 검증한다. 구 키(mocha.llm.*·mocha.search.*)는 TΔ8b에서 폐기돼
 * 어떤 프로퍼티도 채우지 않는다.
 */
class ConfigDefaultsTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            // Boot 런타임과 동일한 문자열→Duration("1h") 변환을 켠다 — 실제 앱 컨텍스트와 조건을 맞춘다.
            .withInitializer(context -> context.getBeanFactory()
                    .setConversionService(ApplicationConversionService.getSharedInstance()))
            // CommonConfig: 트랜스크립트 빈이 공통 Clock 빈을 주입받는다(ADR-63) — 실제 앱 컨텍스트와 동일 배선.
            .withUserConfiguration(CommonConfig.class, LlmConfig.class, AgentConfig.class);

    @Test
    @DisplayName("ADR-50: mocha.agent.* 미설정 시 루프·트랜스크립트 빈이 코드 default로 뜬다")
    void agentBeansStartWithDefaults() {
        runner.run(context -> {
            AgentClient agentClient = context.getBean(AgentClient.class);
            assertThat(agentClient).isInstanceOf(OpenAiAgentClient.class);
            // changes/0021 TΔ3b: 경량(gpt-5.4-mini)의 AC-77 반복 위반 관측으로 gpt-5.4 교체(사용자 확정).
            assertThat(ReflectionTestUtils.getField(agentClient, "model")).isEqualTo("gpt-5.4");
            assertThat(ReflectionTestUtils.getField(agentClient, "maxToolCalls")).isEqualTo(8);

            ConversationTranscript transcript = context.getBean(ConversationTranscript.class);
            assertThat(ReflectionTestUtils.getField(transcript, "maxTurns")).isEqualTo(20);
            assertThat(ReflectionTestUtils.getField(transcript, "ttl")).isEqualTo(Duration.ofHours(1));
        });
    }

    @Test
    @DisplayName("ADR-63/V-3: 공통 Clock 빈은 Asia/Seoul 존이다 — 산재 생성 제거 후 유일한 존 소유 지점 가드")
    void commonClockBeanIsSeoulZone() {
        // 존 불변식은 이제 CommonConfig 빈 정의 1곳만 강제한다(자체 생성 8곳 제거, changes/0024 TΔ1a1) —
        // 이 빈이 다른 존으로 바뀌면 V-3 날짜 스탬핑(pending TTL·노트 date)이 조용히 어긋나므로 여기서 박는다.
        runner.run(context ->
                assertThat(context.getBean(Clock.class).getZone()).isEqualTo(ZoneId.of("Asia/Seoul")));
    }

    @Test
    @DisplayName("ADR-61(changes/0023 TΔ3a): mocha.agent.segmenter-model 미설정 시 세그먼터가 전용 경량 default로 뜬다")
    void segmenterDefaultsToLightweightModel() {
        runner.run(context -> {
            UtteranceSegmenter segmenter = context.getBean(UtteranceSegmenter.class);
            assertThat(segmenter).isInstanceOf(OpenAiUtteranceSegmenter.class);
            // 분리만 하는 좁은 작업 — 루프 모델(gpt-5.4)에 편승하지 않는 경량 전용 키(plan §5).
            assertThat(ReflectionTestUtils.getField(segmenter, "model")).isEqualTo("gpt-5.4-mini");
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
    @DisplayName("ADR-50/ADR-54(changes/0021 TΔ5a): mocha.artifact.theme 미설정 시 기본 테마 type-a(세리프)로 렌더러가 뜬다")
    void rendererDefaultsToTypeATheme() {
        new ApplicationContextRunner()
                .withUserConfiguration(RenderConfig.class)
                .withPropertyValues("mocha.artifact.dir=build/test-artifact")
                .withBean(NoteRepository.class, ConfigDefaultsTest::noteRepositoryStub)
                .withBean(CardImageRenderer.class, () -> (html, baseDir, out) -> {
                })
                .run(context -> {
                    NoteRenderer renderer = context.getBean(NoteRenderer.class);
                    assertThat(ReflectionTestUtils.getField(renderer, "theme")).isEqualTo(Theme.TYPE_A);
                });
    }

    // 렌더러 빈 배선에만 필요한 무동작 스텁 — 이 테스트에서 어떤 저장소 메서드도 불리지 않는다.
    private static NoteRepository noteRepositoryStub() {
        return (NoteRepository) Proxy.newProxyInstance(
                NoteRepository.class.getClassLoader(), new Class<?>[]{NoteRepository.class},
                (proxy, method, args) -> {
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
