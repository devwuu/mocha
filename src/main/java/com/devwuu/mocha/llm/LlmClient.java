package com.devwuu.mocha.llm;

/**
 * 구조화 추출을 위한 LLM 경계 (ref: specs/coffee-note-agent/plan.md#ADR-5, #ADR-6).
 * <p>계약: {@code complete(schema, prompt): T} — JSON schema로 structured output을 강제하고,
 * 응답을 타입 {@code T}로 역직렬화해 돌려준다. 스키마/도메인 위반(예: rating 4범주 외, V-1)으로
 * 역직렬화가 실패하면 설정된 횟수만큼 재추출하고, 끝내 실패하면 {@link LlmException}으로 수렴한다.
 * <p>POLICY: 파이프라인은 이 인터페이스 뒤에만 의존하고 OpenAI SDK 타입을 직접 참조하지 않는다
 * (ref: plan.md#ADR-5 POLICY, NFR-4). 구현: {@link OpenAiLlmClient}.
 */
public interface LlmClient {

    /**
     * structured output 완성 요청. 응답 JSON을 {@code request.responseType()}으로 역직렬화해 반환한다.
     *
     * @throws LlmException 호출 실패, 또는 재시도 소진 후에도 스키마/도메인 위반이 남을 때 (plan §7, V-1)
     */
    <T> T complete(LlmRequest<T> request);
}
