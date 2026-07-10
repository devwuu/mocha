package com.devwuu.mocha;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
// 컨텍스트 로드 검증만 한다 — 실 Slack 소켓에 연결하지 않도록 토큰을 빈 값으로 덮는다.
// (.env.local의 실토큰이 주입되면 SmartLifecycle 시작 시 Socket Mode 연결을 시도하므로 차단.)
@TestPropertySource(properties = {"mocha.slack.bot-token=", "mocha.slack.app-token="})
class MochaApplicationTests {

	@Test
	void contextLoads() {
	}

}
