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
              "required": ["coffee_name","roastery","origin","process","roast_level","my_taste","my_taste_original","rating","recipe","matched_slug","references_past","target_date"],
              "properties": {
                "coffee_name":  {"type": ["string","null"], "description": "표시용 커피 이름. 언급 없으면 null."},
                "roastery":     {"type": ["string","null"], "description": "로스터리. 언급 없으면 null."},
                "origin":       {"type": ["string","null"], "description": "원산지. 사용자가 명시한 경우만. 추측 금지, 검색 보강은 서버가 함."},
                "process":      {"type": ["string","null"], "description": "가공 방식. 사용자 명시분만. 추측 금지."},
                "roast_level":  {"type": ["string","null"], "description": "로스팅 정도. 사용자 명시분만. 추측 금지."},
                "my_taste":     {"type": ["string","null"], "description": "사용자가 느낀 맛. 표현·뉘앙스는 보존하되 한국어 음슴체로 정규화. 영어 감상은 한국어로 번역. 언급 없으면 null."},
                "my_taste_original": {"type": ["string","null"], "description": "사용자가 말한 그대로의 감상 표현(언어 불문, 요약·정규화·번역 없이 원문 보존). my_taste가 있으면 반드시 함께 채운다. 감상 언급이 없으면 null."},
                "rating":       {"type": ["string","null"], "enum": ["완전 내스타일","맛있다","맛은 있는데 내스타일은 아님","맛이 없다", null], "description": "4범주 중 하나 또는 null(명확한 만족도 언급 없을 때)."},
                "recipe": {
                  "type": ["object","null"],
                  "additionalProperties": false,
                  "required": ["dose_g","water_ml","grind"],
                  "description": "발화 속 추출(브루잉) 레시피. 레시피 언급이 전혀 없으면 null. 사용자가 말한 항목만 채운다 — 추측 금지.",
                  "properties": {
                    "dose_g":   {"type": ["number","null"], "description": "원두량(g). 사용자가 말한 경우만. 미언급 null."},
                    "water_ml": {"type": ["number","null"], "description": "물량(ml). 사용자가 말한 경우만. 미언급 null."},
                    "grind":    {"type": ["string","null"], "description": "분쇄도(자유 문자열). 사용자가 말한 경우만. 미언급 null."}
                  }
                },
                "matched_slug": {"type": ["string","null"], "description": "existing_notes 중 같은 커피의 slug. 확신이 없으면 null."},
                "references_past": {"type": "boolean", "description": "'저번에', '그때 그', '또 마셨어' 등 기존 기록을 가리키는 참조 표현이 있으면 true. 없으면 false(기본)."},
                "target_date":  {"type": ["string","null"], "description": "YYYY-MM-DD. '어제' 등 상대 날짜를 today 기준으로 해석. 날짜 언급이 없으면 today."}
              }
            }
            """;

    private static final String SYSTEM_PROMPT = """
            너는 커피 감상 메시지에서 정해진 필드만 뽑아내는 추출기다. 아래 규칙을 반드시 지켜라.
            - 사용자가 실제로 말한 것만 채운다. 언급되지 않은 필드는 null로 둔다. 추측하거나 지어내지 않는다.
            - coffee_name·roastery 같은 고유명사 필드는 이름 그 자체만 담는다. 뒤에 붙은 조사·연결어미(는/은/이/가/를/을/고/랑/에서 등)는 잘라내고 이름만 남긴다.
              예: "로스터리는 카페 화고 달고 맛있더라" → roastery "카페 화"(❌ "카페 화고"). "예가체프를 마셨어" → coffee_name "예가체프"(❌ "예가체프를").
            - origin/process/roast_level은 사용자가 직접 말한 경우에만 채운다. 모르는 값을 상식으로 채우지 마라(검색 보강은 이후 단계가 한다).
            - my_taste는 감상을 요약·축약하지 않고, 문장 끝 어미만 한국어 음슴체로 바꿔 담는다. 맛 묘사·수식어·뉘앙스를 하나도 빼지 말고 그대로 보존한다("맛있더라"→"맛있었음", "좋았어"→"좋았음"). 여러 문장이든 긴 감상이든 통째로 "맛있었음" 따위로 줄이면 안 된다.
              예: "산미 있으면서 깔끔한데 적당히 달아서 너무 부담스럽지 않은 맛? 또 먹고 싶더라" → "산미 있으면서 깔끔한데 적당히 달아서 너무 부담스럽지 않은 맛이었음. 또 먹고 싶었음"(❌ "맛있었음"). 영어 감상은 한국어로 옮긴다. 키워드로 재해석하지 않는다(표현 보존 우선).
            - my_taste_original은 사용자가 말한 그대로의 감상 표현을 원문 그대로 담는다(요약·정규화·번역 없이, 원래 언어 그대로). my_taste를 채웠으면 my_taste_original도 반드시 함께 채운다. 감상 언급이 아예 없으면 둘 다 null.
            - rating은 만족도 표현이 4범주에 명확히 대응할 때만 고른다. 만족도 언급이 아예 없으면 null. 아래 매핑을 따른다:
              · "완전 내스타일" — 취향에 딱 맞고 아주 만족("완전 내 취향", "최고", "계속 마시고 싶다").
              · "맛있다" — 맛있고 만족스러움. 취향 불일치 언급은 없음("맛있다", "좋았다").
              · "맛은 있는데 내스타일은 아님" — 맛/품질은 인정하면서도 내 취향과는 어긋남을 함께 말함
                ("맛은 있는데 난 더 단 걸 좋아해", "나쁘진 않은데 내 스타일은 아니야", "괜찮은데 좀 아쉽다").
              · "맛이 없다" — 부정적/불만족("별로", "맛없다", "실망").
              예: "맛은 있는데 나는 좀 더 단맛이 있는 커피를 좋아하는 것 같아" → "맛은 있는데 내스타일은 아님".
              맛/취향에 대한 감상이 있으면 위 4범주 중 가장 가까운 것을 고르고, 정말 판단 불가할 때만 null로 둔다.
            - recipe는 발화에 추출(브루잉) 레시피가 섞여 있을 때만 채운다("원두 15g에 물 240 부어서…" 등). dose_g(원두량 g)·water_ml(물량 ml)·grind(분쇄도) 중 사용자가 말한 항목만 넣고, 말하지 않은 항목은 null로 둔다. 레시피 언급이 아예 없으면 recipe 자체를 null로 둔다. 값을 상식으로 추측하지 마라(레시피는 사용자 발화 전용 — 검색·OCR이 채우지 않는다).
            - matched_slug는 existing_notes에 같은 커피가 있을 때만 그 slug를 넣는다. 없거나 애매하면 null.
            - references_past는 사용자가 "저번에", "그때 그", "또 마셨어"처럼 자신의 기존 기록을 가리키는 참조 표현을 썼을 때만 true다. 그런 표현이 없으면 false. 커피 이름이 익숙해 보인다는 이유만으로 true로 하지 마라 — 발화 속 참조 표현이 근거다.
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
