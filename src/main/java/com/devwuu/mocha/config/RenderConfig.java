package com.devwuu.mocha.config;

import com.devwuu.mocha.render.CardImageRenderer;
import com.devwuu.mocha.render.NoteRenderer;
import com.devwuu.mocha.render.Theme;
import com.devwuu.mocha.render.ThymeleafNoteRenderer;
import com.devwuu.mocha.repository.NoteRepository;
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
 * 템플릿은 {@code templates/<theme>/{index,note}.html}에 있고 테마({@code mocha.artifact.theme})가 폴더를 고른다.
 * <p>{@link ThymeleafNoteRenderer}는 프레임워크 의존이 없는 순수 렌더러라 {@code @Component} 스캔 대신 여기서
 * 명시적으로 조립한다(RepositoryConfig·PipelineConfig와 동일 방침).
 */
@Configuration
public class RenderConfig {

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
            NoteRepository noteRepository,
            SpringTemplateEngine noteTemplateEngine,
            CardImageRenderer cardImageRenderer,
            @Value("${mocha.artifact.dir}") String artifactDir,
            @Value("${mocha.artifact.theme}") String theme) {
        return new ThymeleafNoteRenderer(
                noteRepository, noteTemplateEngine, Path.of(artifactDir), Theme.from(theme), cardImageRenderer);
    }
}
