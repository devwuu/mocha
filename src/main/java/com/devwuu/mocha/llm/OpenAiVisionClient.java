package com.devwuu.mocha.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.openai.client.OpenAIClient;
import com.openai.core.JsonValue;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFormatTextJsonSchemaConfig;
import com.openai.models.responses.ResponseInputContent;
import com.openai.models.responses.ResponseInputImage;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseInputText;
import com.openai.models.responses.ResponseTextConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * OpenAI Responses API vision(이미지 입력) 기반 {@link VisionClient} 구현 (ref: plan.md#ADR-15, NFR-4).
 * <p>OpenAI SDK 타입은 이 클래스 안에만 존재한다(plan §4 POLICY, NFR-4). 상세 이미지 URL을
 * {@code input_image}(detail=high)로 직접 전달하고 — TΔ0 실측에서 모모스(카페24) 기준 핫링크 차단이
 * 없어 URL 직접 전달로 충분함을 확인했다(OQ-Δ1, findings-TΔ0) — {@code text.format}에 strict JSON schema를
 * 붙여 <b>스키마 보장 JSON</b>으로 {@code coffee_name/roastery/beans/roast_level/official_notes}를 받는다
 * (원두 구성은 원두별 요소 — changes/0021 ADR-53).
 * <p>지침에 <b>한국어 기록</b>(영문 표기는 번역)과 <b>추측 금지</b>(이미지에서 확인 안 되는 값은 공란)를
 * 인코딩한다(AC-Δ3, AC-Δ4). {@code sources}는 없다 — 출처 표기는 이 값을 소비하는 상위가 photo 출처로
 * 다룬다(V-5·V-6).
 * <p>호출/형식 실패(예외·비스키마 응답)는 예외로 새지 않고 {@link VisionExtraction#empty()}로 수렴한다 —
 * 2단계의 어떤 실패도 1단계 결과만으로 진행하게 하기 위함이다(AC-Δ2, plan §7).
 */
public class OpenAiVisionClient implements VisionClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiVisionClient.class);

    // POLICY: vision 추출도 "추측 금지" — 이미지에서 확인 안 되는 값은 공란(ADR-15). official_notes는 로스터리
    //         공식 출처(=공식 페이지 이미지) 한정(FR-3, ADR-16).
    // POLICY: coffee_name·roastery는 원문 표기 유지(음차·번역 금지), 그 외 텍스트 필드(beans의 description·
    //         process, roast_level·official_notes)만 한국어 통일 — 4계약 프롬프트 동일 인코딩
    //         (ref: plan.md#ADR-38, spec FR-2/AC-57).
    // POLICY: beans는 원두별 요소 — 블렌드는 구성 원두마다 요소를 만들어 가공방식을 나눠 담고, 품종은
    //         확인되면 description에 포함한다. 에이전트 시스템 프롬프트와 동일 문구 인코딩
    //         (ref: plan.md#ADR-53/#ADR-38, spec FR-2/FR-19/AC-64 — 동일성은 LanguagePolicyParityTest가 가드).
    private static final String INSTRUCTIONS = """
            너는 원두 봉투·카페가 제공하는 커피 노트(카드)·상품 상세 이미지 등 커피 관련 이미지에서 원두 정보를 읽어 구조화하는 도우미다. 아래 규칙을 반드시 지켜라.
            - 이미지에 실제로 적힌 내용만 채운다. 확인되지 않은 값은 추측하지 말고 null(문자열 필드)·빈 배열(리스트)로 둔다.
            - coffee_name·roastery는 이미지의 원문 표기를 그대로 유지한다 — 음차·번역하지 않는다("Ethiopia Chelbesa"는 그대로). 한국어 음차·이표기는 내부에서 따로 만드니 여기서 만들지 마라.
            - beans의 description·process·roast_level·official_notes는 한국어로 기록한다(영문 표기는 한국어로 옮겨 적는다). description의 지명은 한국어 지명으로 통일(영문·한글 혼용 금지: "게데오, Gedeb" ❌ → "게데오"), process·roast_level은 한국어 관용 표기로 옮긴다(예: 가공방식 "워시드/내추럴/허니/무산소", 로스팅 "라이트/미디엄/다크" — 고정 목록 아님).
            - beans에는 원두 구성을 원두별 요소로 담는다 — 요소의 description은 원산지·품종 등을 묶은 자유 텍스트("에티오피아 예가체프 헤어룸"), process는 그 원두의 가공방식이다. 단일 원두도 요소 1개짜리 배열로 담고, 블렌드는 구성 원두마다 요소를 만들어 가공방식이 원두별로 다르면 각 요소에 나눠 담는다(원산지를 한 문자열에 쉼표로 나열하지 않는다). 품종은 확인되면 그 원두의 description에 포함하고, 없으면 생략한다.
            - coffee_name에는 이미지에 표시된 상품(커피) 이름을 담는다. 이름이 보이지 않으면 null로 둔다.
            - official_notes에는 이미지에 표시된 공식 테이스팅 노트만 담고, 없으면 빈 배열로 둔다.
            - 출력은 아래 JSON 객체 하나만, 다른 설명 없이 반환한다:
              {"coffee_name": string|null, "roastery": string|null,
               "beans": [{"description": string, "process": string|null}],
               "roast_level": string|null, "official_notes": string[]}
            """;

    private final OpenAIClient client;
    private final String model;
    private final ObjectMapper mapper;

    /**
     * @param model vision 모델 — 전용 경량 키 {@code mocha.vision.model}(plan §5, ADR-50).
     */
    public OpenAiVisionClient(OpenAIClient client, String model, ObjectMapper mapper) {
        this.client = client;
        this.model = model;
        this.mapper = mapper;
    }

    @Override
    public VisionExtraction read(List<String> imageUrls, VisionHint hint) {
        if (imageUrls == null || imageUrls.isEmpty()) {
            // 상세 이미지 0장 — 2단계 실패 모드 중 하나. 호출 없이 빈 결과로 수렴한다(AC-Δ2).
            log.info("vision OCR 이미지 없음 — 빈 결과로 진행: coffee={}", hint.coffeeName());
            return VisionExtraction.empty();
        }
        String json;
        try {
            json = rawRead(imageUrls, hint);
        } catch (RuntimeException e) {
            // POLICY: vision 호출 실패는 예외로 새지 않고 empty()로 수렴 — 1단계 결과로 진행(ADR-15, AC-Δ2, plan §7).
            log.warn("vision OCR 호출 실패 — 빈 결과로 진행: coffee={}", hint.coffeeName(), e);
            return VisionExtraction.empty();
        }
        return parse(json, hint);
    }

    // 응답(JSON)을 VisionExtraction으로 매핑. 형식 실패(비JSON·파싱 불가)는 빈 결과로 수렴한다(AC-Δ2).
    private VisionExtraction parse(String json, VisionHint hint) {
        String body = extractJsonObject(json);
        if (body == null) {
            log.warn("vision OCR 형식 실패(비JSON 응답) — 빈 결과로 진행: coffee={}", hint.coffeeName());
            return VisionExtraction.empty();
        }
        try {
            VisionPayload payload = mapper.readValue(body, VisionPayload.class);
            return new VisionExtraction(
                    payload.coffeeName(), payload.roastery(), toBeans(payload.beans()),
                    payload.roastLevel(), payload.officialNotes());
        } catch (RuntimeException e) {
            log.warn("vision OCR 형식 실패(파싱 불가) — 빈 결과로 진행: coffee={}", hint.coffeeName(), e);
            return VisionExtraction.empty();
        }
    }

    /**
     * SDK 호출 경계 — Responses API에 이미지 입력 + strict schema로 OCR 요청을 보내고 응답 텍스트를 돌려준다.
     * <p>테스트는 이 메서드를 override해 결정론적 응답으로 대체한다(실 API 스모크는 수동, CLAUDE.md §5.2).
     */
    protected String rawRead(List<String> imageUrls, VisionHint hint) {
        Response response = client.responses().create(buildParams(imageUrls, hint));
        return OpenAiResponseTexts.outputText(response);
    }

    // 파라미터 조립을 분리해 테스트가 SDK 호출 없이 이미지 입력·strict schema·모델 배선을 검사할 수 있게 한다.
    ResponseCreateParams buildParams(List<String> imageUrls, VisionHint hint) {
        List<ResponseInputContent> content = new ArrayList<>();
        content.add(ResponseInputContent.ofInputText(ResponseInputText.builder()
                .text(buildContextText(hint))
                .build()));
        for (String url : imageUrls) {
            content.add(ResponseInputContent.ofInputImage(ResponseInputImage.builder()
                    .imageUrl(url)
                    // 통짜 세로 상세 이미지의 작은 글자를 읽어야 해 detail=high(findings-TΔ0 ③).
                    .detail(ResponseInputImage.Detail.HIGH)
                    .build()));
        }

        return ResponseCreateParams.builder()
                .model(model)
                .inputOfResponse(List.of(ResponseInputItem.ofEasyInputMessage(EasyInputMessage.builder()
                        .role(EasyInputMessage.Role.USER)
                        .contentOfResponseInputMessageContentList(content)
                        .build())))
                .text(ResponseTextConfig.builder()
                        .format(visionSchemaFormat())
                        .build())
                .instructions(INSTRUCTIONS)
                .build();
    }

    // 이미지가 어떤 로스터리·커피의 상세인지 알려 오독을 줄인다 — 잘린 이미지엔 이름이 없을 수 있어서다(findings-TΔ0).
    // 사진-only 흐름(수신 사진 OCR, ADR-23)은 커피명을 모른 채 호출하므로 hint가 비어 있을 수 있다 — 그땐 이름까지 읽으라 지시한다.
    // 이미지 종류를 원두 봉투로 좁히지 않는다 — 카페 제공 커피 노트·상품 상세 등 무엇이든 정보를 읽는다(delta 동기, ADR-23).
    String buildContextText(VisionHint hint) {
        StringBuilder sb = new StringBuilder();
        if (hint.roastery() != null && !hint.roastery().isBlank()) {
            sb.append("로스터리 '").append(hint.roastery().strip()).append("'의 ");
        }
        if (hint.coffeeName() != null && !hint.coffeeName().isBlank()) {
            sb.append("커피 '").append(hint.coffeeName().strip()).append("'에 대한 이미지다. ");
            sb.append("원두 구성(원두별 설명·가공방식)·로스팅 정도·공식 테이스팅 노트를 읽어라.");
        } else {
            sb.append("원두 봉투·카페가 제공하는 커피 노트·상품 상세 등 커피 관련 이미지다. ");
            sb.append("커피 이름·원두 구성(원두별 설명·가공방식)·로스팅 정도·공식 테이스팅 노트를 읽어라.");
        }
        return sb.toString();
    }

    // VisionExtraction 5필드(coffee_name/roastery/beans/roast_level/official_notes)의 strict JSON schema.
    // 문자열류는 미확인 시 null 허용(["string","null"]), beans는 원두별 요소 배열(요소도 strict —
    // description string·process nullable, ADR-53), official_notes는 문자열 배열. 전 필드 required·additionalProperties=false.
    private static ResponseFormatTextJsonSchemaConfig visionSchemaFormat() {
        Map<String, Object> nullableString = Map.of("type", List.of("string", "null"));
        Map<String, Object> stringArray = Map.of("type", "array", "items", Map.of("type", "string"));

        Map<String, Object> beanProperties = new LinkedHashMap<>();
        beanProperties.put("description", Map.of("type", "string"));
        beanProperties.put("process", nullableString);
        Map<String, Object> beanElement = new LinkedHashMap<>();
        beanElement.put("type", "object");
        beanElement.put("properties", beanProperties);
        beanElement.put("required", List.of("description", "process"));
        beanElement.put("additionalProperties", false);

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("coffee_name", nullableString);
        properties.put("roastery", nullableString);
        properties.put("beans", Map.of("type", "array", "items", beanElement));
        properties.put("roast_level", nullableString);
        properties.put("official_notes", stringArray);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of("coffee_name", "roastery", "beans", "roast_level", "official_notes"));
        schema.put("additionalProperties", false);

        ResponseFormatTextJsonSchemaConfig.Schema.Builder builder =
                ResponseFormatTextJsonSchemaConfig.Schema.builder();
        schema.forEach((key, value) -> builder.putAdditionalProperty(key, JsonValue.from(value)));
        return ResponseFormatTextJsonSchemaConfig.builder()
                .name("coffee_vision")
                .strict(true)
                .schema(builder.build())
                .build();
    }

    // 응답에 코드펜스·설명 문구가 섞여도 첫 '{' ~ 마지막 '}' 구간만 취한다. 없으면 null(형식 실패 취급).
    private static String extractJsonObject(String text) {
        if (text == null) {
            return null;
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return text.substring(start, end + 1);
    }

    // 요소 DTO → VisionExtraction.Bean 변환 — 빈 description 드롭 등 위생은 VisionExtraction 생성자가 맡는다.
    private static List<VisionExtraction.Bean> toBeans(List<BeanPayload> beans) {
        if (beans == null) {
            return null;
        }
        return beans.stream()
                .map(b -> b == null ? null : new VisionExtraction.Bean(b.description(), b.process()))
                .toList();
    }

    /** vision 응답 매핑용 관대한 DTO — 알 수 없는 필드는 무시. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record VisionPayload(
            String coffeeName,
            String roastery,
            List<BeanPayload> beans,
            String roastLevel,
            List<String> officialNotes) {
    }

    /** beans 요소 매핑용 관대한 DTO (ADR-53). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BeanPayload(String description, String process) {
    }
}
