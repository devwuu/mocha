package com.devwuu.mocha;

import com.devwuu.mocha.support.PostgresIntegrationTest;
import org.junit.jupiter.api.Test;

// 컨텍스트 로드 검증만 한다. DataSource가 필수가 된 뒤로(changes/0028 TΔ1) 실 Postgres가 있어야 뜨므로
// 통합 테스트 기반을 상속한다(TΔ10a) — 접속 대상이 개발 데이터(public)가 아닌 test 스키마로 갈라진다.
// Slack 토큰 오버라이드도 기반이 소유한다. 실행 전 `docker compose up -d`는 여전히 선행이다.
class MochaApplicationTests extends PostgresIntegrationTest {

	@Test
	void contextLoads() {
	}

}
