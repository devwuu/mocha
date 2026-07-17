package com.devwuu.mocha.pipeline;

import com.devwuu.mocha.domain.Aliases;
import com.devwuu.mocha.llm.LlmClient;
import com.devwuu.mocha.llm.LlmRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * 별칭 생성기 — 신규 노트 첫 [저장] 커밋 시 커피명·로스터리의 한국어 음차·이표기를 1콜로 생성한다
 * (ref: plan.md#ADR-37, data-model.md#4.3, spec FR-14; changes/0016).
 * <p>노트당 평생 1회(match=NEW일 때만) 호출된다 — 이후 같은 노트로 매칭된 기록의 관측 표기는 콜 없이
 * 서버가 축적한다(TΔ3, data-model §2.1). 모델은 텍스트 전용 최경량 전용 키({@code mocha.alias.model} —
 * ADR-50, changes/0018).
 * <p>POLICY: 별칭 생성 콜 실패·스키마 위반은 저장을 되돌리지 않는다 — 빈 배열({@link Aliases#empty()})로
 * 수렴하고 노트 저장은 유지한다. 이후 관측 표기 축적이 보완한다 (ref: plan.md §7, V-13, ADR-37).
 */
public class AliasGenerator {

    private static final Logger log = LoggerFactory.getLogger(AliasGenerator.class);

    private static final String SCHEMA_NAME = "coffee_note_aliases";

    // data-model §4.3 응답 스키마 — 한국어 음차·이표기 목록. 원문 표시값 자체는 서버가 이미 알아 중복 수록하지 않는다.
    private static final String JSON_SCHEMA = """
            {
              "type": "object",
              "additionalProperties": false,
              "required": ["coffee_name_aliases","roastery_aliases"],
              "properties": {
                "coffee_name_aliases": {
                  "type": "array",
                  "items": {"type": "string"},
                  "description": "커피명의 한국어 음차·이표기(예: 'Ethiopia Chelbesa' → '에티오피아 첼베사'). 원문 자체는 넣지 않는다. 마땅한 이표기가 없으면 빈 배열."
                },
                "roastery_aliases": {
                  "type": "array",
                  "items": {"type": "string"},
                  "description": "로스터리의 한국어 음차·이표기(예: 'FroB' → '프롭','프로브'). 원문 자체는 넣지 않는다. 마땅한 이표기가 없으면 빈 배열."
                }
              }
            }
            """;

    private static final String SYSTEM_PROMPT = """
            너는 커피 커피명·로스터리의 한국어 음차·이표기를 생성하는 도우미다. 아래 규칙을 지켜라.
            - 입력으로 커피명(coffee_name)과 로스터리(roastery) 원문이 주어진다.
            - 각각에 대해 한국어로 읽었을 때 자연스러운 음차·이표기를 배열로 만든다(예: "Ethiopia Chelbesa" → "에티오피아 첼베사", "FroB" → "프롭","프로브").
            - 원문 표기 그 자체는 별칭에 넣지 않는다 — 서버가 이미 원문을 대조 집합에 포함한다. 음차·이표기만 담는다.
            - 뜻을 번역하지 말고 발음을 한글로 옮긴다. 여러 표기가 가능하면 모두 담는다.
            - 마땅한 이표기가 없으면(이미 한국어이거나 음차가 무의미) 해당 배열은 빈 배열로 둔다. 억지로 지어내지 않는다.
            입력은 coffee_name/roastery를 담은 JSON으로 주어진다.
            """;

    private final LlmClient llmClient;
    private final ObjectMapper mapper;

    public AliasGenerator(LlmClient llmClient, ObjectMapper mapper) {
        this.llmClient = llmClient;
        this.mapper = mapper;
    }

    /**
     * 커피명·로스터리로 별칭을 생성한다 — 신규 노트 첫 커밋 시 1회.
     *
     * @param coffeeName 커밋된 커피명(원문 표시값). null이면 대조 재료가 없으므로 빈 값으로 조립된다.
     * @param roastery   커밋된 로스터리(원문 표시값). null 허용.
     * @return 생성된 {@link Aliases}. 호출·스키마 실패 시 {@link Aliases#empty()}(저장 거부 아님, plan §7).
     */
    public Aliases generate(String coffeeName, String roastery) {
        try {
            String userPrompt = mapper.writeValueAsString(new AliasRequest(coffeeName, roastery));
            LlmRequest<AliasResponse> request = new LlmRequest<>(
                    SCHEMA_NAME, JSON_SCHEMA, SYSTEM_PROMPT, userPrompt, AliasResponse.class);
            AliasResponse response = llmClient.complete(request);
            // 생성 콜에서만 채워지는 음차·이표기 — 원문 표시값은 여기 담지 않는다(V-13, 대조 시 별도 포함).
            return new Aliases(response.coffeeNameAliases(), response.roasteryAliases());
        } catch (RuntimeException e) {
            // POLICY: 생성 실패는 저장을 되돌리지 않는다 — 빈 배열 수렴 + 로그, 관측 축적이 보완(plan §7, ADR-37).
            log.warn("별칭 생성 실패 — 빈 별칭으로 저장(노트 저장 유지): coffeeName={} roastery={}",
                    coffeeName, roastery, e);
            return Aliases.empty();
        }
    }

    /** data-model §4.3 요청 스키마 대응 페이로드 — coffee_name/roastery로 직렬화(snake_case). */
    private record AliasRequest(String coffeeName, String roastery) {
    }

    /**
     * data-model §4.3 응답 스키마 대응 — coffee_name_aliases/roastery_aliases로 역직렬화(snake_case).
     * <p>{@link LlmClient}가 이 타입으로 역직렬화하므로 fake 클라이언트가 구성할 수 있게 공개한다(테스트용, §5.3).
     */
    public record AliasResponse(List<String> coffeeNameAliases, List<String> roasteryAliases) {
    }
}
