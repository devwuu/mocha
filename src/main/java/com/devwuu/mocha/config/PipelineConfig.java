package com.devwuu.mocha.config;

import com.devwuu.mocha.json.MochaObjectMapper;
import com.devwuu.mocha.llm.LlmClient;
import com.devwuu.mocha.llm.SearchClient;
import com.devwuu.mocha.pipeline.NoteEnricher;
import com.devwuu.mocha.pipeline.NoteExtractor;
import com.devwuu.mocha.pipeline.NoteMatcher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 파이프라인 구성요소 빈 배선 (ref: plan.md §1 [2]~[4], tasks T3-6).
 * <p>{@link NoteExtractor}/{@link NoteMatcher}/{@link NoteEnricher}는 프레임워크 의존이 없는 순수
 * 오케스트레이션 부품이라 {@code @Component} 스캔 대신 여기서 명시적으로 조립한다 — 외부 경계
 * ({@link LlmClient}/{@link SearchClient})만 주입하고, 도메인 JSON 규칙은 {@link MochaObjectMapper}로 통일한다
 * (LlmConfig·RepositoryConfig와 동일 방침).
 * <p>{@code PendingReviser}(수정 분기)는 T3-7에서 배선한다 — 여기서는 신규 파이프라인([2]~[4])만 다룬다.
 */
@Configuration
public class PipelineConfig {

    @Bean
    public NoteExtractor noteExtractor(LlmClient llmClient) {
        return new NoteExtractor(llmClient, MochaObjectMapper.create());
    }

    @Bean
    public NoteMatcher noteMatcher() {
        return new NoteMatcher();
    }

    @Bean
    public NoteEnricher noteEnricher(SearchClient searchClient) {
        return new NoteEnricher(searchClient);
    }
}
