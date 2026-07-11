package com.devwuu.mocha.pipeline;

import com.devwuu.mocha.json.MochaObjectMapper;
import com.devwuu.mocha.llm.LlmClient;
import com.devwuu.mocha.llm.LlmRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * IntentClassifier의 계약 검증 — 요청 조립(data-model §4.1)·스키마·응답 역직렬화를 결정론적으로 확인한다.
 * LLM 생성은 fake로 대체하고(§5.3), 2분류 판정 자체는 그 반환값을 그대로 보존하는지로 본다.
 * (ref: changes/0007 delta TΔ2, AC-Δ3 계열 부품 계약.)
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

    private IntentClassifier classifier(CapturingLlmClient llm) {
        return new IntentClassifier(llm, MochaObjectMapper.create());
    }

    @Test
    @DisplayName("요청 조립: 원문이 message 키로 사용자 프롬프트에 실린다 (data-model §4.1)")
    void assemblesRequestPayload() {
        CapturingLlmClient llm = new CapturingLlmClient();
        llm.response = new IntentResult(MessageIntent.RECORD);

        classifier(llm).classify("커피베라 예가체프 마셨는데 새콤하고 좋았다");

        String userPrompt = llm.captured.userPrompt();
        assertThat(userPrompt).contains("message");                              // snake_case 키
        assertThat(userPrompt).contains("커피베라 예가체프 마셨는데 새콤하고 좋았다"); // 원문 그대로
    }

    @Test
    @DisplayName("스키마: intent 단일 필드 + record/other 2값 enum을 강제한다 (data-model §4.1)")
    void enforcesTwoValueSchema() {
        CapturingLlmClient llm = new CapturingLlmClient();
        llm.response = new IntentResult(MessageIntent.RECORD);

        classifier(llm).classify("아무 말");

        String schema = llm.captured.jsonSchema();
        assertThat(schema).contains("\"intent\"");
        assertThat(schema).contains("\"record\"");
        assertThat(schema).contains("\"other\"");
        assertThat(schema).contains("\"additionalProperties\": false"); // strict
    }

    @Test
    @DisplayName("응답: record 판정을 그대로 보존한다")
    void preservesRecordIntent() {
        CapturingLlmClient llm = new CapturingLlmClient();
        llm.response = new IntentResult(MessageIntent.RECORD);

        IntentResult result = classifier(llm).classify("예가체프 좋았다");

        assertThat(result.intent()).isEqualTo(MessageIntent.RECORD);
    }

    @Test
    @DisplayName("응답: other 판정을 그대로 보존한다")
    void preservesOtherIntent() {
        CapturingLlmClient llm = new CapturingLlmClient();
        llm.response = new IntentResult(MessageIntent.OTHER);

        IntentResult result = classifier(llm).classify("안녕 오늘 날씨 좋다");

        assertThat(result.intent()).isEqualTo(MessageIntent.OTHER);
    }

    @Test
    @DisplayName("역직렬화: record/other JSON이 MessageIntent로 정확히 매핑된다 (data-model §4.1)")
    void deserializesResponseSchema() {
        assertThat(MochaObjectMapper.create().readValue("{\"intent\":\"record\"}", IntentResult.class).intent())
                .isEqualTo(MessageIntent.RECORD);
        assertThat(MochaObjectMapper.create().readValue("{\"intent\":\"other\"}", IntentResult.class).intent())
                .isEqualTo(MessageIntent.OTHER);
    }

    @Test
    @DisplayName("V-1 정신: 정의 외 intent 값은 역직렬화에서 거부된다")
    void rejectsUndefinedIntent() {
        assertThatThrownBy(() ->
                MochaObjectMapper.create().readValue("{\"intent\":\"query\"}", IntentResult.class))
                .isInstanceOf(RuntimeException.class);
    }
}
