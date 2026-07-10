package com.devwuu.mocha.llm;

import com.devwuu.mocha.domain.Rating;
import com.devwuu.mocha.json.MochaObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * OpenAiLlmClient의 계약 검증 — 역직렬화·재시도 정책(V-1, AC-9)을 SDK 호출 없이 결정론적으로 확인한다
 * (ref: CLAUDE.md §5.2 외부 연동 어댑터 — 응답 모킹, 실 API 스모크는 수동).
 */
class OpenAiLlmClientTest {

    /** 추출 응답 계약 일부를 본뜬 타입 — snake_case + rating 4범주 enum(V-1)으로 위반 경로를 만든다. */
    record Extraction(String coffeeName, Rating rating) {
    }

    /** {@code call} 시임을 미리 준비한 JSON 시퀀스로 대체하는 스텁. SDK 클라이언트는 쓰지 않는다. */
    static class StubLlmClient extends OpenAiLlmClient {
        private final Deque<String> responses;
        int calls = 0;

        StubLlmClient(int maxRetries, String... responses) {
            super(null, "test-model", maxRetries, MochaObjectMapper.create());
            this.responses = new ArrayDeque<>(List.of(responses));
        }

        @Override
        protected String call(LlmRequest<?> request) {
            calls++;
            return responses.poll();
        }
    }

    private static final String VALID = "{\"coffee_name\":\"예가체프\",\"rating\":\"맛있다\"}";
    // rating이 4범주 밖 → Rating.from 예외 → 역직렬화 위반(V-1).
    private static final String INVALID = "{\"coffee_name\":\"예가체프\",\"rating\":\"별로임\"}";

    private static LlmRequest<Extraction> request() {
        return new LlmRequest<>("extraction", "{\"type\":\"object\"}", null, "메시지", Extraction.class);
    }

    @Test
    @DisplayName("정상 응답은 대상 타입으로 역직렬화된다")
    void deserializesValidResponse() {
        StubLlmClient client = new StubLlmClient(1, VALID);

        Extraction result = client.complete(request());

        assertThat(result.coffeeName()).isEqualTo("예가체프");
        assertThat(result.rating()).isEqualTo(Rating.GOOD);
        assertThat(client.calls).isEqualTo(1);
    }

    @Test
    @DisplayName("V-1: 스키마 위반 1회 후 유효 응답이면 1회 재추출로 성공한다")
    void retriesOnceThenSucceeds() {
        StubLlmClient client = new StubLlmClient(1, INVALID, VALID);

        Extraction result = client.complete(request());

        assertThat(result.rating()).isEqualTo(Rating.GOOD);
        assertThat(client.calls).isEqualTo(2); // 최초 + 재시도 1
    }

    @Test
    @DisplayName("AC-9: 재시도 소진 후에도 위반이면 LlmException으로 수렴한다")
    void throwsAfterRetriesExhausted() {
        StubLlmClient client = new StubLlmClient(1, INVALID, INVALID);

        assertThatThrownBy(() -> client.complete(request()))
                .isInstanceOf(LlmException.class);
        assertThat(client.calls).isEqualTo(2);
    }

    @Test
    @DisplayName("max-retries=0이면 재시도 없이 첫 위반에서 실패한다(재시도 횟수 설정 반영)")
    void respectsZeroRetries() {
        StubLlmClient client = new StubLlmClient(0, INVALID);

        assertThatThrownBy(() -> client.complete(request()))
                .isInstanceOf(LlmException.class);
        assertThat(client.calls).isEqualTo(1);
    }

    @Test
    @DisplayName("max-retries=2면 두 번까지 재추출한다(재시도 횟수 설정 반영)")
    void respectsHigherRetries() {
        StubLlmClient client = new StubLlmClient(2, INVALID, INVALID, VALID);

        Extraction result = client.complete(request());

        assertThat(result.rating()).isEqualTo(Rating.GOOD);
        assertThat(client.calls).isEqualTo(3); // 최초 + 재시도 2
    }
}
