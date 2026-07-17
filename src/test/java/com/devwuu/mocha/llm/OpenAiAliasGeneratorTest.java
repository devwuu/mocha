package com.devwuu.mocha.llm;

import com.devwuu.mocha.domain.Aliases;
import com.devwuu.mocha.json.MochaObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link OpenAiAliasGenerator} 계약 테스트 — 신규 노트 첫 커밋 별칭 생성(ADR-37, data-model §4.1;
 * changes/0018 TΔ8b — 구 {@code LlmClient} 경유에서 전용 어댑터로 재배선).
 * <p>LLM 생성 자체는 비결정적이므로 SDK 호출 경계({@code call})를 override해 구조·정책만 검증한다
 * (모듈 CLAUDE.md §5.2·5.3): 응답 필드가 {@link Aliases}로 매핑되는가, 콜 실패가 빈 배열로 수렴하는가(plan §7).
 */
class OpenAiAliasGeneratorTest {

    @Test
    @DisplayName("ADR-37: 생성 성공 시 응답 음차·이표기를 Aliases로 매핑한다")
    void mapsResponseAliases() {
        OpenAiAliasGenerator gen = generatorReturning("""
                {"coffee_name_aliases":["에티오피아 첼베사"],"roastery_aliases":["프롭","프로브"]}
                """);

        Aliases aliases = gen.generate("Ethiopia Chelbesa", "FroB");

        assertThat(aliases.coffeeName()).containsExactly("에티오피아 첼베사");
        assertThat(aliases.roastery()).containsExactly("프롭", "프로브");
    }

    @Test
    @DisplayName("plan §7: 콜 실패는 저장을 되돌리지 않는다 — 빈 별칭으로 수렴")
    void convergesToEmptyOnFailure() {
        OpenAiAliasGenerator gen = new OpenAiAliasGenerator(null, "test-model", MochaObjectMapper.create()) {
            @Override
            protected String call(String userPrompt) {
                throw new IllegalStateException("모델 호출 실패");
            }
        };

        Aliases aliases = gen.generate("Ethiopia Chelbesa", "FroB");

        assertThat(aliases.coffeeName()).isEmpty();
        assertThat(aliases.roastery()).isEmpty();
    }

    @Test
    @DisplayName("plan §7: 비스키마 응답(파싱 불가)도 빈 별칭으로 수렴한다")
    void convergesToEmptyOnMalformedResponse() {
        OpenAiAliasGenerator gen = generatorReturning("별칭은 이런 게 좋겠어요!");

        Aliases aliases = gen.generate("Ethiopia Chelbesa", "FroB");

        assertThat(aliases.coffeeName()).isEmpty();
        assertThat(aliases.roastery()).isEmpty();
    }

    @Test
    @DisplayName("V-13: 정규화 기준 중복은 저장하지 않는다")
    void dedupNormalizedAliases() {
        // 대소문자·공백만 다른 표기는 정규화 기준 중복 — 첫 등장만 남는다(Aliases 계약).
        OpenAiAliasGenerator gen = generatorReturning("""
                {"coffee_name_aliases":["에티오피아 첼베사","에티오피아첼베사"],"roastery_aliases":[]}
                """);

        Aliases aliases = gen.generate("Ethiopia Chelbesa", null);

        assertThat(aliases.coffeeName()).containsExactly("에티오피아 첼베사");
    }

    /** SDK 호출 경계를 canned JSON으로 대체한 어댑터 — 실 API 스모크는 수동 태그(§5.2). */
    private static OpenAiAliasGenerator generatorReturning(String cannedJson) {
        return new OpenAiAliasGenerator(null, "test-model", MochaObjectMapper.create()) {
            @Override
            protected String call(String userPrompt) {
                return cannedJson;
            }
        };
    }
}
