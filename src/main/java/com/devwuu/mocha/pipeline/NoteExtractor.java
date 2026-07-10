package com.devwuu.mocha.pipeline;

import com.devwuu.mocha.llm.LlmClient;
import com.devwuu.mocha.llm.LlmException;
import com.devwuu.mocha.llm.LlmRequest;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.List;

/**
 * 파이프라인 [2] — 자연어 메시지에서 고정 필드를 structured output으로 추출한다
 * (ref: plan.md §1 [2], §3 extract(text, candidates); spec FR-2; data-model.md#3, #4).
 * <p>data-model §3 요청(원문·today·existing_notes 후보)을 조립해 {@link LlmClient}로 넘기고,
 * §4 응답을 {@link ExtractionResult}로 받는다. 스키마 강제·재시도·실패 수렴은 LlmClient 계약에 위임한다.
 * <p>POLICY: 언급 없는 필드는 null — 프롬프트로 추측을 금지하고 스키마로 구조를 강제한다
 * (ref: spec FR-2, plan#ADR-6). 출처 마킹·검색 보강은 하지 않는다(이후 단계 몫).
 */
public class NoteExtractor {

    private static final String SCHEMA_NAME = "coffee_note_extraction";

    // structured output 스키마(data-model.md#4). strict 모드 — 전 필드 required, 미언급은 null 허용.
    // rating은 4범주 enum + null만 허용(FR-11, V-1). 최상위 키는 OpenAiLlmClient가 그대로 SDK로 옮긴다.
    private static final String JSON_SCHEMA = """
            {
              "type": "object",
              "additionalProperties": false,
              "required": ["coffee_name","roastery","origin","process","roast_level","my_taste","rating","matched_slug","target_date"],
              "properties": {
                "coffee_name":  {"type": ["string","null"], "description": "표시용 커피 이름. 언급 없으면 null."},
                "roastery":     {"type": ["string","null"], "description": "로스터리. 언급 없으면 null."},
                "origin":       {"type": ["string","null"], "description": "원산지. 사용자가 명시한 경우만. 추측 금지, 검색 보강은 서버가 함."},
                "process":      {"type": ["string","null"], "description": "가공 방식. 사용자 명시분만. 추측 금지."},
                "roast_level":  {"type": ["string","null"], "description": "로스팅 정도. 사용자 명시분만. 추측 금지."},
                "my_taste":     {"type": ["string","null"], "description": "사용자가 느낀 맛. 감상 원문 보존 위주 요약. 언급 없으면 null."},
                "rating":       {"type": ["string","null"], "enum": ["완전 내스타일","맛있다","맛은 있는데 내스타일은 아님","맛이 없다", null], "description": "4범주 중 하나 또는 null(명확한 만족도 언급 없을 때)."},
                "matched_slug": {"type": ["string","null"], "description": "existing_notes 중 같은 커피의 slug. 확신이 없으면 null."},
                "target_date":  {"type": ["string","null"], "description": "YYYY-MM-DD. '어제' 등 상대 날짜를 today 기준으로 해석. 날짜 언급이 없으면 today."}
              }
            }
            """;

    private static final String SYSTEM_PROMPT = """
            너는 커피 감상 메시지에서 정해진 필드만 뽑아내는 추출기다. 아래 규칙을 반드시 지켜라.
            - 사용자가 실제로 말한 것만 채운다. 언급되지 않은 필드는 null로 둔다. 추측하거나 지어내지 않는다.
            - origin/process/roast_level은 사용자가 직접 말한 경우에만 채운다. 모르는 값을 상식으로 채우지 마라(검색 보강은 이후 단계가 한다).
            - my_taste는 감상 표현을 원문 그대로 보존하는 요약으로 담는다. 키워드로 재해석하지 않는다.
            - rating은 만족도 표현이 4범주에 명확히 대응할 때만 고른다. 애매하면 null.
            - matched_slug는 existing_notes에 같은 커피가 있을 때만 그 slug를 넣는다. 없거나 애매하면 null.
            - target_date는 today를 기준으로 '어제', '그저께', '지난 주말' 같은 상대 날짜를 해석한 YYYY-MM-DD다. 날짜 언급이 없으면 today를 그대로 쓴다.
            입력은 message/today/existing_notes를 담은 JSON으로 주어진다.
            """;

    private final LlmClient llmClient;
    private final ObjectMapper mapper;

    public NoteExtractor(LlmClient llmClient, ObjectMapper mapper) {
        this.llmClient = llmClient;
        this.mapper = mapper;
    }

    /**
     * 메시지를 추출 요청으로 조립해 LLM에 넘기고 §4 결과를 돌려준다.
     *
     * @param message    원문 그대로의 사용자 메시지.
     * @param today      서버가 주입하는 오늘 날짜(Asia/Seoul 기준, data-model.md#3).
     * @param candidates 매칭 후보(기존 노트). 없으면 빈 리스트.
     * @throws LlmException 호출 실패, 또는 재시도 소진 후에도 스키마/도메인 위반이 남을 때(plan §7, V-1).
     */
    public ExtractionResult extract(String message, LocalDate today, List<NoteCandidate> candidates) {
        String userPrompt = buildUserPrompt(message, today, candidates);
        LlmRequest<ExtractionResult> request = new LlmRequest<>(
                SCHEMA_NAME, JSON_SCHEMA, SYSTEM_PROMPT, userPrompt, ExtractionResult.class);
        ExtractionResult result = llmClient.complete(request);
        return result.withTargetDateDefault(today);
    }

    // data-model.md#3 요청 스키마를 그대로 사용자 프롬프트로 직렬화(snake_case: message/today/existing_notes).
    private String buildUserPrompt(String message, LocalDate today, List<NoteCandidate> candidates) {
        ExtractionRequest payload = new ExtractionRequest(
                message, today, candidates == null ? List.of() : candidates);
        try {
            return mapper.writeValueAsString(payload);
        } catch (RuntimeException e) {
            throw new LlmException("추출 요청 직렬화 실패", e);
        }
    }

    /** data-model.md#3 요청 스키마 대응 내부 페이로드. existing_notes로 직렬화된다(snake_case). */
    private record ExtractionRequest(String message, LocalDate today, List<NoteCandidate> existingNotes) {
    }
}
