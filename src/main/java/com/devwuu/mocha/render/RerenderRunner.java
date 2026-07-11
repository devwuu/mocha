package com.devwuu.mocha.render;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * CLI 진입점 — {@code --rerender}로 실행하면 JSON에서 전체 리렌더만 하고 종료한다 (ref: tasks T5-1, plan.md §7).
 * <p>렌더 실패로 데이터가 상하는 일은 없으므로(ADR-1), artifact/를 지운 뒤나 스키마/디자인을 바꾼 뒤 수동 복구용으로 쓴다.
 * <p>{@code rerender} 프로파일에서만 활성화된다({@code MochaApplication}이 {@code --rerender}를 보고 프로파일을 켠다).
 * 이 프로파일에서는 {@code SlackGateway}가 배제되어({@code @Profile("!rerender")}) 소켓을 열지 않는다 —
 * 상주 인스턴스와 별개로 안전하게 리렌더만 돌릴 수 있다.
 */
@Component
@Profile("rerender")
public class RerenderRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RerenderRunner.class);

    private final NoteRenderer noteRenderer;
    private final ConfigurableApplicationContext context;

    public RerenderRunner(NoteRenderer noteRenderer, ConfigurableApplicationContext context) {
        this.noteRenderer = noteRenderer;
        this.context = context;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("--rerender: 전체 리렌더 시작.");
        noteRenderer.renderAll();
        log.info("--rerender: 완료, 종료합니다.");
        // 상주하지 않고 종료한다 — 종료 코드를 컨텍스트 정리 후 전파한다.
        System.exit(SpringApplication.exit(context, () -> 0));
    }
}
