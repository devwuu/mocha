package com.devwuu.mocha.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.Environment;

/**
 * TΔ5 (changes/0032-dev-prod-data-split) — dev/prod 프로파일의 프로퍼티 해석 단언 (AC-Δ3·AC-Δ5, ADR-89).
 *
 * <p>이 앱은 로컬 단일 프로세스로 상주하므로(NFR-1) 개발 실행과 실사용 실행이 같은 머신·같은 Postgres·
 * 같은 파일 루트를 딛는다. 둘을 가르는 유일한 장치가 프로파일이고, 그 배선이 어긋나면
 * <b>개발 실행이 실기록을 오염시키거나 파생물을 지운다</b>(고아 정리 — ADR-81). 그 배선을 여기서 박는다.
 *
 * <p><b>DB에 붙지 않는다.</b> {@link ConfigDataApplicationContextInitializer}로 yaml만 싣고
 * {@link Environment} 값을 읽을 뿐이라 실 스키마를 건드릴 위험이 없다 — 이 테스트가 지키려는 대상(실기록)을
 * 이 테스트가 만지는 자기모순을 피한다. 같은 이유로 {@code @SpringBootTest}를 쓰지 않는다.
 *
 * <p><b>세 번째 단언(존재하지 않는 프로파일)이 이 클래스의 핵심이다.</b> 나머지 둘은 설정이 의도대로 읽히는지
 * 보는 것이고, 그것은 TΔ4 기동 실측이 이미 한 번 확인했다. 하지만 «오타가 어느 방향으로 실패하는가»는
 * 사람이 실수해야만 드러나는 성질이라 실측으로 잡히지 않는다 — 여기서만 지킬 수 있다.
 */
class ProfileDataSplitTest {

