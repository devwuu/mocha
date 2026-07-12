package com.devwuu.mocha.llm;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.devwuu.mocha.json.MochaObjectMapper;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFormatTextJsonSchemaConfig;
import com.openai.models.responses.ResponseInputContent;
import com.openai.models.responses.ResponseInputItem;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OpenAiVisionClient의 계약 검증 — 이미지 입력·strict schema·모델 배선(AC-Δ3), 응답 텍스트(JSON) →
 * VisionExtraction 매핑, 호출/형식 실패의 빈 결과 수렴(AC-Δ2)을 SDK 호출 없이 결정론적으로 확인한다
 * (ref: CLAUDE.md §5.2 외부 연동 어댑터, 실 API 스모크는 수동; plan.md#ADR-15).
 */
class OpenAiVisionClientTest {

    /** {@code rawRead} 시임을 미리 준비한 응답 텍스트로 대체하는 스텁. SDK 클라이언트는 쓰지 않는다. */
    static class StubVisionClient extends OpenAiVisionClient {
        private final String response;
        private final RuntimeException toThrow;

        StubVisionClient(String response) {
            super(null, "test-model", MochaObjectMapper.create());
            this.response = response;
            this.toThrow = null;
        }

        StubVisionClient(RuntimeException toThrow) {
            super(null, "test-model", MochaObjectMapper.create());
            this.response = null;
            this.toThrow = toThrow;
        }

        @Override
        protected String rawRead(List<String> imageUrls, VisionHint hint) {
            if (toThrow != null) {
                throw toThrow;
            }
            return response;
        }
    }

    private static VisionHint hint() {
        return new VisionHint("와이키키", "모모스 커피");
    }

    private static List<String> images() {
        return List.of(
                "https://momos.co.kr/web/upload/a.jpg",
                "https://momos.co.kr/web/upload/b.jpg");
    }

    private ListAppender<ILoggingEvent> logs;
    private Logger clientLogger;

    @BeforeEach
    void attachLogAppender() {
        clientLogger = (Logger) LoggerFactory.getLogger(OpenAiVisionClient.class);
        logs = new ListAppender<>();
        logs.start();
        clientLogger.addAppender(logs);
    }

    @AfterEach
    void detachLogAppender() {
        clientLogger.detachAppender(logs);
    }

    @Test
    @DisplayName("AC-Δ3: buildParams가 이미지 입력·strict JSON schema·공용 모델을 붙인다")
    void buildsParamsWithImageInputsAndStrictSchema() {
        OpenAiVisionClient client =
                new OpenAiVisionClient(null, "gpt-4o", MochaObjectMapper.create());

        ResponseCreateParams params = client.buildParams(images(), hint());

        // vision 모델은 검색 보강과 공용(mocha.search.model).
        assertThat(params.model().orElseThrow().toString()).contains("gpt-4o");

        // 이미지 URL 2장이 input_image로, 문맥 텍스트가 input_text로 담긴다(텍스트 1 + 이미지 2 = 3).
        ResponseInputItem item = params.input().orElseThrow().asResponse().get(0);
        List<ResponseInputContent> content =
                item.asEasyInputMessage().content().asResponseInputMessageContentList();
        assertThat(content).hasSize(3);
        assertThat(content.get(0).isInputText()).isTrue();
        assertThat(content).filteredOn(ResponseInputContent::isInputImage).hasSize(2);
        assertThat(content.get(1).asInputImage().imageUrl())
                .contains("https://momos.co.kr/web/upload/a.jpg");

        // text.format에 strict JSON schema가 붙고, VisionExtraction 5필드를 요구한다.
        ResponseFormatTextJsonSchemaConfig format =
                params.text().orElseThrow().format().orElseThrow().asJsonSchema();
        assertThat(format.strict()).contains(true);
        assertThat(format.schema()._additionalProperties())
                .containsKeys("type", "properties", "required", "additionalProperties");
    }

    @Test
    @DisplayName("AC-Δ3/AC-Δ4: INSTRUCTIONS에 한국어 기록·추측 금지 규칙이 있다")
    void instructionsEncodeKoreanAndNoGuessing() {
        OpenAiVisionClient client =
                new OpenAiVisionClient(null, "gpt-4o", MochaObjectMapper.create());

        String instructions = client.buildParams(images(), hint()).instructions().orElseThrow();

        // AC-Δ4: 모든 값 한국어 기록 규칙.
        assertThat(instructions).contains("한국어로 기록");
        // AC-Δ3(추측 금지): 이미지에서 확인 안 되는 값은 공란.
        assertThat(instructions).contains("추측하지 말고");
        assertThat(instructions).contains("이미지에 실제로 적힌 내용만");
    }

    @Test
    @DisplayName("문맥 힌트(로스터리·커피명)가 이미지 입력 앞 텍스트에 담긴다")
    void contextTextCarriesHint() {
        OpenAiVisionClient client =
                new OpenAiVisionClient(null, "gpt-4o", MochaObjectMapper.create());

        String text = client.buildContextText(hint());
        assertThat(text).contains("모모스 커피").contains("와이키키");

        // 로스터리 null이면 커피명만 담는다.
        assertThat(client.buildContextText(new VisionHint("예가체프", null)))
                .contains("예가체프")
                .doesNotContain("로스터리 '");
    }

    @Test
    @DisplayName("응답 JSON이 snake_case 필드로 VisionExtraction에 매핑된다(coffee_name 포함)")
    void mapsJsonResponse() {
        String json = """
                {"coffee_name": "와이키키", "roastery": null, "origin": "에티오피아, 에콰도르", "process": null,
                 "roast_level": "미디엄 라이트", "official_notes": ["패션프루트","베르가못"]}
                """;

        VisionExtraction result = new StubVisionClient(json).read(images(), hint());

        assertThat(result.coffeeName()).isEqualTo("와이키키");
        assertThat(result.origin()).isEqualTo("에티오피아, 에콰도르");
        assertThat(result.process()).isNull();
        assertThat(result.roastLevel()).isEqualTo("미디엄 라이트");
        assertThat(result.officialNotes()).containsExactly("패션프루트", "베르가못");
    }

    @Test
    @DisplayName("changes/0010: strict schema에 coffee_name 필드가 required로 포함된다")
    void schemaIncludesCoffeeName() {
        OpenAiVisionClient client =
                new OpenAiVisionClient(null, "gpt-4o", MochaObjectMapper.create());

        ResponseFormatTextJsonSchemaConfig format =
                client.buildParams(images(), hint()).text().orElseThrow().format().orElseThrow().asJsonSchema();

        Object properties = format.schema()._additionalProperties().get("properties");
        assertThat(properties.toString()).contains("coffee_name");
        assertThat(format.schema()._additionalProperties().get("required").toString()).contains("coffee_name");
    }

    @Test
    @DisplayName("ADR-23: 커피명 힌트가 없으면(사진-only) 이름까지 읽으라 지시한다")
    void contextTextReadsCoffeeNameWhenHintMissing() {
        OpenAiVisionClient client =
                new OpenAiVisionClient(null, "gpt-4o", MochaObjectMapper.create());

        String text = client.buildContextText(new VisionHint(null, null));

        assertThat(text).contains("커피 이름");
        assertThat(text).doesNotContain("커피 '");
    }

    @Test
    @DisplayName("코드펜스·설명이 섞인 응답에서도 JSON 객체 구간만 추출해 매핑한다")
    void extractsJsonFromNoisyResponse() {
        String noisy = """
                이미지에서 읽은 정보입니다:
                ```json
                {"roastery": null, "origin": "콜롬비아", "process": "워시드", "roast_level": null,
                 "official_notes": []}
                ```
                """;

        VisionExtraction result = new StubVisionClient(noisy).read(images(), hint());

        assertThat(result.origin()).isEqualTo("콜롬비아");
        assertThat(result.process()).isEqualTo("워시드");
        assertThat(result.officialNotes()).isEmpty();
    }

    @Test
    @DisplayName("AC-Δ2: 이미지 0장이면 호출 없이 빈 결과로 수렴한다")
    void noImagesYieldsEmpty() {
        VisionExtraction result =
                new StubVisionClient("절대 호출되면 안 됨").read(List.of(), hint());

        assertThat(result).isEqualTo(VisionExtraction.empty());
    }

    @Test
    @DisplayName("AC-Δ2: JSON이 없는 응답(형식 실패)은 빈 결과로 수렴한다")
    void noJsonYieldsEmpty() {
        VisionExtraction result =
                new StubVisionClient("이미지를 읽지 못했습니다.").read(images(), hint());

        assertThat(result).isEqualTo(VisionExtraction.empty());
        assertThat(logs.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getFormattedMessage()).contains("형식 실패");
        });
    }

    @Test
    @DisplayName("AC-Δ2: 파싱 불가한 깨진 JSON도 빈 결과로 수렴한다")
    void brokenJsonYieldsEmpty() {
        VisionExtraction result =
                new StubVisionClient("{\"origin\": ").read(images(), hint());

        assertThat(result).isEqualTo(VisionExtraction.empty());
    }

    @Test
    @DisplayName("AC-Δ2: vision 호출이 예외를 던져도 빈 결과로 수렴한다(예외 미전파)")
    void callFailureYieldsEmpty() {
        VisionExtraction result =
                new StubVisionClient(new RuntimeException("timeout")).read(images(), hint());

        assertThat(result).isEqualTo(VisionExtraction.empty());
        assertThat(logs.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getFormattedMessage()).contains("호출 실패");
        });
    }
}
