package com.devwuu.mocha.llm;

import java.util.Objects;

/**
 * LLM structured output 요청 (ref: specs/coffee-note-agent/data-model.md#3, #4).
 * <p>SDK 중립적인 값객체 — 파이프라인(NoteExtractor 등)이 스키마·프롬프트를 조립해 넘기고,
 * 구현체가 벤더 API 형식으로 변환한다. OpenAI SDK 타입을 담지 않는다(plan §4 POLICY).
 *
 * @param schemaName   structured output 스키마 이름(벤더 요구)
 * @param jsonSchema   JSON schema 문자열(strict). 응답 구조를 강제한다(ADR-6)
 * @param systemPrompt 시스템 지침(널 허용 — 없으면 사용자 프롬프트만 전송)
 * @param userPrompt   사용자 프롬프트(원문·컨텍스트)
 * @param responseType 응답 역직렬화 대상 타입
 */
public record LlmRequest<T>(
        String schemaName,
        String jsonSchema,
        String systemPrompt,
        String userPrompt,
        Class<T> responseType) {

    public LlmRequest {
        Objects.requireNonNull(schemaName, "schemaName");
        Objects.requireNonNull(jsonSchema, "jsonSchema");
        Objects.requireNonNull(userPrompt, "userPrompt");
        Objects.requireNonNull(responseType, "responseType");
    }
}