    /** 프로파일별 프로퍼티 해석용 러너. 빈을 등록하지 않으므로 컨텍스트에 뜨는 것은 Environment뿐이다. */
    private ApplicationContextRunner runner(String... activeProfiles) {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer());
        if (activeProfiles.length == 0) {
            return runner;
        }
        return runner.withPropertyValues("spring.profiles.active=" + String.join(",", activeProfiles));
    }

    @Test
    @DisplayName("AC-Δ1(ADR-89): 프로파일 미지정이면 개발 값으로 읽힌다 — dev 스키마 · data-dev · artifact-dev · logs-dev")
    void defaultsToDevelopmentValues() {
        // base(application.yaml)가 dev 값을 든다는 것이 ADR-89 ②의 배치다. 무옵션 `./gradlew bootRun`이
        // 개발 환경으로 뜨는 성질이 여기 걸려 있다.
        runner().run(context -> {
            Environment env = context.getEnvironment();
            assertThat(env.getProperty("spring.jpa.properties.hibernate.default_schema")).isEqualTo("dev");
            assertThat(env.getProperty("spring.flyway.schemas")).isEqualTo("dev");
            assertThat(env.getProperty("spring.flyway.default-schema")).isEqualTo("dev");
            assertThat(env.getProperty("mocha.data.dir")).isEqualTo("./data-dev");
            assertThat(env.getProperty("mocha.artifact.dir")).isEqualTo("./artifact-dev");
            assertThat(env.getProperty("logging.file.name")).startsWith("logs-dev/");
            assertThat(env.getProperty("logging.logback.rollingpolicy.file-name-pattern")).startsWith("logs-dev/");
        });
    }

    @Test
    @DisplayName("AC-Δ2(ADR-89): prod 프로파일이면 실사용 값으로 읽힌다 — public 스키마 · data · artifact · logs")
    void prodProfileResolvesProductionValues() {
        // application-prod.yaml이 실사용 값의 «유일한» 소유자다. 여기 빠진 키가 있으면 그 키만 개발 값으로
        // 남아 반쪽 분리가 되므로, 갈리는 값 넷을 모두 단언한다.
        runner("prod").run(context -> {
            Environment env = context.getEnvironment();
            assertThat(env.getProperty("spring.jpa.properties.hibernate.default_schema")).isEqualTo("public");
            assertThat(env.getProperty("spring.flyway.schemas")).isEqualTo("public");
            assertThat(env.getProperty("spring.flyway.default-schema")).isEqualTo("public");
            assertThat(env.getProperty("mocha.data.dir")).isEqualTo("./data");
            assertThat(env.getProperty("mocha.artifact.dir")).isEqualTo("./artifact");
            assertThat(env.getProperty("logging.file.name")).startsWith("logs/");
            assertThat(env.getProperty("logging.logback.rollingpolicy.file-name-pattern")).startsWith("logs/");
        });
    }

    @Test
    @DisplayName("AC-Δ3(ADR-89): 존재하지 않는 프로파일명은 개발 값으로 떨어진다 — 오타가 실기록 방향으로 실패하지 않는다")
    void unknownProfileFallsBackToDevelopmentNotProduction() {
        // ADR-89 ②의 기각 대안(`spring.profiles.default: dev`)이 무너지는 지점이 정확히 여기다: default는
        // active가 «비었을 때만» 붙으므로, 오타로 active가 채워지면 default가 안 붙고 base만 남는다.
        // base가 prod 값이었다면 이 케이스가 실기록에 붙었을 것이다. base=dev이므로 그 경로가 없다.
        runner("prd").run(context -> {
            Environment env = context.getEnvironment();
            assertThat(env.getProperty("spring.jpa.properties.hibernate.default_schema"))
                    .as("오타 프로파일이 public(실기록)에 붙으면 안 된다")
                    .isEqualTo("dev");
            assertThat(env.getProperty("mocha.data.dir")).isEqualTo("./data-dev");
            assertThat(env.getProperty("mocha.artifact.dir")).isEqualTo("./artifact-dev");
            assertThat(env.getProperty("logging.file.name")).startsWith("logs-dev/");
        });
    }

    @Test
    @DisplayName("AC-Δ5(ADR-89): 갈리는 값 넷이 «함께» 갈린다 — 한 프로파일 안에서 개발/실사용 값이 섞이지 않는다")
    void thePairsNeverMixWithinAProfile() {
        // 이 델타가 막으려는 사고는 «하나만 갈림»이다: DB만 가르면 사진·카드가 섞이고, artifact만 가르면
        // 노트가 섞인다. 새 키를 한쪽 yaml에만 더하는 실수를 잡으려고 «루트 접미사의 일관성»을 본다.
        runner().run(context -> {
            Environment env = context.getEnvironment();
            assertThat(env.getProperty("spring.jpa.properties.hibernate.default_schema")).isEqualTo("dev");
            assertThat(env.getProperty("mocha.data.dir")).endsWith("-dev");
            assertThat(env.getProperty("mocha.artifact.dir")).endsWith("-dev");
            assertThat(env.getProperty("logging.file.name")).startsWith("logs-dev/");
        });
        runner("prod").run(context -> {
            Environment env = context.getEnvironment();
            assertThat(env.getProperty("spring.jpa.properties.hibernate.default_schema")).isEqualTo("public");
            assertThat(env.getProperty("mocha.data.dir")).doesNotEndWith("-dev");
            assertThat(env.getProperty("mocha.artifact.dir")).doesNotEndWith("-dev");
            assertThat(env.getProperty("logging.file.name")).doesNotStartWith("logs-dev/");
        });
    }

    @Test
    @DisplayName("AC-Δ5: 접속 3종(MOCHA_DB_*)은 프로파일과 직교한다 — prod가 접속 대상을 바꾸지 않는다")
    void datasourceTargetIsOrthogonalToProfile() {
        // ADR-89는 «그 안의 어느 스키마»만 가른다. 접속 축(ADR-73)까지 건드리면 클라우드 이전(델타 B)에서
        // 주입만으로 대상을 바꾸는 성질이 깨진다 — application-prod.yaml이 datasource를 덮지 않는다는 단언.
        runner().run(dev -> runner("prod").run(prod -> {
            assertThat(prod.getEnvironment().getProperty("spring.datasource.url"))
                    .isEqualTo(dev.getEnvironment().getProperty("spring.datasource.url"));
            assertThat(prod.getEnvironment().getProperty("spring.datasource.username"))
                    .isEqualTo(dev.getEnvironment().getProperty("spring.datasource.username"));
        }));
    }
}
