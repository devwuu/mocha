package com.devwuu.mocha.pipeline;

import com.devwuu.mocha.domain.Aliases;
import com.devwuu.mocha.json.MochaObjectMapper;
import com.devwuu.mocha.llm.LlmClient;
import com.devwuu.mocha.llm.LlmException;
import com.devwuu.mocha.llm.LlmRequest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link AliasGenerator} 계약 테스트 — 신규 노트 첫 커밋 별칭 생성(TΔ2, ADR-37, data-model §4.3).
 * <p>LLM 생성 자체는 비결정적이므로 fake로 대체해 구조·정책만 검증한다(모듈 CLAUDE.md §5.3):
 * 응답 필드가 {@link Aliases}로 매핑되는가, 콜 실패가 빈 배열로 수렴하는가(plan §7).
 */
class AliasGeneratorTest {

    private final AliasGenerator generator =
            new AliasGenerator(new FakeLlmClient(), MochaObjectMapper.create());

    @Test
    @DisplayName("TΔ2: 생성 성공 시 응답 음차·이표기를 Aliases로 매핑한다")
    void mapsResponseAliases() {
        FakeLlmClient fake = new FakeLlmClient();
        fake.canned = new AliasGenerator.AliasResponse(
                List.of("에티오피아 첼베사"), List.of("프롭", "프로브"));
        AliasGenerator gen = new AliasGenerator(fake, MochaObjectMapper.create());

        Aliases aliases = gen.generate("Ethiopia Chelbesa", "FroB");

        assertThat(aliases.coffeeName()).containsExactly("에티오피아 첼베사");
        assertThat(aliases.roastery()).containsExactly("프롭", "프로브");
    }

    @Test
    @DisplayName("TΔ2: 콜 실패는 저장을 되돌리지 않는다 — 빈 별칭으로 수렴(plan §7)")
    void convergesToEmptyOnFailure() {
        FakeLlmClient fake = new FakeLlmClient();
        fake.failure = new LlmException("모델 호출 실패");
        AliasGenerator gen = new AliasGenerator(fake, MochaObjectMapper.create());

        Aliases aliases = gen.generate("Ethiopia Chelbesa", "FroB");

        assertThat(aliases.coffeeName()).isEmpty();
        assertThat(aliases.roastery()).isEmpty();
    }

    @Test
    @DisplayName("TΔ2: 정규화 기준 중복은 저장하지 않는다(V-13)")
    void dedupNormalizedAliases() {
        FakeLlmClient fake = new FakeLlmClient();
        // 대소문자·공백만 다른 표기는 정규화 기준 중복 — 첫 등장만 남는다(Aliases 계약).
        fake.canned = new AliasGenerator.AliasResponse(
                List.of("에티오피아 첼베사", "에티오피아첼베사"), List.of());
        AliasGenerator gen = new AliasGenerator(fake, MochaObjectMapper.create());

        Aliases aliases = gen.generate("Ethiopia Chelbesa", null);

        assertThat(aliases.coffeeName()).containsExactly("에티오피아 첼베사");
    }

    /** 응답 타입에 맞춰 canned 별칭을 돌려주는 fake — 실패 주입 시 예외를 던진다(§5.3). */
    private static final class FakeLlmClient implements LlmClient {
        AliasGenerator.AliasResponse canned = new AliasGenerator.AliasResponse(List.of(), List.of());
        RuntimeException failure;

        @SuppressWarnings("unchecked")
        @Override
        public <T> T complete(LlmRequest<T> request) {
            if (failure != null) {
                throw failure;
            }
            return (T) canned;
        }
    }
}
