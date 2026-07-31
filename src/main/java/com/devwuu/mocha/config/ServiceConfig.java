package com.devwuu.mocha.config;

import com.devwuu.mocha.llm.AliasGenerator;
import com.devwuu.mocha.repository.NoteRepository;
import com.devwuu.mocha.service.NoteService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 유스케이스 계층 빈 배선 (ref: 백엔드 CLAUDE.md §2, changes/0029 delta#D-8, tasks TΔ4a).
 *
 * <p>{@link NoteService}는 프레임워크 의존이 없는 순수 협력자라 {@code @Component} 스캔 대신 여기서
 * 명시적으로 조립한다({@link RepositoryConfig}·{@link RenderConfig}와 동일 방침, ADR-63).
 *
 * <p>{@link AliasGenerator} 주입이 <b>TΔ4에서 끊겼던 배선을 되살린다</b> — 빈은 {@link LlmConfig}가 계속
 * 등록하고 있었으나 호출부가 {@code SlackCommitHandler}와 함께 사라져 아무도 부르지 않는 상태였다.
 */
@Configuration
public class ServiceConfig {

    @Bean
    public NoteService noteService(NoteRepository noteRepository, AliasGenerator aliasGenerator) {
        return new NoteService(noteRepository, aliasGenerator);
    }
}
