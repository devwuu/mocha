package com.devwuu.mocha;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.test.context.TestPropertySource;

/**
 * TΔ1 (changes/0009) — 파일 로깅 설정 검증.
 * NFR-7/ADR-21: 일 단위 롤링·30일 보존·총 300MB 상한, logs/ 아래 파일 기록.
 */
@SpringBootTest
// 실 Slack 소켓에 연결하지 않도록 토큰을 빈 값으로 덮는다 (MochaApplicationTests와 동일 이유).
@TestPropertySource(properties = {"mocha.slack.bot-token=", "mocha.slack.app-token="})
class FileLoggingConfigTest {

	@Autowired
	Environment env;

	@Test
	@DisplayName("AC-23: 로깅 설정에 일 롤링·30일 보존·300MB 상한이 명시돼 있다")
	void rollingPolicyPropertiesArePresent() {
		// 파일 대상이 logs/ 아래인가 (개인 데이터 격리 대상 위치)
		assertThat(env.getProperty("logging.file.name")).contains("logs/");
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
