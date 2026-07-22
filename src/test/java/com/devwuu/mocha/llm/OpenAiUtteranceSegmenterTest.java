package com.devwuu.mocha.llm;

import com.devwuu.mocha.json.MochaObjectMapper;
import com.devwuu.mocha.llm.UtteranceSegmenter.Segment;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFormatTextJsonSchemaConfig;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link OpenAiUtteranceSegmenter} 계약 테스트 — 다중 날짜 세그먼트 분리(ADR-61, data-model §4.3;
 * changes/0023 TΔ3a).
 * <p>LLM 분리 자체는 비결정적이므로 SDK 호출 경계({@code call})를 override해 구조·정책만 검증한다
 * (모듈 CLAUDE.md §5.2·5.3): 응답이 {@link Segment} 목록으로 매핑되는가(날짜 오름차순), 콜 실패·스키마
 * 위반이 <b>예외로 전파</b>되는가 — {@code AliasGenerator}류의 빈 결과 수렴이 아니라 호출부의 분리 안내
 * 폴백(AC-Δ2)을 트리거해야 한다(ADR-61 POLICY).
 */
class OpenAiUtteranceSegmenterTest {

    private static final LocalDate D0718 = LocalDate.of(2026, 7, 18);
    private static final LocalDate D0719 = LocalDate.of(2026, 7, 19);

    @Test
    @DisplayName("ADR-61/§4.3: 분리 성공 시 응답 세그먼트를 날짜 오름차순 Segment 목록으로 매핑한다")
    void mapsResponseSegmentsSortedByDate() {
        // 응답이 늦은 날짜부터 와도 오름차순으로 정렬한다 — 가장 이른 날짜 소비(ADR-61) 지원.
        OpenAiUtteranceSegmenter segmenter = segmenterReturning("""
                {"segments":[
                  {"date":"2026-07-19","text":"19일엔 같은 원두를 브루잉으로"},
                  {"date":"2026-07-18","text":"18일에 와이키키 에스프레소"}]}
                """);

        List<Segment> segments = segmenter.segment("18일에 와이키키 에스프레소, 19일엔 같은 원두를 브루잉으로",
                List.of(D0718, D0719));

        assertThat(segments).containsExactly(
                new Segment(D0718, "18일에 와이키키 에스프레소"),
                new Segment(D0719, "19일엔 같은 원두를 브루잉으로"));
    }

    @Test
    @DisplayName("ADR-61 POLICY: 콜 실패는 빈 결과로 수렴하지 않고 예외로 전파된다(호출부 분리 안내 폴백)")
    void propagatesCallFailure() {
        OpenAiUtteranceSegmenter segmenter =
                new OpenAiUtteranceSegmenter(null, "test-model", MochaObjectMapper.create()) {
                    @Override
                    protected String call(String userPrompt, List<String> isoDates) {
                        throw new IllegalStateException("모델 호출 실패");
                    }
                };

        assertThatThrownBy(() -> segmenter.segment("발화", List.of(D0718, D0719)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("모델 호출 실패");
    }

    @Test
    @DisplayName("ADR-61 POLICY: 비스키마 응답(파싱 불가)도 예외로 전파된다")
    void propagatesMalformedResponse() {
        OpenAiUtteranceSegmenter segmenter = segmenterReturning("날짜별로 나눠 보내주세요!");

        assertThatThrownBy(() -> segmenter.segment("발화", List.of(D0718, D0719)))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    @DisplayName("ADR-61 POLICY: 빈 세그먼트 목록은 스키마 위반 — 예외로 전파된다")
    void rejectsEmptySegments() {
        OpenAiUtteranceSegmenter segmenter = segmenterReturning("{\"segments\":[]}");

        assertThatThrownBy(() -> segmenter.segment("발화", List.of(D0718, D0719)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("세그먼트가 없음");
    }

    @Test
    @DisplayName("V-16 상류 방어: 탐지 집합 밖 날짜 세그먼트는 예외로 전파된다")
    void rejectsDateOutsideDetectedSet() {
        OpenAiUtteranceSegmenter segmenter = segmenterReturning("""
                {"segments":[{"date":"2026-07-20","text":"20일에 마신 커피"}]}
                """);

        assertThatThrownBy(() -> segmenter.segment("발화", List.of(D0718, D0719)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("탐지 집합 밖");
    }

    @Test
    @DisplayName("ADR-61 POLICY: 빈 발췌(text)는 스키마 위반 — 예외로 전파된다")
    void rejectsBlankSegmentText() {
        OpenAiUtteranceSegmenter segmenter = segmenterReturning("""
                {"segments":[{"date":"2026-07-18","text":"  "}]}
                """);

        assertThatThrownBy(() -> segmenter.segment("발화", List.of(D0718, D0719)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("빈 세그먼트 text");
    }

    @Test
    @DisplayName("§4.3: buildParams가 strict JSON schema·date enum(탐지 집합)·전용 모델을 붙인다")
    void buildsParamsWithStrictSchemaAndDateEnum() {
        OpenAiUtteranceSegmenter segmenter =
                new OpenAiUtteranceSegmenter(null, "gpt-5.4-mini", MochaObjectMapper.create());

        ResponseCreateParams params =
                segmenter.buildParams("{}", List.of("2026-07-18", "2026-07-19"));

        assertThat(params.model().orElseThrow().toString()).contains("gpt-5.4-mini");

        ResponseFormatTextJsonSchemaConfig format =
                params.text().orElseThrow().format().orElseThrow().asJsonSchema();
        assertThat(format.strict()).contains(true);
        // date는 탐지 날짜 enum — 집합 밖 날짜 생성을 스키마 레벨에서 차단한다(V-16 상류 방어).
        String properties = format.schema()._additionalProperties().get("properties").toString();
        assertThat(properties).contains("segments").contains("date").contains("text")
                .contains("2026-07-18").contains("2026-07-19");
        assertThat(format.schema()._additionalProperties().get("required").toString())
                .contains("segments");
    }

    @Test
    @DisplayName("ADR-61 POLICY: INSTRUCTIONS가 무편집 발췌·요약/추출 금지(분리만)를 강제한다")
    void instructionsEncodeSplitOnlyPolicy() {
        OpenAiUtteranceSegmenter segmenter =
                new OpenAiUtteranceSegmenter(null, "gpt-5.4-mini", MochaObjectMapper.create());

        String instructions = segmenter.buildParams("{}", List.of("2026-07-18"))
                .instructions().orElseThrow();

        assertThat(instructions).contains("무편집 발췌");
        assertThat(instructions).contains("분리만 한다");
        assertThat(instructions).contains("요약·번역·바꿔 쓰기·필드 추출을 하지 않는다");
        assertThat(instructions).contains("목록에 없는 날짜를 만들지 않는다");
    }

    /** SDK 호출 경계를 canned JSON으로 대체한 어댑터 — 실 API 스모크는 수동 태그(§5.2). */
    private static OpenAiUtteranceSegmenter segmenterReturning(String cannedJson) {
        return new OpenAiUtteranceSegmenter(null, "test-model", MochaObjectMapper.create()) {
            @Override
            protected String call(String userPrompt, List<String> isoDates) {
                return cannedJson;
            }
        };
    }
}
