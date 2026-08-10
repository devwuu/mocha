package com.devwuu.mocha.agent.tool;

import com.devwuu.mocha.agent.turn.TurnProposalSink;
import com.devwuu.mocha.agent.turn.TurnUserMessage;
import com.devwuu.mocha.domain.Recipe;
import com.devwuu.mocha.json.MochaObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TΔ1(changes/0018) · TΔ2a(changes/0021): tool 인자 타입의 구조 계약 — data-model §3 JSON
 * 스키마(beans·cups 개정형)와의 역직렬화 정합을 단언한다.
 * <p>changes/0029 TΔ1에서 {@code propose_edit} 계약 케이스 2건(V-9 patch 구조 차단·patch 역직렬화)이
 * tool 폐기와 함께 사라졌다. <b>V-9(커피명 불변) 자체는 살아 있다</b> — 수정 경로가 UI로 옮겨졌으므로
 * 가드도 노트 수정 API(TΔ5)·수정 화면(TΔ13)에서 다시 세운다.
 */
class ToolArgsContractTest {

    private final ObjectMapper mapper = MochaObjectMapper.create();

    @Test
    @DisplayName("changes/0021: 인자 스키마에서 구 단일 필드(origin/process/my_taste/rating/recipe)가 제거됐다 — beans·cups로 대체")
    void legacySingleFieldsRemovedFromArgs() {
        assertThat(componentNames(ProposeRecordArgs.class))
                .contains("coffeeName", "beans", "cups")
                .doesNotContain("origin", "process", "myTaste", "myTasteOriginal", "rating", "recipe");
    }

    @Test
    @DisplayName("data-model §3.3: propose_record 예시 JSON(beans·cups)이 snake_case 그대로 인자 타입으로 역직렬화된다")
    void proposeRecordArgsDeserializeFromSpecExample() {
        String json = """
                {
                  "coffee_name":    { "value": "커피베라 예가체프 G1", "source": "user" },
                  "roastery":       { "value": "커피베라", "source": "user" },
                  "beans": [
                    { "description": { "value": "에티오피아 예가체프", "source": "user" },
                      "process":     { "value": "워시드", "source": "search" } }
                  ],
                  "roast_level":    { "value": null, "source": null },
                  "official_notes": { "value": ["자스민", "베르가못"], "source": "search" },
                  "cups": [
                    { "recipe": { "method": "핸드드립", "dose_g": 15, "water_ml": 240, "yield_ml": null,
                                  "time_sec": 160, "temp_c": 92, "grind": 210,
                                  "grinder": "매버릭 2.0", "detail": "뜸 40ml 30초 → 100ml → 100ml",
                                  "feedback": "첫 모금이 살짝 떫었으니 다음엔 220클릭으로" },
                      "review": { "my_taste": "새콤하고 좋았음",
                                   "my_taste_original": "새콤하고 좋았다",
                                   "rating": "맛있다" } }
                  ],
                  "target_date": "2026-07-16",
                  "match": { "type": "existing", "note_id": 12, "date": "2026-07-16" },
                  "sources": ["https://frob.co.kr/products/chelbesa"]
                }
                """;
        ProposeRecordArgs args = mapper.readValue(json, ProposeRecordArgs.class);

        assertThat(args.coffeeName()).isEqualTo(new SourcedArg<>("커피베라 예가체프 G1", "user"));
        assertThat(args.beans()).containsExactly(new BeanArg(
                new SourcedArg<>("에티오피아 예가체프", "user"), new SourcedArg<>("워시드", "search")));
        assertThat(args.officialNotes().value()).containsExactly("자스민", "베르가못");
        assertThat(args.cups()).hasSize(1);
        assertThat(args.cups().getFirst().recipe()).isEqualTo(new Recipe(
                "핸드드립", 15.0, 240.0, null, 160.0, 92.0, 210.0, "매버릭 2.0",
                "뜸 40ml 30초 → 100ml → 100ml", "첫 모금이 살짝 떫었으니 다음엔 220클릭으로"));
        assertThat(args.cups().getFirst().review()).isEqualTo(
                new CupArg.ReviewArg("새콤하고 좋았음", "새콤하고 좋았다", "맛있다"));
        assertThat(args.targetDate()).isEqualTo("2026-07-16");
        // note_id는 스키마상 정수지만 인자 record는 원시 String으로 받는다 — 위반 값(비숫자)이 역직렬화
        // 예외로 새지 않고 거부 사유로 돌아가게 하는 계약이다(changes/0028 TΔ0b §4 E-6).
        assertThat(args.match()).isEqualTo(
                new ProposeRecordArgs.MatchArg("existing", "12", "2026-07-16"));
        assertThat(args.sources()).containsExactly("https://frob.co.kr/products/chelbesa");
    }

