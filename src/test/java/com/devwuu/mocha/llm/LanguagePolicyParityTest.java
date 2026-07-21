package com.devwuu.mocha.llm;

import com.devwuu.mocha.agent.prompt.AgentSystemPrompt;
import com.devwuu.mocha.json.MochaObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 언어 정책(ADR-38)의 인코딩 지점 2곳 — 에이전트 시스템 프롬프트와 vision 프롬프트 — 가 <b>동일 문구</b>를
 * 담는지 가드한다 (ref: plan.md#ADR-38 POLICY "한 곳만 고치는 부분 수정 금지", changes/0018 TΔ7a).
 * <p>규칙 문장에서 출처 한정어("이미지의"/"발화·사진의")만 문맥별로 다르고, 그 뒤의 규칙 본문·예시는
 * 두 프롬프트에 자구까지 동일해야 한다 — 한쪽만 고치면 이 테스트가 그쪽에서 깨진다.
 */
class LanguagePolicyParityTest {

    // 고유명사 원문 유지 규칙 — 출처 한정어 뒤의 공통 본문 (FR-2, AC-57).
    private static final String PROPER_NOUN_RULE =
            "원문 표기를 그대로 유지한다 — 음차·번역하지 않는다(\"Ethiopia Chelbesa\"는 그대로). "
                    + "한국어 음차·이표기는 내부에서 따로 만드니 여기서 만들지 마라.";

    // 한국어 통일 규칙 — 문장 전체가 두 프롬프트에 그대로 실린다(changes/0021 — 담기는 필드가 beans로 이동, 규칙 내용 불변).
    private static final String KOREAN_FIELD_RULE =
            "beans의 description·process·roast_level·official_notes는 한국어로 기록한다(영문 표기는 한국어로 옮겨 적는다). "
                    + "description의 지명은 한국어 지명으로 통일(영문·한글 혼용 금지: \"게데오, Gedeb\" ❌ → \"게데오\"), "
                    + "process·roast_level은 한국어 관용 표기로 옮긴다(예: 가공방식 \"워시드/내추럴/허니/무산소\", "
                    + "로스팅 \"라이트/미디엄/다크\" — 고정 목록 아님).";

    // beans 원두별 구성 규칙(ADR-53) — 블렌드 원두별 요소·쉼표 나열 금지·품종은 있으면 포함. 문장 전체 동일.
    private static final String BEANS_RULE =
            "beans에는 원두 구성을 원두별 요소로 담는다 — 요소의 description은 원산지·품종 등을 묶은 자유 텍스트(\"에티오피아 예가체프 헤어룸\"), "
                    + "process는 그 원두의 가공방식이다. 단일 원두도 요소 1개짜리 배열로 담고, "
                    + "블렌드는 구성 원두마다 요소를 만들어 가공방식이 원두별로 다르면 각 요소에 나눠 담는다(원산지를 한 문자열에 쉼표로 나열하지 않는다). "
                    + "품종은 확인되면 그 원두의 description에 포함하고, 없으면 생략한다.";

    private static String visionInstructions() {
        OpenAiVisionClient client = new OpenAiVisionClient(null, "test-model", MochaObjectMapper.create());
        return client.buildParams(List.of("https://example.com/bag.jpg"), new VisionHint(null, null))
                .instructions().orElseThrow();
    }

    @Test
    @DisplayName("ADR-38: 고유명사 원문 유지 규칙이 에이전트·vision 프롬프트에 동일 문구로 실린다")
    void properNounRuleIsEncodedIdentically() {
        assertThat(AgentSystemPrompt.INSTRUCTIONS).contains(PROPER_NOUN_RULE);
        assertThat(visionInstructions()).contains(PROPER_NOUN_RULE);
    }

    @Test
    @DisplayName("ADR-38: 한국어 통일 규칙(지명·관용 표기 예시 포함)이 에이전트·vision 프롬프트에 동일 문구로 실린다")
    void koreanFieldRuleIsEncodedIdentically() {
        assertThat(AgentSystemPrompt.INSTRUCTIONS).contains(KOREAN_FIELD_RULE);
        assertThat(visionInstructions()).contains(KOREAN_FIELD_RULE);
    }

    @Test
    @DisplayName("ADR-53/AC-64: beans 원두별 구성 규칙(블렌드 원두별 요소·품종 포함)이 에이전트·vision 프롬프트에 동일 문구로 실린다")
    void beansRuleIsEncodedIdentically() {
        assertThat(AgentSystemPrompt.INSTRUCTIONS).contains(BEANS_RULE);
        assertThat(visionInstructions()).contains(BEANS_RULE);
    }
}
