package com.devwuu.mocha;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.util.Arrays;

@SpringBootApplication
public class MochaApplication {

	// CLI --rerender: 상주(Slack 소켓) 없이 전체 리렌더만 하고 종료(RerenderRunner, tasks T5-1).
	private static final String RERENDER_OPTION = "--rerender";

	public static void main(String[] args) {
		SpringApplication app = new SpringApplication(MochaApplication.class);
		// --rerender면 'rerender' 프로파일을 켠다 → SlackGateway(@Profile("!rerender")) 배제, RerenderRunner 활성화.
		if (Arrays.asList(args).contains(RERENDER_OPTION)) {
			app.setAdditionalProfiles("rerender");
		}
		app.run(args);
	}

}
