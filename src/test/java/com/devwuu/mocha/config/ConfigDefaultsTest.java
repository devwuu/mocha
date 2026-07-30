package com.devwuu.mocha.config;

import com.devwuu.mocha.agent.ChatClient;
import com.devwuu.mocha.agent.OpenAiChatClient;
import com.devwuu.mocha.agent.conversation.FoldingChatMemory;
import com.devwuu.mocha.agent.prompt.TurnPromptAssembler;
import com.devwuu.mocha.agent.tool.ToolCallbackProvider;
import com.devwuu.mocha.agent.tool.validation.EditProposalValidator;
import com.devwuu.mocha.agent.tool.validation.RecordProposalValidator;
import com.devwuu.mocha.json.MochaObjectMapper;
import com.devwuu.mocha.llm.AliasGenerator;
import com.devwuu.mocha.llm.OpenAiAliasGenerator;
import com.devwuu.mocha.llm.OpenAiUtteranceSegmenter;
import com.devwuu.mocha.llm.PhotoInfoExtractor;
import com.devwuu.mocha.llm.UtteranceSegmenter;
import com.devwuu.mocha.llm.VisionClient;
import com.devwuu.mocha.render.CardImageRenderer;
import com.devwuu.mocha.render.NoteRenderer;
import com.devwuu.mocha.render.Theme;
import com.devwuu.mocha.repository.JsonFileNoteRepository;
import com.devwuu.mocha.repository.JsonFilePendingStore;
import com.devwuu.mocha.repository.JsonFilePhotoBufferStore;
import com.devwuu.mocha.repository.LocalPhotoStore;
import com.devwuu.mocha.repository.NoteRepository;
import com.devwuu.mocha.repository.PendingStore;
import com.devwuu.mocha.repository.PhotoBufferStore;
import com.devwuu.mocha.repository.PhotoStore;
import com.devwuu.mocha.slack.SlackCommitHandler;
import com.devwuu.mocha.slack.inbound.PhotoDownloader;
import com.devwuu.mocha.slack.inbound.SlackPhotoIntake;
import com.devwuu.mocha.slack.outbound.PreviewBlocks;
import com.devwuu.mocha.slack.outbound.PreviewMessenger;
import com.devwuu.mocha.slack.outbound.SlackResponder;
import com.slack.api.methods.MethodsClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.convert.ApplicationConversionService;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TΔ4·TΔ8b(changes/0018) — 설정 키 재편의 default 기동 단언 (ref: plan.md#ADR-50 POLICY).
 * <p>새 키 6종({@code mocha.agent.model}·{@code max-tool-calls}·{@code transcript-max-turns}·
 * {@code transcript-ttl}·{@code mocha.vision.model}·{@code mocha.alias.model})이 전부 미설정이어도
 * 코드 default로 빈이 뜨는지 검증한다. 구 키(mocha.llm.*·mocha.search.*)는 TΔ8b에서 폐기돼
 * 어떤 프로퍼티도 채우지 않는다.
 * <p>TΔ4a(changes/0025, R-1) — 배선 커버 확대: {@code RepositoryConfig}·{@code RouterConfig}도
 * 스텁 러너로 기동 단언한다(AC-Δ4). 0024가 라우터 생성자 안 조립을 config로 이관하면서(ADR-63)
 * 배선 오류가 앱 기동 시점에만 드러나게 됐고, 그 표적 가드가 없었다.
 */
class ConfigDefaultsTest {

    // 격리·Duration 변환은 isolatedRunner()가 소유 — 러너가 늘어도 토대가 갈라지지 않게 한다(TΔ4a).
    private final ApplicationContextRunner runner = isolatedRunner()
            // Jackson 자동구성을 실제 앱과 동일하게 켠다(starter-json) — Boot의 @Primary JsonMapper와의
            // 공존 조건까지 가드하기 위함(TΔ1a2 리뷰 발견: ObjectMapper 상위 타입 빈은 Boot가 안 물러난다).
            .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
            // CommonConfig: 트랜스크립트 빈이 공통 Clock 빈을 주입받는다(ADR-63) — 실제 앱 컨텍스트와 동일 배선.
            .withUserConfiguration(CommonConfig.class, LlmConfig.class, AgentConfig.class);

    @Test
    @DisplayName("ADR-50: mocha.agent.* 미설정 시 루프·트랜스크립트 빈이 코드 default로 뜬다")
    void agentBeansStartWithDefaults() {
        runner.run(context -> {
            ChatClient chatClient = context.getBean(ChatClient.class);
            assertThat(chatClient).isInstanceOf(OpenAiChatClient.class);
            // changes/0021 TΔ3b: 경량(gpt-5.4-mini)의 AC-77 반복 위반 관측으로 gpt-5.4 교체(사용자 확정).
            assertThat(ReflectionTestUtils.getField(chatClient, "model")).isEqualTo("gpt-5.4");
            assertThat(ReflectionTestUtils.getField(chatClient, "maxToolCalls")).isEqualTo(8);
            // changes/0027 TΔ6(ADR-70 ②): 응답당 출력 상한도 미설정 프로파일에서 코드 default로 뜬다 —
            // 값은 plan §5 문서값(4000, findings-TΔ4 §4.4)과 일치해야 한다.
            assertThat(ReflectionTestUtils.getField(chatClient, "maxOutputTokens")).isEqualTo(4000);

            FoldingChatMemory transcript = context.getBean(FoldingChatMemory.class);
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
    @DisplayName("ADR-63: 공통 ObjectMapper 빈은 도메인 JSON 규칙(snake_case·오프셋 보존)을 지닌다 — 유일한 생성 지점 가드")
    void commonObjectMapperBeanCarriesMochaRules() {
        // 규칙 불변식은 이제 CommonConfig 빈 정의 1곳만 강제한다(자체 생성 9곳 제거, changes/0024 TΔ1a2) —
        // 이 빈이 기본 매퍼로 바뀌면 노트 JSON 필드명(data-model — coffee_name 등)·오프셋 왕복 동일성이
        // 조용히 어긋나므로 여기서 박는다. 러너에 Jackson 자동구성이 켜져 있어(위 주석) Boot 기본 매퍼가
        // 이 빈을 가리는 회귀(@Primary 승자 역전)도 함께 잡는다.
        runner.run(context -> {
            ObjectMapper mapper = context.getBean(ObjectMapper.class);
            assertThat(mapper.writeValueAsString(new Probe("예가체프", null)))
                    .isEqualTo("{\"coffee_name\":\"예가체프\",\"tasted_at\":null}");
            // ADJUST_DATES_TO_CONTEXT_TIME_ZONE=false — 원 오프셋(+09:00) 보존, UTC 정규화 금지.
            assertThat(mapper.readValue("{\"tasted_at\":\"2026-07-23T09:30:00+09:00\"}", Probe.class)
                    .tastedAt().getOffset()).isEqualTo(ZoneOffset.ofHours(9));
        });
    }

    // snake_case·오프셋 규칙 관측용 최소 표본 — 도메인 record를 끌어오지 않고 규칙만 본다.
    private record Probe(String coffeeName, OffsetDateTime tastedAt) {
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
        renderRunner()
                .withPropertyValues("mocha.artifact.dir=build/test-artifact")
                .run(context -> {
                    NoteRenderer renderer = context.getBean(NoteRenderer.class);
                    assertThat(ReflectionTestUtils.getField(renderer, "theme")).isEqualTo(Theme.TYPE_A);
                });
    }

    @Test
    @DisplayName("ADR-50(changes/0025 TΔ3d): mocha.artifact.dir 미설정 시 렌더러가 코드 default ./artifact로 뜬다")
    void rendererDefaultsToArtifactDir() {
        // RB-B6: RouterConfig만 `:./artifact`를 갖고 RenderConfig는 무기본값이라 키 미설정이면 기동 자체가
        // 막혔다(= RouterConfig의 default는 도달 불가한 죽은 선언). 두 선언을 일치시킨 뒤의 기동을 여기서 박아
        // 한쪽만 바뀌는 드리프트를 잡는다 — 렌더러 생성자는 파일 I/O를 하지 않아 실제 디렉터리는 생기지 않는다.
        renderRunner().run(context -> {
            NoteRenderer renderer = context.getBean(NoteRenderer.class);
            assertThat(ReflectionTestUtils.getField(renderer, "artifactDir")).isEqualTo(Path.of("./artifact"));
        });
    }

    @Test
    @DisplayName("CR25-7: default 기동 단언은 주변 환경(시스템 프로퍼티·환경변수)에 오염되지 않는다")
    void defaultAssertionsAreIsolatedFromAmbientEnvironment() {
        // 이 클래스의 모든 단언이 "키 미설정 → 코드 default"를 주장하므로, 환경 소스가 남아 있으면 주장 자체가
        // 거짓이 된다(개발자가 MOCHA_ARTIFACT_DIR을 export하면 빨개졌다 — CR25-7 실측). 격리를 여기서 박아
        // 러너가 확장될 때(TΔ4a) 새 키가 같은 함정에 빠지는 것을 막는다. 프로세스 환경변수는 테스트에서
        // 심을 수 없으니, 같은 격리로 함께 떨어져 나가는 시스템 프로퍼티로 관측한다.
        renderRunner()
                .withSystemProperties("mocha.artifact.dir=/tmp/ambient-artifact")
                .run(context -> {
                    NoteRenderer renderer = context.getBean(NoteRenderer.class);
                    assertThat(ReflectionTestUtils.getField(renderer, "artifactDir"))
                            .isEqualTo(Path.of("./artifact"));
                });
    }

    @Test
    @DisplayName("AC-Δ4(changes/0025 TΔ4a): RepositoryConfig가 mocha.data.dir·mocha.pending.ttl로 저장소 4종을 띄운다")
    void repositoryBeansWireFromDataDirAndTtl() {
        // R-1: 저장소 배선은 `mocha.data.dir` 하나에서 경로가 파생되고(plan §5·CLAUDE.md §3) TTL은 문자열
        // 변환을 거친다 — 어느 쪽이 어긋나도 앱 기동 시점에야 드러났다. 생성자는 파일 I/O를 하지 않아
        // 실제 디렉터리는 생기지 않는다.
        repositoryRunner()
                .withPropertyValues("mocha.data.dir=build/test-data", "mocha.pending.ttl=30m")
                .run(context -> {
                    assertThat(context.getBean(NoteRepository.class)).isInstanceOf(JsonFileNoteRepository.class);
                    assertThat(context.getBean(PhotoStore.class)).isInstanceOf(LocalPhotoStore.class);
                    assertThat(context.getBean(PhotoBufferStore.class)).isInstanceOf(JsonFilePhotoBufferStore.class);

                    PendingStore pendingStore = context.getBean(PendingStore.class);
                    assertThat(pendingStore).isInstanceOf(JsonFilePendingStore.class);
                    // 경로는 설정 키에서만 파생된다(CLAUDE.md §3) — 하드코딩 회귀를 여기서 잡는다.
                    assertThat(ReflectionTestUtils.getField(pendingStore, "pendingFile"))
                            .isEqualTo(Path.of("build/test-data/pending.json"));
                    assertThat(ReflectionTestUtils.getField(pendingStore, "ttl")).isEqualTo(Duration.ofMinutes(30));
                });
    }

    @Test
    @DisplayName("ADR-50(changes/0025 CR25-8): mocha.data.dir·mocha.pending.ttl 미설정 시 저장소가 코드 default로 뜬다")
    void repositoryBeansStartWithDefaults() {
        // CR25-8: RB-B6이 mocha.artifact.dir만 고쳐 이 두 키는 무기본값으로 남아 있었다 — application.yaml을
        // 싣지 않는 컨텍스트에서 placeholder 미해결로 기동이 막힌다(TΔ3d가 자매 키에서 없앤 바로 그 실패).
        // 값은 plan §5 문서값(./data · 24시간)과 일치해야 한다.
        repositoryRunner().run(context -> {
            assertThat(ReflectionTestUtils.getField(context.getBean(NoteRepository.class), "notesDir"))
                    .isEqualTo(Path.of("./data/notes"));
            assertThat(ReflectionTestUtils.getField(context.getBean(PendingStore.class), "ttl"))
                    .isEqualTo(Duration.ofHours(24));
        });
    }

    @Test
    @DisplayName("AC-Δ4(changes/0025 TΔ4a): RouterConfig가 협력자 스텁만으로 턴 배선 6종을 조립한다")
    void routerBeansAssembleFromCollaborators() {
        // R-1: 0024 TΔ1b가 라우터 생성자 안 조립을 이 config로 이관했다(ADR-63) — 주입 지점이 늘어난 만큼
        // 배선 누락이 기동 실패로만 드러난다. 협력자는 전부 스텁이라 이 테스트는 "조립되는가"만 본다.
        routerRunner().run(context -> {
            assertThat(context.getBean(RecordProposalValidator.class)).isNotNull();
            assertThat(context.getBean(EditProposalValidator.class)).isNotNull();
            assertThat(context.getBean(TurnPromptAssembler.class)).isNotNull();
            assertThat(context.getBean(ToolCallbackProvider.class)).isNotNull();
            assertThat(context.getBean(SlackCommitHandler.class)).isNotNull();

            SlackPhotoIntake photoIntake = context.getBean(SlackPhotoIntake.class);
            // 버퍼 창(FR-10·ADR-31)은 이 config만 default를 선언한다 — 미설정 기동을 함께 박는다.
            assertThat(ReflectionTestUtils.getField(photoIntake, "bufferWindow")).isEqualTo(Duration.ofMinutes(10));
        });
    }

    // 렌더 배선만 띄우는 최소 러너 — 협력자는 스텁, 프로퍼티는 각 테스트가 필요한 것만 얹는다.
    private static ApplicationContextRunner renderRunner() {
        return isolatedRunner()
                .withUserConfiguration(RenderConfig.class)
                .withBean(NoteRepository.class, () -> stub(NoteRepository.class))
                .withBean(CardImageRenderer.class, () -> (html, baseDir, out) -> {
                });
    }

    // 저장소 배선 러너 — 전역 빈(Clock·ObjectMapper)은 실제 CommonConfig에서 받아 앱과 조건을 맞춘다.
    private static ApplicationContextRunner repositoryRunner() {
        return isolatedRunner().withUserConfiguration(CommonConfig.class, RepositoryConfig.class);
    }

    // 턴 협력자 배선 러너 — 의존 빈 13종을 스텁으로 채운다(인터페이스는 Proxy, 구체 클래스는 무해한 실인스턴스).
    private static ApplicationContextRunner routerRunner() {
        return isolatedRunner()
                .withUserConfiguration(RouterConfig.class)
                .withBean(NoteRepository.class, () -> stub(NoteRepository.class))
                .withBean(NoteRenderer.class, () -> stub(NoteRenderer.class))
                .withBean(SlackResponder.class, () -> stub(SlackResponder.class))
                .withBean(PendingStore.class, () -> stub(PendingStore.class))
                .withBean(PhotoDownloader.class, () -> stub(PhotoDownloader.class))
                .withBean(PhotoStore.class, () -> stub(PhotoStore.class))
                .withBean(PhotoBufferStore.class, () -> stub(PhotoBufferStore.class))
                .withBean(AliasGenerator.class, () -> stub(AliasGenerator.class))
                .withBean(PreviewMessenger.class, () -> new PreviewMessenger(new PreviewBlocks(),
                        stub(MethodsClient.class)))
                .withBean(PhotoInfoExtractor.class, () -> new PhotoInfoExtractor(null, 4))
                .withBean(FoldingChatMemory.class, () -> new FoldingChatMemory(20, Duration.ofHours(1),
                        Clock.systemUTC()))
                .withBean(Clock.class, () -> Clock.fixed(Instant.EPOCH, ZoneId.of("Asia/Seoul")))
                .withBean(ObjectMapper.class, MochaObjectMapper::create);
    }

    // 이 클래스의 모든 러너가 공유하는 토대 — 환경 격리(CR25-7)와 Boot 런타임과 동일한 문자열→Duration 변환.
    private static ApplicationContextRunner isolatedRunner() {
        return new ApplicationContextRunner()
                .withInitializer(ConfigDefaultsTest::isolateFromAmbientEnvironment)
                .withInitializer(context -> context.getBeanFactory()
                        .setConversionService(ApplicationConversionService.getSharedInstance()));
    }

    // POLICY: "키 미설정 → 코드 default" 단언은 주변 환경과 격리한다 (ref: plan.md#ADR-50 POLICY).
    // StandardEnvironment가 기본 탑재하는 systemEnvironment·systemProperties를 떼지 않으면 relaxed binding이
    // MOCHA_ARTIFACT_DIR 같은 개발자 환경변수를 잡아, 코드 default가 아니라 그 환경값을 단언하게 된다
    // (CR25-7 실측: MOCHA_ARTIFACT_DIR 지정 시 이 클래스가 빨개졌다). withPropertyValues로 각 테스트가
    // 명시적으로 얹는 소스는 이 제거 대상이 아니므로 영향 없다.
    private static void isolateFromAmbientEnvironment(ConfigurableApplicationContext context) {
        MutablePropertySources sources = context.getEnvironment().getPropertySources();
        sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
        sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
    }

    // 배선 단언에만 필요한 무동작 스텁 — 이 테스트들은 빈 조립만 보고 어떤 협력자 메서드도 부르지 않으므로,
    // 호출되면 즉시 터뜨려 "스텁이 조용히 동작을 대신하는" 착시를 막는다.
    @SuppressWarnings("unchecked")
    private static <T> T stub(Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
                (proxy, method, args) -> {
                    // Object 3종은 정체성으로 답한다 — 단언이 실패하면 AssertJ가 actual의 toString()으로 실패
                    // 메시지를 만드는데, 여기서 터지면 진짜 배선 실패 대신 "UnsupportedOperationException:
                    // toString"이 보고된다(가드가 발동하는 바로 그 순간에 엉뚱한 원인을 가리킴).
                    if (method.getDeclaringClass() == Object.class) {
                        return switch (method.getName()) {
                            case "toString" -> "stub<" + type.getSimpleName() + ">";
                            case "equals" -> proxy == args[0];
                            case "hashCode" -> System.identityHashCode(proxy);
                            default -> throw new UnsupportedOperationException(method.getName());
                        };
                    }
                    throw new UnsupportedOperationException(method.getName());
                });
    }
}
