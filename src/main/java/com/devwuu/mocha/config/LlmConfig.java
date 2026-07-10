package com.devwuu.mocha.config;

import com.devwuu.mocha.json.MochaObjectMapper;
import com.devwuu.mocha.llm.LlmClient;
import com.devwuu.mocha.llm.OpenAiLlmClient;
import com.devwuu.mocha.llm.OpenAiSearchClient;
import com.devwuu.mocha.llm.SearchClient;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * LLM 클라이언트 빈 배선 (ref: plan.md#ADR-5). OpenAI SDK 타입은 여기와 구현체에만 존재한다.
 * <p>비밀(OPENAI_API_KEY)은 코드/설정에 하드코딩하지 않고 환경변수(.env → .env.local)로 주입한다
 * (루트 CLAUDE.md §5). 미설정 프로파일에서도 컨텍스트가 뜨도록 빈 기본값을 둔다 — 실제 호출 시에만 필요.
 */
@Configuration
public class LlmConfig {

    @Bean
    public OpenAIClient openAiClient(@Value("${mocha.openai.api-key:}") String apiKey) {
        return OpenAIOkHttpClient.builder()
                .apiKey(apiKey)
                .build();
    }

    @Bean
    public LlmClient llmClient(
            OpenAIClient openAiClient,
            @Value("${mocha.llm.model}") String model,
            @Value("${mocha.llm.max-retries:1}") int maxRetries) {
        return new OpenAiLlmClient(openAiClient, model, maxRetries, MochaObjectMapper.create());
    }

    @Bean
    public SearchClient searchClient(
            OpenAIClient openAiClient,
            @Value("${mocha.llm.model}") String model,
            @Value("${mocha.search.max-results:3}") int maxResults) {
        return new OpenAiSearchClient(openAiClient, model, maxResults, MochaObjectMapper.create());
    }
}
