package com.devwuu.mocha.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.openai.client.OpenAIClient;
import com.openai.core.JsonValue;
import com.openai.models.responses.EasyInputMessage;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFormatTextJsonSchemaConfig;
import com.openai.models.responses.ResponseInputItem;
import com.openai.models.responses.ResponseTextConfig;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * OpenAI Responses API structured output 기반 {@link UtteranceSegmenter} 구현
 * (ref: plan.md#ADR-61, data-model.md#4.3; changes/0023 TΔ3a).
 * <p>OpenAI SDK 타입은 이 클래스 안에만 존재한다(plan §4 POLICY, NFR-4 — {@code OpenAiAliasGenerator}와
 * 동일 규칙). {@code text.format}에 strict JSON schema를 붙이되, {@code date} 필드는 <b>탐지 날짜 집합의
 * enum</b>으로 호출마다 조립한다 — 집합 밖 날짜 생성은 스키마 레벨에서 차단된다(V-16 게이트의 상류 방어).
 * <p>POLICY: 세그먼터는 분리만 — 필드 추출·요약·번역 금지. 콜 실패·스키마 위반은 {@code AliasGenerator}처럼
 * 빈 결과로 수렴하지 않고 <b>예외로 전파</b>한다 — 호출부가 분리 안내 폴백을 선택해야 하기 때문이다
 * (ref: plan.md#ADR-61 POLICY, §7).
 */
public class OpenAiUtteranceSegmenter implements UtteranceSegmenter {

    private static final String SCHEMA_NAME = "utterance_segments";

    private static final String INSTRUCTIONS = """
            너는 커피 시음 기록 발화를 시음 날짜별 세그먼트로 분리하는 도우미다. 아래 규칙을 지켜라.
            - 입력은 사용자 원문(utterance)과 원문에서 탐지된 시음 날짜 목록(dates, YYYY-MM-DD)을 담은 JSON이다.
            - 원문을 날짜별로 나눠 segments에 담는다. 각 세그먼트의 text는 원문 중 그 날짜에 귀속되는 부분의 무편집 발췌다 — 원문 어구를 그대로 옮긴다.
            - 분리만 한다: 요약·번역·바꿔 쓰기·필드 추출을 하지 않는다. 원문에 없는 문구를 만들지 않는다.
            - 여러 날짜에 공통으로 걸리는 서술(커피 이름·로스터리 등)은 해당하는 각 세그먼트에 원문 그대로 반복 포함해도 된다.
            - date는 dates 목록의 값만 쓴다. 목록에 없는 날짜를 만들지 않는다.
            """;

    private final OpenAIClient client;
    private final String model;
    private final ObjectMapper mapper;

    public OpenAiUtteranceSegmenter(OpenAIClient client, String model, ObjectMapper mapper) {
        this.client = client;
        this.model = model;
        this.mapper = mapper;
    }

    @Override
    public List<Segment> segment(String utterance, Collection<LocalDate> dates) {
        // POLICY: 실패·스키마 위반은 예외로 전파 — 호출부가 분리 안내 폴백(ADR-61). 빈 결과 수렴 금지.
        List<String> isoDates = dates.stream().distinct().sorted().map(LocalDate::toString).toList();
        String userPrompt = mapper.writeValueAsString(new SegmentRequest(utterance, isoDates));
        SegmentsPayload payload = mapper.readValue(call(userPrompt, isoDates), SegmentsPayload.class);
        return toSegments(payload, Set.copyOf(isoDates));
    }

    /**
     * SDK 호출 경계 — Responses API에 strict schema로 세그먼트 분리를 요청하고 응답 텍스트를 돌려준다.
     * <p>테스트는 이 메서드를 override해 결정론적 응답으로 대체한다(실 API 스모크는 수동, CLAUDE.md §5.2).
     */
    protected String call(String userPrompt, List<String> isoDates) {
        Response response = client.responses().create(buildParams(userPrompt, isoDates));
        return OpenAiResponseTexts.outputText(response);
    }

    // 파라미터 조립을 분리해 테스트가 SDK 호출 없이 strict schema·date enum·모델 배선을 검사할 수 있게 한다.
    ResponseCreateParams buildParams(String userPrompt, List<String> isoDates) {
        return ResponseCreateParams.builder()
                .model(model)
                .inputOfResponse(List.of(ResponseInputItem.ofEasyInputMessage(EasyInputMessage.builder()
                        .role(EasyInputMessage.Role.USER)
                        .content(userPrompt)
                        .build())))
                .text(ResponseTextConfig.builder()
                        .format(segmentSchemaFormat(isoDates))
                        .build())
                .instructions(INSTRUCTIONS)
                .build();
    }

    // data-model §4.3 응답 스키마 — 날짜별 세그먼트 배열. date는 탐지 날짜 enum(집합 밖 날짜 스키마 차단),
    // 전 필드 required·additionalProperties=false.
    private static ResponseFormatTextJsonSchemaConfig segmentSchemaFormat(List<String> isoDates) {
        Map<String, Object> segmentProperties = new LinkedHashMap<>();
        segmentProperties.put("date", Map.of("type", "string", "enum", isoDates));
        segmentProperties.put("text", Map.of("type", "string"));

        Map<String, Object> segmentItem = new LinkedHashMap<>();
        segmentItem.put("type", "object");
        segmentItem.put("properties", segmentProperties);
        segmentItem.put("required", List.of("date", "text"));
        segmentItem.put("additionalProperties", false);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of("segments", Map.of("type", "array", "items", segmentItem)));
        schema.put("required", List.of("segments"));
        schema.put("additionalProperties", false);

        ResponseFormatTextJsonSchemaConfig.Schema.Builder builder =
                ResponseFormatTextJsonSchemaConfig.Schema.builder();
        schema.forEach((key, value) -> builder.putAdditionalProperty(key, JsonValue.from(value)));
        return ResponseFormatTextJsonSchemaConfig.builder()
                .name(SCHEMA_NAME)
                .strict(true)
                .schema(builder.build())
                .build();
    }

    // 응답 검증 — strict schema를 뚫고 온 위반(빈 목록·빈 발췌·집합 밖 날짜)은 예외로 알린다(폴백 대상).
    // 날짜 오름차순으로 정렬해 "가장 이른 날짜" 소비(ADR-61)를 바로 지원한다.
    private static List<Segment> toSegments(SegmentsPayload payload, Set<String> allowedDates) {
        if (payload.segments() == null || payload.segments().isEmpty()) {
            throw new IllegalStateException("세그먼터 응답에 세그먼트가 없음 — 스키마 위반(분리 안내 폴백 대상, ADR-61)");
        }
        return payload.segments().stream()
                .map(raw -> {
                    if (raw.text() == null || raw.text().isBlank()) {
                        throw new IllegalStateException(
                                "빈 세그먼트 text — 스키마 위반(분리 안내 폴백 대상, ADR-61): date=" + raw.date());
                    }
                    if (raw.date() == null || !allowedDates.contains(raw.date())) {
                        throw new IllegalStateException(
                                "탐지 집합 밖 세그먼트 날짜 — 스키마 위반(분리 안내 폴백 대상, ADR-61): date=" + raw.date());
                    }
                    return new Segment(LocalDate.parse(raw.date()), raw.text());
                })
                .sorted(Comparator.comparing(Segment::date))
                .toList();
    }

    /** data-model §4.3 요청 스키마 대응 페이로드 — utterance/dates로 직렬화(snake_case). */
    private record SegmentRequest(String utterance, List<String> dates) {
    }

    /** data-model §4.3 응답 매핑용 관대한 DTO — 알 수 없는 필드는 무시(검증은 {@code toSegments}). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SegmentsPayload(List<RawSegment> segments) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record RawSegment(String date, String text) {
    }
}
