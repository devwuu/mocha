package com.devwuu.mocha;

import static org.assertj.core.api.Assertions.assertThat;

import com.devwuu.mocha.service.NoteService;
import com.devwuu.mocha.service.NoteTxService;
import com.devwuu.mocha.support.PostgresIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.annotation.Autowired;

// 컨텍스트 로드 검증만 한다. DataSource가 필수가 된 뒤로(changes/0028 TΔ1) 실 Postgres가 있어야 뜨므로
// 통합 테스트 기반을 상속한다(TΔ10a) — 접속 대상이 개발 데이터(public)가 아닌 test 스키마로 갈라진다.
// Slack 토큰 오버라이드도 기반이 소유한다. 실행 전 `docker compose up -d`는 여전히 선행이다.
class MochaApplicationTests extends PostgresIntegrationTest {

	@Autowired
	NoteService noteService;

	@Autowired
	NoteTxService noteTxService;

	@Test
	void contextLoads() {
	}

	@Test
	@DisplayName("0029 TΔ4b·TΔ4c: 트랜잭션 계층이 CGLIB 프록시로 서고 service 경유 실 조회가 통한다")
	void transactionalLayerIsProxiedAndReachable() {
		// 포트 제거(ADR-76)가 조용히 깨뜨릴 수 있는 자리다: 프록시 수단이 JDK 동적 프록시에서 CGLIB
		// 서브클래싱으로 바뀐다. 프록시가 안 서면 @Transactional이 무력화되는데, 단위 테스트는 협력자를
		// `new`로 세우고 통합 테스트는 @Transactional을 스스로 걸어서 — 어느 쪽도 이것을 보지 못한다.
		// TΔ4c에서 프록시 대상이 NoteTxService로 옮겨갔고, 위층 NoteService는 프록시가 아니어야 한다
		// (트랜잭션 경계를 늘리지 않는다는 백엔드 CLAUDE.md §3의 관측 지점).
		assertThat(AopUtils.isCglibProxy(noteTxService)).isTrue();
		assertThat(AopUtils.isAopProxy(noteService)).isFalse();
		// 조회가 실제로 트랜잭션 안에서 도는지까지 본다(readOnly 경계 — 없으면 EntityManager가 못 열린다).
		// 테스트 스키마는 매 기동 clean→migrate라 비어 있고, 여기서 보는 것은 건수가 아니라 도달이다.
		assertThat(noteService.findAll()).isEmpty();
	}

}
