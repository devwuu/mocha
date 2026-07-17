package com.devwuu.mocha.config;

import com.devwuu.mocha.json.MochaObjectMapper;
import com.devwuu.mocha.llm.LlmClient;
import com.devwuu.mocha.llm.OfficialPageImageCollector;
import com.devwuu.mocha.llm.OpenAiLlmClient;
import com.devwuu.mocha.llm.OpenAiSearchClient;
import com.devwuu.mocha.llm.OpenAiVisionClient;
import com.devwuu.mocha.llm.SearchClient;
import com.devwuu.mocha.llm.VisionClient;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

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

    // 추출·게이트 등 기존 단발 콜 공용 클라이언트 — 구 키(mocha.llm.model)와 함께 TΔ8b에서 폐기 예정.
    // 별칭 전용 클라이언트(aliasLlmClient)와 구분하기 위해 기본 주입 대상(@Primary)으로 지정한다.
    @Bean
    @Primary
    public LlmClient llmClient(
            OpenAIClient openAiClient,
            @Value("${mocha.llm.model}") String model,
            @Value("${mocha.llm.max-retries:1}") int maxRetries) {
        return new OpenAiLlmClient(openAiClient, model, maxRetries, MochaObjectMapper.create());
    }

    // 별칭 생성 전용 클라이언트(ADR-50, changes/0018 TΔ4) — 텍스트 전용 최경량(mocha.alias.model).
    // 재시도 1회는 코드 상수 — 별칭 콜 실패는 저장을 되돌리지 않는 실패 허용 경로라(ADR-37 POLICY) 키 불요.
    @Bean
    public LlmClient aliasLlmClient(
            OpenAIClient openAiClient,
            @Value("${mocha.alias.model:gpt-5.4-nano}") String model) {
        return new OpenAiLlmClient(openAiClient, model, 1, MochaObjectMapper.create());
    }

    // 검색 보강은 추출과 별도 모델을 쓴다 — web_search로 공식 페이지를 찾아내는 능력이 필요해 상위 모델을
    // 붙인다(mocha.search.model). 추출은 경량(mocha.llm.model) 유지로 비용을 통제한다.
    // 2단계(공식 페이지 이미지 OCR, ADR-15) 협력자를 함께 주입한다 — 이미지 수집기(Jsoup 경계)와 vision 경계.
    @Bean
    public SearchClient searchClient(
            OpenAIClient openAiClient,
            VisionClient visionClient,
            @Value("${mocha.search.model:gpt-4o}") String model,
            @Value("${mocha.search.max-results:3}") int maxResults) {
        return new OpenAiSearchClient(openAiClient, model, maxResults, MochaObjectMapper.create(),
                new OfficialPageImageCollector(), visionClient);
    }

    // 사진 OCR용 vision 경계 — 모델은 전용 경량 키(mocha.vision.model, ADR-50 · changes/0018 TΔ4)로
    // 구 mocha.search.model 공용에서 분리했다(전처리에 상위 모델 낭비 금지). 재사용 경계이므로
    // SearchClient가 아닌 별도 빈으로 노출한다(NFR-4).
    @Bean
    public VisionClient visionClient(
            OpenAIClient openAiClient,
            @Value("${mocha.vision.model:gpt-5.4-mini}") String model) {
        return new OpenAiVisionClient(openAiClient, model, MochaObjectMapper.create());
    }
}