    @Test
    @DisplayName("changes/0030 TΔ12: propose_record의 recipe 스키마 = 도메인 Recipe의 와이어 형태 — grind는 number, grinder·detail이 machine·pouring 자리를 대신한다")
    void recipeSchemaMatchesTheRecipeWireShape() {
        JsonNode recipe = proposeRecordSchema()
                .get("properties").get("cups").get("items").get("properties").get("recipe");

        // 기대값을 손으로 나열하지 않고 «record를 이 매퍼로 직렬화한 결과»에서 뽑는다 — 모델이 실제로 채워
        // 보내야 하는 키가 그것이고, 도메인이 다시 바뀌면 목록을 고치지 않아도 이 단언이 따라 움직인다.
        List<String> wireFields = fieldNames(mapper.valueToTree(
                new Recipe(null, null, null, null, null, null, null, null, null, null)));

        // containsExactly = 부재(machine·pouring) + 존재(grinder·detail) + 순서를 한 번에 문다. strict
        // 계약이라 required도 전 필드여야 한다 — 스키마가 없는 필드를 요구하면 모델 응답이 통째로 막힌다.
        assertThat(fieldNames(recipe.get("properties"))).containsExactlyElementsOf(wireFields);
        assertThat(stringValues(recipe.get("required"))).containsExactlyElementsOf(wireFields);
        assertThat(recipe.get("additionalProperties").asBoolean()).isFalse();

        // ⚠️ grind는 이름이 그대로라 키 단언이 재편을 못 잡는다 — 타입을 따로 문다(TΔ11 ClientApiContractTest와
        // 같은 구멍). 텍스트인 채로 남았다면 위 세 단언은 전부 그린이고 V-8 수치 규칙만 조용히 어긋난다.
        assertThat(stringValues(recipe.get("properties").get("grind").get("type")))
                .containsExactly("number", "null");
        assertThat(stringValues(recipe.get("properties").get("grinder").get("type")))
                .containsExactly("string", "null");
        assertThat(stringValues(recipe.get("properties").get("detail").get("type")))
                .containsExactly("string", "null");
    }

    /**
     * 장착된 {@code propose_record}의 인자 스키마 — 턴별 인자(userId·발화·draft·수거함)는 executor
     * 클로저에만 쓰이고 정의에는 영향이 없어 더미로 충분하다(계약 스냅샷 테스트와 같은 방식).
     */
    private JsonNode proposeRecordSchema() {
        List<ToolCallback> tools = ToolCallbackProviderFixture.toolkit().build()
                .forTurn("U-contract", new TurnUserMessage("계약", null), null, new TurnProposalSink());
        ToolCallback proposeRecord = tools.stream()
                .filter(tool -> tool.name().equals("propose_record"))
                .findFirst().orElseThrow();
        return mapper.readTree(proposeRecord.parametersSchema());
    }

    private static List<String> fieldNames(JsonNode object) {
        return new ArrayList<>(object.propertyNames());
    }

    private static List<String> stringValues(JsonNode array) {
        return array.valueStream().map(JsonNode::asString).toList();
    }

    private static List<String> componentNames(Class<?> recordClass) {
        return Arrays.stream(recordClass.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }
}
