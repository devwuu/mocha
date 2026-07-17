package com.devwuu.mocha.agent.tool;

import com.devwuu.mocha.domain.Recipe;
import com.devwuu.mocha.json.MochaObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TΔ1(changes/0018): tool 인자 타입의 구조 계약 — V-9 구조 차단과 data-model §3 JSON 스키마와의
 * 역직렬화 정합을 단언한다.
 */
class ToolArgsContractTest {

    private final ObjectMapper mapper = MochaObjectMapper.create();

    @Test
    @DisplayName("V-9/AC-38: propose_edit patch 스키마에 coffee_name 필드 자체가 없다 — 커피명 변경의 구조 차단")
    void editPatchHasNoCoffeeNameComponent() {
        // 컴파일 수준 차단의 반사 단언: patch.coffeeName()은 애초에 컴파일되지 않으며,
        // 여기서는 레코드 컴포넌트 목록에 없음을 회귀 가드로 박는다(ADR-45).
        List<String> patchFields = componentNames(ProposeEditArgs.Patch.class);
        assertThat(patchFields).doesNotContain("coffeeName");
        // 대조: 신규 기록 인자에는 coffee_name이 있다 — 이름은 기록 생성 경로로만 들어온다.
        assertThat(componentNames(ProposeRecordArgs.class)).contains("coffeeName");
    }

    @Test
    @DisplayName("data-model §3.3: propose_record 예시 JSON이 snake_case 그대로 인자 타입으로 역직렬화된다")
    void proposeRecordArgsDeserializeFromSpecExample() {
        String json = """
                {
                  "coffee_name":    { "value": "커피베라 예가체프 G1", "source": "user" },
                  "roastery":       { "value": "커피베라", "source": "user" },
                  "origin":         { "value": "에티오피아", "source": "search" },
                  "process":        { "value": null, "source": null },
                  "roast_level":    { "value": null, "source": null },
                  "official_notes": { "value": ["자스민", "베르가못"], "source": "search" },
                  "my_taste": "새콤하고 좋았음",
                  "my_taste_original": "새콤하고 좋았다",
                  "rating": "맛있다",
                  "recipe": { "dose_g": 15, "water_ml": 240, "grind": null },
                  "target_date": "2026-07-16",
                  "match": { "type": "existing", "slug": "2026-07-13-102030", "date": "2026-07-16" },
                  "sources": ["https://frob.co.kr/products/chelbesa"]
                }
                """;
        ProposeRecordArgs args = mapper.readValue(json, ProposeRecordArgs.class);

        assertThat(args.coffeeName()).isEqualTo(new SourcedArg<>("커피베라 예가체프 G1", "user"));
        assertThat(args.origin()).isEqualTo(new SourcedArg<>("에티오피아", "search"));
        assertThat(args.process()).isEqualTo(new SourcedArg<String>(null, null));
        assertThat(args.officialNotes().value()).containsExactly("자스민", "베르가못");
        assertThat(args.myTasteOriginal()).isEqualTo("새콤하고 좋았다");
        assertThat(args.rating()).isEqualTo("맛있다");
        assertThat(args.recipe()).isEqualTo(new Recipe(15.0, 240.0, null));
        assertThat(args.targetDate()).isEqualTo("2026-07-16");
        assertThat(args.match()).isEqualTo(
                new ProposeRecordArgs.MatchArg("existing", "2026-07-13-102030", "2026-07-16"));
        assertThat(args.sources()).containsExactly("https://frob.co.kr/products/chelbesa");
    }

    @Test
    @DisplayName("data-model §3.4: propose_edit 인자(slug+date+patch)가 역직렬화되고 rating 위반도 값으로 도착한다")
    void proposeEditArgsDeserialize() {
        String json = """
                {
                  "slug": "2026-07-13-102030",
                  "date": "2026-07-13",
                  "patch": {
                    "roastery": { "value": "프릳츠", "source": "user" },
                    "origin": null,
                    "process": null,
                    "roast_level": null,
                    "official_notes": null,
                    "my_taste": null,
                    "my_taste_original": null,
                    "rating": "다섯 개 만점",
                    "recipe": null,
                    "new_date": "2026-07-15"
                  }
                }
                """;
        ProposeEditArgs args = mapper.readValue(json, ProposeEditArgs.class);

        assertThat(args.slug()).isEqualTo("2026-07-13-102030");
        assertThat(args.date()).isEqualTo("2026-07-13");
        assertThat(args.patch().roastery()).isEqualTo(new SourcedArg<>("프릳츠", "user"));
        assertThat(args.patch().newDate()).isEqualTo("2026-07-15");
        // V-1 위반 값은 역직렬화 예외가 아니라 String으로 도착한다 — 거부 사유 반환은 ProposalValidator의 몫.
        assertThat(args.patch().rating()).isEqualTo("다섯 개 만점");
    }

    private static List<String> componentNames(Class<?> recordClass) {
        return Arrays.stream(recordClass.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();
    }
}
