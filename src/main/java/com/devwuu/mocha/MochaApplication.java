package com.devwuu.mocha;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.Arrays;

@SpringBootApplication
public class MochaApplication {

	// CLI --rerender: 상주(웹 서버) 없이 전체 리렌더만 하고 종료(RerenderRunner, tasks T5-1).
	// 구 상주 자원은 Slack 소켓이었고, changes/0029 TΔ16에서 그쪽이 사라졌다.
	private static final String RERENDER_OPTION = "--rerender";

	public static void main(String[] args) {
		SpringApplication app = new SpringApplication(MochaApplication.class);
		// --rerender면 'rerender' 프로파일을 켠다 → 웹 서버·StagingSweeper(@Profile("!rerender")) 배제,
		// RerenderRunner 활성화.
		if (Arrays.asList(args).contains(RERENDER_OPTION)) {
			app.setAdditionalProfiles("rerender");
		}
		app.run(args);
	}

}
