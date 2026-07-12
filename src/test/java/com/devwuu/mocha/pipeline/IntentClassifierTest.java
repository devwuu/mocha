package com.devwuu.mocha.pipeline;

import com.devwuu.mocha.json.MochaObjectMapper;
import com.devwuu.mocha.llm.LlmClient;
import com.devwuu.mocha.llm.LlmRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * IntentClassifier의 계약 검증 — 요청 조립(data-model §4.1: message + context 힌트)·5값 스키마·응답 역직렬화를
 * 결정론적으로 확인한다. LLM 생성은 fake로 대체하고(§5.3), 판정 자체는 그 반환값을 그대로 보존하는지로 본다.
 * (ref: changes/0011 delta TΔ1, ADR-24, FR-17.)
 */
class IntentClassifierTest {

    /** 요청을 포착하고 준비된 결과를 돌려주는 fake — SDK/실 API를 쓰지 않는다(§5.2). */
    static class CapturingLlmClient implements LlmClient {
        LlmRequest<?> captured;
        Object response;

        @Override
        @SuppressWarnings("unchecked")
        public <T> T complete(LlmRequest<T> request) {
            this.captured = request;
            return (T) response;
        }
    }

    private static final ContextHint NO_CONTEXT = new ContextHint(false, false);

    private IntentClassifier classifier(CapturingLlmClient llm) {
        return new IntentClassifier(llm, MochaObjectMapper.create());
    }

    @Test
    @DisplayName("요청 조립: 원문이 message 키로, 힌트가 context 키로 사용자 프롬프트에 실린다 (data-model §4.1)")
    void assemblesRequestPayload() {
        CapturingLlmClient llm = new CapturingLlmClient();
        llm.response = new IntentResult(MessageIntent.RECORD);

        classifier(llm).classify("커피베라 예가체프 마셨는데 새콤하고 좋았다", NO_CONTEXT);

        String userPrompt = llm.captured.userPrompt();
        assertThat(userPrompt).contains("message");                              // snake_case 키
        assertThat(userPrompt).contains("커피베라 예가체프 마셨는데 새콤하고 좋았다"); // 원문 그대로
        assertThat(userPrompt).contains("\"context\"");
        assertThat(userPrompt).contains("\"has_pending\":false");
        assertThat(userPrompt).contains("\"search_session_active\":false");
    }

    @Test
    @DisplayName("요청 조립: 컨텍스트 힌트의 참값이 snake_case로 직렬화된다 (data-model §4.1, ADR-24)")
    void serializesContextHintValues() {
        CapturingLlmClient llm = new CapturingLlmClient();
        llm.response = new IntentResult(MessageIntent.SEARCH);

        classifier(llm).classify("저번에 마신 예가체프 찾아줘", new ContextHint(true, true));

        String userPrompt = llm.captured.userPrompt();
        assertThat(userPrompt).contains("\"has_pending\":true");
        assertThat(userPrompt).contains("\"search_session_active\":true");
    }

    @Test
    @DisplayName("스키마: intent 단일 필드 + record/revise/search/end/other 5값 enum을 강제한다 (data-model §4.1)")
    void enforcesFiveValueSchema() {
        CapturingLlmClient llm = new CapturingLlmClient();
        llm.response = new IntentResult(MessageIntent.RECORD);

        classifier(llm).classify("아무 말", NO_CONTEXT);

        String schema = llm.captured.jsonSchema();
        assertThat(schema).contains("\"intent\"");
        assertThat(schema).contains("\"record\"");
        assertThat(schema).contains("\"revise\"");
        assertThat(schema).contains("\"search\"");
        assertThat(schema).contains("\"end\"");
        assertThat(schema).contains("\"other\"");
        assertThat(schema).contains("\"additionalProperties\": false"); // strict
    }

    @Test
    @DisplayName("프롬프트: 검색은 명시적 조회 신호가 있을 때만 판정하고 애매하면 폴백에 맡기는 지침을 담는다 (ADR-24)")
    void promptGuidesExplicitSearchAndFallback() {
        CapturingLlmClient llm = new CapturingLlmClient();
        llm.response = new IntentResult(MessageIntent.RECORD);

        classifier(llm).classify("아무 말", NO_CONTEXT);

        String systemPrompt = llm.captured.systemPrompt();
        assertThat(systemPrompt).contains("명시적 조회 신호가 있을 때만");
        assertThat(systemPrompt).contains("폴백에 맡긴다");
    }

    @Test
    @DisplayName("응답: record 판정을 그대로 보존한다")
    void preservesRecordIntent() {
        CapturingLlmClient llm = new CapturingLlmClient();
        llm.response = new IntentResult(MessageIntent.RECORD);

        IntentResult result = classifier(llm).classify("예가체프 좋았다", NO_CONTEXT);

        assertThat(result.intent()).isEqualTo(MessageIntent.RECORD);
    }

    @Test
    @DisplayName("응답: search 판정을 그대로 보존한다")
    void preservesSearchIntent() {
        CapturingLlmClient llm = new CapturingLlmClient();
        llm.response = new IntentResult(MessageIntent.SEARCH);

        IntentResult result = classifier(llm).classify("저번에 마신 거 찾아줘", NO_CONTEXT);

        assertThat(result.intent()).isEqualTo(MessageIntent.SEARCH);
    }

    @Test
    @DisplayName("역직렬화: 5값 JSON이 MessageIntent로 정확히 매핑된다 (data-model §4.1)")
    void deserializesResponseSchema() {
        var mapper = MochaObjectMapper.create();
        assertThat(mapper.readValue("{\"intent\":\"record\"}", IntentResult.class).intent())
                .isEqualTo(MessageIntent.RECORD);
        assertThat(mapper.readValue("{\"intent\":\"revise\"}", IntentResult.class).intent())
                .isEqualTo(MessageIntent.REVISE);
        assertThat(mapper.readValue("{\"intent\":\"search\"}", IntentResult.class).intent())
                .isEqualTo(MessageIntent.SEARCH);
        assertThat(mapper.readValue("{\"intent\":\"end\"}", IntentResult.class).intent())
                .isEqualTo(MessageIntent.END);
        assertThat(mapper.readValue("{\"intent\":\"other\"}", IntentResult.class).intent())
                .isEqualTo(MessageIntent.OTHER);
    }

    @Test
    @DisplayName("V-1 정신: 정의 외 intent 값은 역직렬화에서 거부된다")
    void rejectsUndefinedIntent() {
        assertThatThrownBy(() ->
                MochaObjectMapper.create().readValue("{\"intent\":\"query\"}", IntentResult.class))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() ->
                MochaObjectMapper.create().readValue("{\"intent\":\"save\"}", IntentResult.class))
                .isInstanceOf(RuntimeException.class);
    }
}
