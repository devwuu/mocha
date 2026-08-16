package com.devwuu.mocha;

import static org.assertj.core.api.Assertions.assertThat;

import com.devwuu.mocha.support.PostgresIntegrationTest;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;

/**
 * TΔ1 (changes/0009) — 파일 로깅 설정 검증.
 * NFR-7/ADR-21: 일 단위 롤링·30일 보존·총 300MB 상한, 격리 대상 루트 아래 파일 기록.
 *
 * <p>TΔ3a (changes/0032) — 로그 루트가 프로파일별로 갈렸다(ADR-89): 실사용 {@code logs/} · 개발
 * {@code logs-dev/}. 이 테스트는 {@code test} 프로파일로 도므로 base(개발) 값을 본다. 단언이 지키는 것은
 * 종전과 같다 — <b>로그가 개인 데이터 격리 대상 루트 아래에 떨어지는가</b>. 루트 이름 하나에 묶여 있던
 * 표현만 두 루트를 받도록 넓혔고, 그 둘로 한정해 격리 밖 경로는 계속 잡는다.
 */
// 컨텍스트가 떠야 검증되는 설정이라 DataSource가 필요하다 — Testcontainers 기반을 상속한다(TΔ10a).
class FileLoggingConfigTest extends PostgresIntegrationTest {

	@Autowired
	Environment env;

	@Test
	@DisplayName("AC-23: 로깅 설정에 일 롤링·30일 보존·300MB 상한이 명시돼 있다")
	void rollingPolicyPropertiesArePresent() {
		// 파일 대상이 개인 데이터 격리 대상 루트 아래인가.
		// changes/0032(ADR-89): 로그 루트가 프로파일별로 갈렸다 — 실사용 logs/ · 개발 logs-dev/.
		// 두 루트 모두 .gitignore 대상이고 원문 로깅이 허용되는 자리다(NFR-7·NFR-8, AC-23 개정).
		// 둘을 «모두 받되 그 둘로 한정»한다 — 격리 대상 밖으로 로그가 나가면 여전히 여기서 잡힌다.
		assertThat(env.getProperty("logging.file.name")).matches("^logs(-dev)?/.+");
		assertThat(env.getProperty("logging.logback.rollingpolicy.file-name-pattern")).matches("^logs(-dev)?/.+");
		// 일 단위 롤링 — 파일명 패턴에 날짜 토큰(%d)이 있어야 한다
		assertThat(env.getProperty("logging.logback.rollingpolicy.file-name-pattern")).contains("%d");
		// 보존 30일 · 총 300MB 상한
		assertThat(env.getProperty("logging.logback.rollingpolicy.max-history")).isEqualTo("30");
		assertThat(env.getProperty("logging.logback.rollingpolicy.total-size-cap")).isEqualTo("300MB");
	}

	@Test
	@DisplayName("AC-23: 구동 후 발생시킨 로그가 당일 로그 파일에 기록된다")
	void logIsWrittenToFile() throws Exception {
		// changes/0009 TΔ1: 기동 후 로그 파일 생성 확인. 유니크 마커로 이번 기록이 파일에 내려앉는지 단언.
		String marker = "MOCHA-FILE-LOGGING-PROBE-changes0009";
		Logger log = LoggerFactory.getLogger(FileLoggingConfigTest.class);
		log.error(marker);

		Path active = Path.of(env.getProperty("logging.file.name"));
		assertThat(Files.exists(active)).as("활성 로그 파일 %s 이 생성됐다", active).isTrue();
		assertThat(Files.readString(active)).contains(marker);
	}
}
