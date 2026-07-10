package com.devwuu.mocha.pipeline;

import com.devwuu.mocha.domain.Rating;
import com.devwuu.mocha.json.MochaObjectMapper;
import com.devwuu.mocha.llm.LlmClient;
import com.devwuu.mocha.llm.LlmRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * NoteExtractor의 계약 검증 — 요청 조립(data-model §3)과 응답 통과(§4)를 결정론적으로 확인한다.
 * LLM 생성은 fake로 대체하고(§5.3), 상대 날짜 해석 같은 모델 판단은 그 반환값을 그대로 보존하는지로 본다.
 */
class NoteExtractorTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 10);

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

    private NoteExtractor extractor(CapturingLlmClient llm) {
        return new NoteExtractor(llm, MochaObjectMapper.create());
    }

    @Test
    @DisplayName("요청 조립: 원문·today·existing_notes 후보가 사용자 프롬프트에 실린다 (data-model §3)")
    void assemblesRequestPayload() {
        CapturingLlmClient llm = new CapturingLlmClient();
        llm.response = new ExtractionResult("예가체프", null, null, null, null, null, null,
                "coffeevera-yirgacheffe-g1", TODAY);

        extractor(llm).extract(
                "커피베라 예가체프 마셨는데 새콤",
                TODAY,
                List.of(new NoteCandidate("coffeevera-yirgacheffe-g1", "커피베라 예가체프 G1", "커피베라")));

        String userPrompt = llm.captured.userPrompt();
        assertThat(userPrompt).contains("커피베라 예가체프 마셨는데 새콤"); // 원문
        assertThat(userPrompt).contains("2026-07-10");                    // today
        assertThat(userPrompt).contains("existing_notes");                // snake_case 키
        assertThat(userPrompt).contains("coffeevera-yirgacheffe-g1");     // 후보 slug
    }

    @Test
    @DisplayName("상대 날짜: '어제 마신' 해석 결과(today-1)를 그대로 보존한다")
    void preservesRelativeTargetDate() {
        CapturingLlmClient llm = new CapturingLlmClient();
        // LLM이 today를 기준으로 '어제'를 해석해 target_date를 채워 돌려준다.
        llm.response = new ExtractionResult("예가체프", null, null, null, null, "좋았다",
                Rating.GOOD, null, TODAY.minusDays(1));

        ExtractionResult result = extractor(llm).extract("어제 마신 예가체프 좋았다", TODAY, List.of());

        assertThat(result.targetDate()).isEqualTo(TODAY.minusDays(1));
        // 해석 근거(today)가 프롬프트에 실려 있어야 LLM이 상대 날짜를 계산할 수 있다.
        assertThat(llm.captured.userPrompt()).contains("2026-07-10");
    }

    @Test
    @DisplayName("FR-2: 언급되지 않은 필드는 null로 유지된다")
    void keepsUnmentionedFieldsNull() {
        CapturingLlmClient llm = new CapturingLlmClient();
        llm.response = new ExtractionResult("예가체프", "커피베라", null, null, null, "새콤",
                null, null, TODAY);

        ExtractionResult result = extractor(llm).extract("커피베라 예가체프 새콤", TODAY, List.of());

        assertThat(result.origin()).isNull();
        assertThat(result.process()).isNull();
        assertThat(result.roastLevel()).isNull();
        assertThat(result.rating()).isNull();
        assertThat(result.matchedSlug()).isNull();
    }

    @Test
    @DisplayName("target_date 미해석(null) 시 today로 기본화한다 (data-model §4)")
    void defaultsNullTargetDateToToday() {
        CapturingLlmClient llm = new CapturingLlmClient();
        llm.response = new ExtractionResult("예가체프", null, null, null, null, null,
                null, null, null);

        ExtractionResult result = extractor(llm).extract("예가체프", TODAY, List.of());

        assertThat(result.targetDate()).isEqualTo(TODAY);
    }

    @Test
    @DisplayName("§4 응답 JSON이 snake_case·rating·날짜로 정확히 역직렬화된다")
    void deserializesResponseSchema() {
        String json = """
                {
                  "coffee_name": "커피베라 예가체프 G1",
                  "roastery": "커피베라",
                  "origin": null,
                  "process": null,
                  "roast_level": null,
                  "my_taste": "새콤하고 좋았다",
                  "rating": "맛있다",
                  "matched_slug": "coffeevera-yirgacheffe-g1",
                  "target_date": "2026-07-09"
                }
                """;

        ExtractionResult result = MochaObjectMapper.create().readValue(json, ExtractionResult.class);

        assertThat(result.coffeeName()).isEqualTo("커피베라 예가체프 G1");
        assertThat(result.myTaste()).isEqualTo("새콤하고 좋았다");
        assertThat(result.rating()).isEqualTo(Rating.GOOD);
        assertThat(result.matchedSlug()).isEqualTo("coffeevera-yirgacheffe-g1");
        assertThat(result.targetDate()).isEqualTo(LocalDate.of(2026, 7, 9));
        assertThat(result.origin()).isNull();
    }
}
