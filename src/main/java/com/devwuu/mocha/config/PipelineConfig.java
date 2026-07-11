package com.devwuu.mocha.config;

import com.devwuu.mocha.json.MochaObjectMapper;
import com.devwuu.mocha.llm.LlmClient;
import com.devwuu.mocha.llm.SearchClient;
import com.devwuu.mocha.pipeline.IntentClassifier;
import com.devwuu.mocha.pipeline.NoteEnricher;
import com.devwuu.mocha.pipeline.NoteExtractor;
import com.devwuu.mocha.pipeline.NoteMatcher;
import com.devwuu.mocha.pipeline.PendingReviser;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 파이프라인 구성요소 빈 배선 (ref: plan.md §1 [2]~[4], tasks T3-6).
 * <p>{@link NoteExtractor}/{@link NoteMatcher}/{@link NoteEnricher}는 프레임워크 의존이 없는 순수
 * 오케스트레이션 부품이라 {@code @Component} 스캔 대신 여기서 명시적으로 조립한다 — 외부 경계
 * ({@link LlmClient}/{@link SearchClient})만 주입하고, 도메인 JSON 규칙은 {@link MochaObjectMapper}로 통일한다
 * (LlmConfig·RepositoryConfig와 동일 방침).
 * <p>{@link PendingReviser}(수정 분기, [1])도 같은 방침으로 여기서 조립한다 — {@link LlmClient}만 주입한다(tasks T3-7).
 * <p>{@link IntentClassifier}(입구 의도 게이트, [1.5])도 같은 방침 — 추출과 경량 모델 공용, 새 설정 키 없음(changes/0007, ADR-18).
 */
@Configuration
public class PipelineConfig {

    @Bean
    public IntentClassifier intentClassifier(LlmClient llmClient) {
        // 입구 의도 게이트([1.5]) — 추출과 경량 모델 공용, 새 설정 키 없음(delta 0007, ADR-18).
        return new IntentClassifier(llmClient, MochaObjectMapper.create());
    }

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

    @Bean
    public PendingReviser pendingReviser(LlmClient llmClient) {
        return new PendingReviser(llmClient, MochaObjectMapper.create());
    }
}
