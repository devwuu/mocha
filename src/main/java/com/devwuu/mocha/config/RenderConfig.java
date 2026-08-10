package com.devwuu.mocha.config;

import com.devwuu.mocha.render.CardImageRenderer;
import com.devwuu.mocha.render.NoteRenderer;
import com.devwuu.mocha.render.Theme;
import com.devwuu.mocha.render.ThymeleafNoteRenderer;
import com.devwuu.mocha.service.NoteService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.templatemode.TemplateMode;
import org.thymeleaf.templateresolver.ClassLoaderTemplateResolver;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

/**
 * 렌더 계층 빈 배선 (ref: plan.md#ADR-7, tasks T5-1).
 * <p>Thymeleaf를 <b>오프라인 정적 생성기</b>로 쓴다 — starter-web 없이 클래스패스 템플릿을 직접 처리한다.
 * 템플릿은 {@code templates/<theme>/{review,recipe}.html}에 있고 테마({@code mocha.artifact.theme})가 폴더를 고른다.
 * <p>{@link ThymeleafNoteRenderer}는 프레임워크 의존이 없는 순수 렌더러라 {@code @Component} 스캔 대신 여기서
 * 명시적으로 조립한다(RepositoryConfig·LlmConfig와 동일 방침).
 */
@Configuration
public class RenderConfig {

    // POLICY: mocha.* 키는 코드 default를 갖는다 — 설정 부재로 기동이 막히지 않는다
    // (ref: specs/coffee-note-agent/plan.md#ADR-50 POLICY). 값은 plan §5 문서값(./artifact)과 일치시킨다.
    // 종전에는 두 빈(여기 noteRenderer · 구 RouterConfig의 toolCallbackProvider)이 이 상수를 공유했다 —
    // 리터럴 2벌이면 한쪽만 바뀌었을 때 렌더러가 쓴 카드를 조회 tool이 다른 디렉터리에서 찾아 "카드 없음"이
    // 되고 전 테스트는 그린으로 남기 때문이다(changes/0025 CR25-9). 0029 TΔ1에서 조회 tool의 카드 송신
    // (send_entry_card)이 폐기되며 산출 디렉터리 주입이 끊겨 **지금 읽는 곳은 렌더러 하나**다. 상수는
    // 유지한다 — TΔ9가 카드를 온디맨드로 되돌리면 두 번째 소비자가 다시 생긴다.
    static final String DEFAULT_ARTIFACT_DIR = "${mocha.artifact.dir:./artifact}";

    /** 오프라인 실행용 Thymeleaf 엔진. 테스트도 이 팩토리를 재사용해 프로덕션과 같은 해석 규칙을 쓴다. */
    public static SpringTemplateEngine offlineTemplateEngine() {
        ClassLoaderTemplateResolver resolver = new ClassLoaderTemplateResolver();
        resolver.setPrefix("templates/");
        resolver.setSuffix(".html");
        resolver.setTemplateMode(TemplateMode.HTML);
        resolver.setCharacterEncoding(StandardCharsets.UTF_8.name());
        resolver.setCacheable(true);
        SpringTemplateEngine engine = new SpringTemplateEngine();
        engine.setTemplateResolver(resolver);
        return engine;
    }

    @Bean
    public SpringTemplateEngine noteTemplateEngine() {
        return offlineTemplateEngine();
    }

    @Bean
    public NoteRenderer noteRenderer(
            NoteService noteService,
            SpringTemplateEngine noteTemplateEngine,
            CardImageRenderer cardImageRenderer,
            @Value(DEFAULT_ARTIFACT_DIR) String artifactDir,
            // 기본 테마는 type-a 세리프 (ref: plan.md#ADR-54, changes/0021 TΔ5a 사용자 확정).
            @Value("${mocha.artifact.theme:type-a}") String theme) {
        return new ThymeleafNoteRenderer(
                noteService, noteTemplateEngine, Path.of(artifactDir), Theme.from(theme), cardImageRenderer);
    }
}
