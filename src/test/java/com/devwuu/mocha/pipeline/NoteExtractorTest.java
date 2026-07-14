package com.devwuu.mocha.pipeline;

import com.devwuu.mocha.domain.Rating;
import com.devwuu.mocha.domain.Recipe;
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
        llm.response = new ExtractionResult("예가체프", null, null, null, null, null, null, null,
                "coffeevera-yirgacheffe-g1", false, TODAY);

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
    @DisplayName("TΔ4: 선행 OCR photo_hint(커피명·로스터리)가 요청에 실려 matched_slug 판정 재료가 된다 (data-model §3, ADR-36)")
    void assemblesPhotoHintIntoRequest() {
        CapturingLlmClient llm = new CapturingLlmClient();
        // 텍스트엔 커피명이 없고(사진-only 정체성), 힌트가 OCR이 읽은 식별 정보를 준다.
        llm.response = new ExtractionResult(null, null, null, null, null, "달큰했음",
                Rating.GOOD, null, "chelbesa-frob", false, TODAY);

        extractor(llm).extract(
                "이거 달큰하고 좋았어",
                TODAY,
                new PhotoHint("Ethiopia Chelbesa", "FroB"),
                List.of(new NoteCandidate("chelbesa-frob", "에티오피아 첼베사", "프롭")));

        String userPrompt = llm.captured.userPrompt();
        assertThat(userPrompt).contains("photo_hint");            // snake_case 키
        assertThat(userPrompt).contains("Ethiopia Chelbesa");     // OCR 커피명
        assertThat(userPrompt).contains("FroB");                  // OCR 로스터리
    }

    @Test
    @DisplayName("TΔ4: 사진 없음·OCR 실패 시 photo_hint가 null로 실려 종전 흐름 그대로다 (AC-28)")
    void serializesNullPhotoHintWhenAbsent() {
        CapturingLlmClient llm = new CapturingLlmClient();
        llm.response = new ExtractionResult("예가체프", null, null, null, null, null,
                null, null, null, false, TODAY);

        // 3-arg 오버로드(사진 없는 흐름) — photo_hint 없이 추출한다.
        extractor(llm).extract("예가체프 마셨어", TODAY, List.of());

        assertThat(llm.captured.userPrompt()).contains("\"photo_hint\":null");
    }

    @Test
    @DisplayName("상대 날짜: '어제 마신' 해석 결과(today-1)를 그대로 보존한다")
    void preservesRelativeTargetDate() {
        CapturingLlmClient llm = new CapturingLlmClient();
        // LLM이 today를 기준으로 '어제'를 해석해 target_date를 채워 돌려준다.
        llm.response = new ExtractionResult("예가체프", null, null, null, null, "좋았다",
                Rating.GOOD, null, null, false, TODAY.minusDays(1));

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
                null, null, null, false, TODAY);

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
                null, null, null, false, null);

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
                  "recipe": { "dose_g": 15, "water_ml": 240, "grind": null },
                  "matched_slug": "coffeevera-yirgacheffe-g1",
                  "references_past": false,
                  "target_date": "2026-07-09"
                }
                """;

        ExtractionResult result = MochaObjectMapper.create().readValue(json, ExtractionResult.class);

        assertThat(result.coffeeName()).isEqualTo("커피베라 예가체프 G1");
        assertThat(result.myTaste()).isEqualTo("새콤하고 좋았다");
        assertThat(result.rating()).isEqualTo(Rating.GOOD);
        assertThat(result.matchedSlug()).isEqualTo("coffeevera-yirgacheffe-g1");
        assertThat(result.referencesPast()).isFalse();
        assertThat(result.targetDate()).isEqualTo(LocalDate.of(2026, 7, 9));
        assertThat(result.origin()).isNull();
    }

    @Test
    @DisplayName("FR-14: references_past=true 응답이 그대로 역직렬화된다 (data-model §4, changes/0011)")
    void deserializesReferencesPastTrue() {
        String json = """
                {
                  "coffee_name": "예가체프",
                  "roastery": null, "origin": null, "process": null, "roast_level": null,
                  "my_taste": "또 마셨는데 여전히 좋다", "rating": null, "recipe": null,
                  "matched_slug": null,
                  "references_past": true,
                  "target_date": "2026-07-10"
                }
                """;

        ExtractionResult result = MochaObjectMapper.create().readValue(json, ExtractionResult.class);

        assertThat(result.referencesPast()).isTrue();
    }

    @Test
    @DisplayName("FR-14: references_past 부재 시 기본 false다 (data-model §4 '기본 false')")
    void defaultsReferencesPastToFalse() {
        String json = """
                {
                  "coffee_name": "예가체프",
                  "roastery": null, "origin": null, "process": null, "roast_level": null,
                  "my_taste": null, "rating": null, "recipe": null,
                  "matched_slug": null, "target_date": "2026-07-10"
                }
                """;

        ExtractionResult result = MochaObjectMapper.create().readValue(json, ExtractionResult.class);

        assertThat(result.referencesPast()).isFalse();
    }

    @Test
    @DisplayName("FR-14: 스키마·프롬프트가 references_past를 계약으로 강제한다 (changes/0011)")
    void schemaAndPromptDeclareReferencesPastContract() {
        CapturingLlmClient llm = new CapturingLlmClient();
        // 참조 표현("저번에 그") — LLM이 references_past=true로 반환한다.
        llm.response = new ExtractionResult("예가체프", null, null, null, null, "여전히 좋다",
                null, null, null, true, TODAY);

        ExtractionResult result = extractor(llm).extract("저번에 마신 그 예가체프 또 마셨어", TODAY, List.of());

        // 계약: 추출기가 반환한 references_past를 그대로 통과시킨다(분기 판단은 이후 단계 몫).
        assertThat(result.referencesPast()).isTrue();
        // structured output 스키마가 references_past를 required boolean으로 선언해 응답 구조를 강제한다.
        LlmRequest<?> request = llm.captured;
        assertThat(request.jsonSchema()).contains("references_past");
        assertThat(request.systemPrompt()).contains("references_past");
    }

    @Test
    @DisplayName("FR-18: recipe 언급 시 dose_g·water_ml·grind가 채워져 역직렬화된다 (changes/0010)")
    void deserializesRecipeWhenMentioned() {
        String json = """
                {
                  "coffee_name": "예가체프",
                  "roastery": null, "origin": null, "process": null, "roast_level": null,
                  "my_taste": "새콤", "rating": null,
                  "recipe": { "dose_g": 15, "water_ml": 240, "grind": "중간" },
                  "matched_slug": null, "target_date": "2026-07-10"
                }
                """;

        ExtractionResult result = MochaObjectMapper.create().readValue(json, ExtractionResult.class);

        assertThat(result.recipe()).isEqualTo(new Recipe(15.0, 240.0, "중간"));
    }

    @Test
    @DisplayName("FR-18/ADR-22: 레시피 미언급 발화는 recipe=null로 통과한다(추측 금지)")
    void keepsRecipeNullWhenUnmentioned() {
        CapturingLlmClient llm = new CapturingLlmClient();
        // 레시피 언급이 없는 감상 — LLM이 recipe를 null로 반환한다.
        llm.response = new ExtractionResult("예가체프", null, null, null, null, "새콤",
                null, null, null, false, TODAY);

        ExtractionResult result = extractor(llm).extract("예가체프 새콤하고 좋았다", TODAY, List.of());

        assertThat(result.recipe()).isNull();
    }

    @Test
    @DisplayName("AC-Δ5: 스키마·프롬프트가 my_taste(정규화)와 my_taste_original(원문)을 계약으로 선언한다 (changes/0013, ADR-30)")
    void schemaAndPromptDeclareMyTasteNormalizationContract() {
        CapturingLlmClient llm = new CapturingLlmClient();
        llm.response = new ExtractionResult("예가체프", null, null, null, null, "맛있었음", "맛있더라",
                Rating.GOOD, null, null, false, TODAY);

        extractor(llm).extract("예가체프 맛있더라", TODAY, List.of());

        LlmRequest<?> request = llm.captured;
        // 두 필드 모두 required로 선언 + 프롬프트에 정규화·원문 병존 지시.
        assertThat(request.jsonSchema()).contains("my_taste").contains("my_taste_original");
        assertThat(request.systemPrompt()).contains("음슴체").contains("my_taste_original");
    }

    @Test
    @DisplayName("AC-Δ5: 정규화본·원문 두 필드가 함께 역직렬화된다 (changes/0013, V-11)")
    void deserializesMyTasteAndOriginal() {
        String json = """
                {
                  "coffee_name": "예가체프",
                  "roastery": null, "origin": null, "process": null, "roast_level": null,
                  "my_taste": "새콤하고 좋았음", "my_taste_original": "새콤하고 좋았다",
                  "rating": "맛있다", "recipe": null,
                  "matched_slug": null, "references_past": false, "target_date": "2026-07-10"
                }
                """;

        ExtractionResult result = MochaObjectMapper.create().readValue(json, ExtractionResult.class);

        assertThat(result.myTaste()).isEqualTo("새콤하고 좋았음");
        assertThat(result.myTasteOriginal()).isEqualTo("새콤하고 좋았다");
    }

    @Test
    @DisplayName("V-11: LLM이 my_taste_original을 누락하면 정규화본을 복사해 병존시킨다 (changes/0013)")
    void copiesNormalizedWhenOriginalMissing() {
        String json = """
                {
                  "coffee_name": "예가체프",
                  "roastery": null, "origin": null, "process": null, "roast_level": null,
                  "my_taste": "맛있었음", "my_taste_original": null,
                  "rating": null, "recipe": null,
                  "matched_slug": null, "references_past": false, "target_date": "2026-07-10"
                }
                """;

        ExtractionResult result = MochaObjectMapper.create().readValue(json, ExtractionResult.class);

        assertThat(result.myTaste()).isEqualTo("맛있었음");
        assertThat(result.myTasteOriginal()).isEqualTo("맛있었음"); // 원문 누락 → 정규화본 복사(감상 유실 방지)
    }

    @Test
    @DisplayName("V-11: 감상 미언급이면 두 필드 모두 null로 남는다 (복사 규칙은 my_taste 존재 시에만)")
    void keepsBothNullWhenNoTaste() {
        ExtractionResult result = new ExtractionResult("예가체프", null, null, null, null, null, null,
                null, null, null, false, TODAY);

        assertThat(result.myTaste()).isNull();
        assertThat(result.myTasteOriginal()).isNull();
    }

    @Test
    @DisplayName("AC-Δ4: 프롬프트가 고유명사 어미 분리를 지시하고 few-shot 예시를 포함한다 (changes/0013, FR-2)")
    void promptDeclaresProperNounEndingSeparation() {
        CapturingLlmClient llm = new CapturingLlmClient();
        llm.response = new ExtractionResult("예가체프", "카페 화", null, null, null, "맛있었음", "맛있더라",
                Rating.GOOD, null, null, false, TODAY);

        extractor(llm).extract("로스터리는 카페 화고 달고 맛있더라", TODAY, List.of());

        String systemPrompt = llm.captured.systemPrompt();
        // 지시: 고유명사 필드는 조사·연결어미 제거 후 이름만.
        assertThat(systemPrompt).contains("조사").contains("어미");
        // few-shot: 오염 사례("카페 화고" → "카페 화")가 프롬프트에 박혀 있다.
        assertThat(systemPrompt).contains("카페 화고").contains("카페 화");
    }

    @Test
    @DisplayName("FR-18: 스키마·프롬프트가 recipe 3항목(dose_g·water_ml·grind)을 계약으로 강제한다")
    void schemaAndPromptDeclareRecipeContract() {
        CapturingLlmClient llm = new CapturingLlmClient();
        llm.response = new ExtractionResult("예가체프", null, null, null, null, null,
                null, new Recipe(15.0, 240.0, null), null, false, TODAY);

        ExtractionResult result = extractor(llm).extract("원두 15g에 물 240 부어서 마셨어", TODAY, List.of());

        // 계약: 추출기가 반환한 recipe를 그대로 통과시킨다(언급 항목 채움·미언급 항목 null).
        assertThat(result.recipe()).isEqualTo(new Recipe(15.0, 240.0, null));
        // structured output 스키마가 recipe 3항목을 required로 선언해 LLM 응답 구조를 강제한다.
        LlmRequest<?> request = llm.captured;
        assertThat(request.jsonSchema()).contains("dose_g").contains("water_ml").contains("grind");
        assertThat(request.systemPrompt()).contains("recipe");
    }
}
