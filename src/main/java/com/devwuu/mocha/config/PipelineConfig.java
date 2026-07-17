package com.devwuu.mocha.config;

import com.devwuu.mocha.llm.VisionClient;
import com.devwuu.mocha.pipeline.PhotoInfoExtractor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 에이전트 턴 전처리 부품 빈 배선 (ref: plan.md §1 [2], #ADR-23).
 * <p>구 단발 파이프라인 부품(게이트·추출·매칭·보강·수정·검색 서비스)은 에이전트 루프로 대체돼
 * 폐기됐다(changes/0018 TΔ8b, ADR-44). {@link PhotoInfoExtractor}는 루프 전 OCR 전처리로 존치한다.
 */
@Configuration
public class PipelineConfig {

    // 수신 사진 OCR(루프 전 전처리, FR-19/ADR-23) — VisionClient(LlmConfig, 전용 키 mocha.vision.model — ADR-50) 재사용.
    // 1콜당 이미지 상한은 mocha.vision.max-images(기본 4).
    @Bean
    public PhotoInfoExtractor photoInfoExtractor(
            VisionClient visionClient,
            @Value("${mocha.vision.max-images:4}") int maxImages) {
        return new PhotoInfoExtractor(visionClient, maxImages);
    }
}
