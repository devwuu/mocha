package com.devwuu.mocha.config;

import com.devwuu.mocha.llm.OpenAiAliasGenerator;
import com.devwuu.mocha.llm.OpenAiVisionClient;
import com.devwuu.mocha.llm.VisionClient;
import com.devwuu.mocha.llm.AliasGenerator;
import com.devwuu.mocha.llm.PhotoInfoExtractor;
import com.openai.client.OpenAIClient;
import com.openai.client.okhttp.OpenAIOkHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

/**
 * 에이전트 루프 밖 보조 콜(vision OCR·별칭 생성) 어댑터 + OCR 전처리({@link PhotoInfoExtractor}) 빈 배선
 * (ref: plan.md#ADR-37·ADR-50·ADR-51, NFR-4).
 * 루프 드라이버({@code AgentClient})는 {@link AgentConfig}가 배선한다. OpenAI SDK 타입은 여기와
 * 구현체에만 존재한다. 구 단발 콜·검색 클라이언트 계열은 에이전트 전환으로 폐기됐다(changes/0018 TΔ8b).
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

    // 사진 OCR용 vision 경계 — 모델은 전용 경량 키(mocha.vision.model, ADR-50 · changes/0018 TΔ4)로
    // 구 검색 모델 공용에서 분리했다(전처리에 상위 모델 낭비 금지). 재사용 경계이므로
    // 별도 빈으로 노출한다(NFR-4).
    @Bean
    public VisionClient visionClient(
            OpenAIClient openAiClient,
            @Value("${mocha.vision.model:gpt-5.4-mini}") String model,
            ObjectMapper mapper) {
        return new OpenAiVisionClient(openAiClient, model, mapper);
    }

    // 별칭 생성 경계(ADR-37 — [저장] 신규 커밋 시 1콜) — 텍스트 전용 최경량 전용 키(mocha.alias.model,
    // ADR-50). 역할별 키 일관 규칙 — vision·agent 키에 편승하지 않는다.
    @Bean
    public AliasGenerator aliasGenerator(
            OpenAIClient openAiClient,
            @Value("${mocha.alias.model:gpt-5.4-nano}") String model,
            ObjectMapper mapper) {
        return new OpenAiAliasGenerator(openAiClient, model, mapper);
    }

    // 수신 사진 OCR(루프 전 전처리, FR-19/ADR-23) — VisionClient(전용 키 mocha.vision.model — ADR-50) 재사용.
    // 1콜당 이미지 상한은 mocha.vision.max-images(기본 4).
    @Bean
    public PhotoInfoExtractor photoInfoExtractor(
            VisionClient visionClient,
            @Value("${mocha.vision.max-images:4}") int maxImages) {
        return new PhotoInfoExtractor(visionClient, maxImages);
    }
}
