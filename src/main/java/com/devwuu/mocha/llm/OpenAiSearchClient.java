package com.devwuu.mocha.llm;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.openai.client.OpenAIClient;
import com.openai.core.JsonValue;
import com.openai.models.responses.Response;
import com.openai.models.responses.ResponseCreateParams;
import com.openai.models.responses.ResponseFormatTextJsonSchemaConfig;
import com.openai.models.responses.ResponseOutputItem;
import com.openai.models.responses.ResponseOutputMessage;
import com.openai.models.responses.ResponseTextConfig;
import com.openai.models.responses.ToolChoiceTypes;
import com.openai.models.responses.WebSearchPreviewTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * OpenAI web search(Responses API) 기반 {@link SearchClient} 구현 (ref: plan.md#ADR-5, spec FR-3).
 * <p>OpenAI SDK 타입은 이 클래스 안에만 존재한다(plan §4 POLICY, NFR-4). 검색 지침(instructions)에
 * fallback 규칙을 인코딩한다 — official_notes는 <b>로스터리 공식 출처 한정</b>이고, origin/process/roast_level은
 * 단일 원산지든 블렌드든 로스터리 공식 우선 + 신뢰할 일반 출처 fallback으로 찾으면 채운다(블렌드의 여러
 * 구성 원산지는 origin 한 문자열에 쉼표로 나열). "추측 금지"(못 찾으면 공란)는 유지한다
 * (FR-3/AC-16, ADR-14 보강 fallback POLICY).
 * <p>보강은 Responses API 한 콜로 (a) {@code web_search_preview} 도구를 {@code tool_choice}로 강제 실행하고,
 * (b) {@code text.format}에 strict JSON schema를 붙여 <b>스키마 보장 JSON</b>으로 받는다 — 응답에 산문·인용이
 * 섞여도 형식 때문에 검색 결과가 버려지지 않게(P1), 경량 모델이 검색을 건너뛰지 못하게(P3) 한다(ADR-13).
 * <p>호출/형식 실패(예외·비스키마 응답)와 진짜 무결과(정상 응답·채운 값 없음)는 서로 다른 로그로
 * 구분하되(P2, ADR-13, plan §6 관측), 둘 다 예외로 새지 않고 {@link SearchResult#empty()}로 수렴한다 —
 * 상위(NoteEnricher)가 사용자 입력만으로 진행하게 하기 위함(AC-12, plan §7).
 */
public class OpenAiSearchClient implements SearchClient {

    private static final Logger log = LoggerFactory.getLogger(OpenAiSearchClient.class);

    // POLICY: official_notes는 로스터리 출처 한정(fallback 없음), origin/process/roast_level은 단일·블렌드 공통으로
    //         로스터리 공식 우선 + 신뢰할 일반 출처 fallback 보강, 블렌드 여러 원산지는 origin에 쉼표 나열, 추측 금지
    //         (ref: spec FR-3/AC-16, plan#ADR-14 보강 fallback POLICY)
    // POLICY: 공식 페이지 URL 반환(2단계 이미지 OCR의 입력)·한국어 기록·동일성 가드(로스터리명+원두명 동시 확인)를
    //         지침에 인코딩한다. 공식 도메인 탐색은 유도하되(findings-TΔ0: 유도 없으면 official_page_url 일관 null),
    //         환각 URL은 "검색 결과에 실제로 나타난 URL만" 규칙 + 2단계 페이지 동일성 가드로 받는다
    //         (ref: delta 0006 ADR-16, spec FR-3, plan#ADR-15/16, findings-TΔ0).
    // POLICY: roastery(고유명사)는 원문 표기 유지(음차·번역 금지) — 한국어 통일은 나머지 텍스트 필드만(coffee_name은
    //         검색이 만들지 않음), 4계약 프롬프트 동일 인코딩 (ref: plan.md#ADR-38, spec FR-2/AC-57).
    // POLICY: sources에는 로스터리+원두명 동시 확인 출처만 담는다 — 값 채움과 동일 가드(참고 출처 ≠ 수록 출처).
    //         동명의 타 로스터리 상품 페이지가 sources에 섞이던 실데이터 회귀 방지, 스키마 불변
    //         (ref: plan.md#ADR-16 확장, spec FR-3/AC-58).
    private static final String INSTRUCTIONS = """
            너는 커피 원두 정보를 웹 검색으로 보강하는 도우미다. 주어진 커피에 대해 웹을 검색하고 아래 규칙을 반드시 지켜라.
            - 반드시 그 로스터리의 공식 웹사이트(공식 온라인 쇼핑몰)에서 이 원두의 상품 상세 페이지를 찾아라.
              블로그·리뷰 페이지가 먼저 나오면 거기서 멈추지 말고, '로스터리명 + 공식' '로스터리명 + 스토어' 등으로 추가 검색해 공식 도메인을 찾아라.
            - official_page_url에는 그 공식 도메인의 이 원두 상품 상세 페이지 URL만 담는다. 검색 결과에 실제로 나타난 URL을
              그대로 담고, 기억이나 추측으로 URL을 만들거나 일부를 생략·변형하지 마라. 끝내 확인 못 하면 null로 둔다.
            - 로스터리명과 원두명이 함께 확인되는 출처만 사용한다(동일성 가드). 지명·동명의 다른 상품으로 새지 않도록,
              같은 대상인지 확신할 수 없는 값은 채우지 말고 공란으로 둔다.
            - official_notes(로스터리 공식 테이스팅 노트)는 그 로스터리의 공식 웹/판매 페이지에서 확인된 경우에만 채운다.
              제3자 블로그·리뷰의 감상은 official_notes에 넣지 않는다. 공식 출처를 못 찾으면 official_notes는 빈 배열로 둔다.
            - origin/process/roast_level 같은 원두 일반 사실은 단일 원산지든 블렌드든 로스터리 공식 페이지를 우선으로,
              없으면 신뢰할 만한 일반 출처(나무위키·판매몰 등)에서 찾으면 채운다.
            - 블렌드(여러 원산지 구성)의 origin은 구성 원산지를 origin 한 문자열에 쉼표로 나열한다(예: "에티오피아, 콜롬비아, 브라질").
            - roastery는 공식 출처의 원문 표기를 그대로 유지한다 — 음차·번역하지 않는다("FroB Coffee"는 그대로). 한국어 음차·이표기는 내부에서 따로 만드니 여기서 만들지 마라.
            - origin·process·roast_level·official_notes는 한국어로 기록한다(영문 출처는 한국어로 옮겨 적는다). origin은 한국어 지명으로 통일(영문·한글 혼용 금지: "게데오, Gedeb" ❌ → "게데오"), process·roast_level은 한국어 관용 표기로 옮긴다(예: 가공방식 "워시드/내추럴/허니/무산소", 로스팅 "라이트/미디엄/다크" — 고정 목록 아님).
            - 확인되지 않은 값은 추측하지 말고 null(문자열 필드)·빈 배열(리스트)로 둔다.
            - sources에는 로스터리명과 원두명이 이 원두의 것으로 함께 확인된 출처 URL만 담는다(값 채움과 동일한 동일성 가드). 검색 중 참고했더라도 같은 대상인지 확신할 수 없는 페이지(동명의 다른 상품·지명 등)는 sources에 넣지 마라 — 참고한 출처와 수록할 출처는 다르다. 최대 %d개.
            - 출력은 아래 JSON 객체 하나만, 다른 설명 없이 반환한다:
              {"roastery": string|null, "origin": string|null, "process": string|null, "roast_level": string|null,
               "official_notes": string[], "official_page_url": string|null, "sources": string[]}
            """;

    private final OpenAIClient client;
    private final String model;
    private final int maxResults;
    private final ObjectMapper mapper;
    // 2단계 협력자 — 공식 페이지 이미지 수집(Jsoup 경계)과 이미지 OCR(vision 경계). Jsoup/SDK 타입은 각 협력자
    // 안에만 존재한다(NFR-4). 어떤 2단계 실패도 이 클래스가 삼켜 1단계 결과로 진행한다(ADR-15, AC-Δ2).
    private final OfficialPageImageCollector imageCollector;
    private final VisionClient visionClient;

    // 1단계만 수행하는 no-op 협력자 — 2단계 배선 없이 쓰는 4-arg 생성자(테스트·간이)에서 2단계를 안전히 비활성화한다.
    private static final OfficialPageImageCollector NO_OP_COLLECTOR = new OfficialPageImageCollector() {
        @Override
        public OfficialPageContent collect(String url) {
            return OfficialPageContent.empty();
        }
    };
    private static final VisionClient NO_OP_VISION = (imageUrls, hint) -> VisionExtraction.empty();

    /**
     * 1단계(web_search)만 수행하는 생성자 — 2단계(공식 페이지 이미지 OCR) 협력자를 no-op으로 둔다.
     * 프로덕션 배선은 6-arg 생성자로 {@link OfficialPageImageCollector}·{@link VisionClient}를 주입한다(ADR-15).
     *
     * @param maxResults 보강 검색 출처 상한(mocha.search.max-results) — 비용/지연 통제(plan §5).
     */
    public OpenAiSearchClient(OpenAIClient client, String model, int maxResults, ObjectMapper mapper) {
        this(client, model, maxResults, mapper, NO_OP_COLLECTOR, NO_OP_VISION);
    }

    /**
     * 2단계까지 배선하는 생성자 (ADR-15). 1단계 결과의 {@code official_page_url}이 있으면 {@code imageCollector}로
     * 상세 이미지·페이지 텍스트를 얻고, <b>페이지 동일성 가드</b>(커피명 포함 확인) 후 {@code visionClient}로 OCR해
     * 공식 페이지 유래 값을 1단계 fallback보다 우선 병합한다.
     *
     * @param maxResults 보강 검색 출처 상한(mocha.search.max-results) — 비용/지연 통제(plan §5).
     */
    public OpenAiSearchClient(OpenAIClient client, String model, int maxResults, ObjectMapper mapper,
                              OfficialPageImageCollector imageCollector, VisionClient visionClient) {
        this.client = client;
        this.model = model;
        this.maxResults = maxResults;
        this.mapper = mapper;
        this.imageCollector = imageCollector;
        this.visionClient = visionClient;
    }

    @Override
    public SearchResult search(SearchQuery query) {
        String json;
        try {
            json = rawSearch(query);
        } catch (RuntimeException e) {
            // POLICY: 호출 실패는 무결과와 구분해 로깅하되 empty()로 수렴 (ADR-13, AC-12, plan §7).
            // 호출/타임아웃 실패(a) — 프로그래밍 오류·API 장애가 무결과로 은폐되던 P2 회귀 방지.
            log.warn("웹 검색 보강 호출 실패 — 빈 결과로 진행: coffee={}", query.coffeeName(), e);
            return SearchResult.empty();
        }
        return parse(json, query);
    }

    // 응답(JSON)을 SearchResult로 매핑(1단계)하고, official_page_url이 있으면 2단계(이미지 OCR)로 넘긴다.
    // 형식 실패(비JSON·파싱 불가, a)와 진짜 무결과(정상 응답·채운 값 없음, b)는 서로 다른 로그로 구분하되
    // 둘 다 empty()로 수렴한다 (ADR-13, AC-12).
    private SearchResult parse(String json, SearchQuery query) {
        String body = extractJsonObject(json);
        if (body == null) {
            // 형식 실패(a): structured output이면 스키마 JSON이 와야 하는데 JSON 객체가 없다 — 비스키마 응답.
            log.warn("웹 검색 보강 형식 실패(비JSON 응답) — 빈 결과로 진행: coffee={}", query.coffeeName());
            return SearchResult.empty();
        }
        SearchPayload payload;
        try {
            payload = mapper.readValue(body, SearchPayload.class);
        } catch (RuntimeException e) {
            // 형식 실패(a): 스키마 위반·파싱 불가.
            log.warn("웹 검색 보강 형식 실패(파싱 불가) — 빈 결과로 진행: coffee={}", query.coffeeName(), e);
            return SearchResult.empty();
        }
        SearchResult firstStage = new SearchResult(
                payload.roastery(), payload.origin(), payload.process(), payload.roastLevel(),
                payload.officialNotes(), payload.sources());

        String url = payload.officialPageUrl();
        boolean hasOfficialUrl = url != null && !url.isBlank();

        if (firstStage.equals(SearchResult.empty()) && !hasOfficialUrl) {
            // 진짜 무결과(b): 1단계가 채운 값도, 2단계로 넘길 공식 페이지 URL도 없음 — 실패와 구분해 관측(plan §6).
            log.info("웹 검색 보강 무결과(채운 값 없음) — 빈 결과로 진행: coffee={}", query.coffeeName());
            return SearchResult.empty();
        }
        if (!hasOfficialUrl) {
            // 공식 페이지 URL 없음 — 2단계 미발동, 1단계 결과로 진행(가장 흔한 2단계 실패 모드, AC-Δ2).
            return firstStage;
        }
        // POLICY: 공식 페이지 상세 이미지 OCR(2단계)은 VisionClient 뒤에만 — 어떤 2단계 실패도 1단계 결과로 진행
        //         (ref: specs/coffee-note-agent/plan.md#ADR-15, AC-12).
        return enrichFromOfficialPage(firstStage, url.strip(), query);
    }

    // 2단계 오케스트레이션: 공식 페이지 fetch → 동일성 가드 → vision OCR → 공식 유래 우선 병합.
    // 어떤 실패(fetch·동일성 불일치·이미지 0장·vision)도 예외로 새지 않고 1단계 결과로 진행하며, 각 실패는
    // 1단계와 구분되는 로그로 관측된다(ADR-15, AC-Δ2, plan §6·§7).
    private SearchResult enrichFromOfficialPage(SearchResult firstStage, String url, SearchQuery query) {
        try {
            OfficialPageContent content = imageCollector.collect(url);
            if (content.imageUrls().isEmpty() && content.pageText().isBlank()) {
                // fetch 실패 등으로 수집 결과 없음(collector가 원인을 상세 로깅).
                log.info("2단계 페이지 수집 결과 없음(fetch 실패 등) — 1단계 결과로 진행: url={}", url);
                return firstStage;
            }
            // 동일성 가드: fetch한 페이지 텍스트에 커피명이 있어야 진행 — 오상품 URL 환각을 vision 호출 전에 차단
            //             (ref: plan.md#ADR-15 동일성 가드, findings-TΔ0).
            if (!pageMentionsCoffee(content.pageText(), query.coffeeName())) {
                log.info("2단계 페이지 동일성 불일치(커피명 미확인) — 1단계 결과로 진행: coffee={}, url={}",
                        query.coffeeName(), url);
                return firstStage;
            }
            if (content.imageUrls().isEmpty()) {
                log.info("2단계 상세 이미지 0장 — 1단계 결과로 진행: coffee={}, url={}", query.coffeeName(), url);
                return firstStage;
            }
            VisionExtraction vision = visionClient.read(
                    content.imageUrls(), new VisionHint(query.coffeeName(), query.roastery()));
            // POLICY: 검색 2단계는 VisionExtraction.coffeeName을 쓰지 않는다 — 병합(mergeOfficial) 대상인 공식
            //         5필드가 전무하면 1단계로 진행한다. coffee_name은 changes/0010의 수신 사진 OCR 전용이라
            //         검색 시엔 이미 아는 값이며, 이 필드 유무가 검색 동작을 바꾸지 않는다(ADR-15/23, AC-Δ7).
            if (hasNoOfficialInfo(vision)) {
                log.info("2단계 vision 무결과/실패 — 1단계 결과로 진행: coffee={}, url={}", query.coffeeName(), url);
                return firstStage;
            }
            log.info("2단계 공식 페이지 OCR 병합(공식 유래 우선) — coffee={}, url={}, images={}",
                    query.coffeeName(), url, content.imageUrls().size());
            return mergeOfficial(firstStage, vision, url);
        } catch (RuntimeException e) {
            // POLICY: 어떤 2단계 실패도 예외로 새지 않고 1단계 결과로 진행 (ref: plan.md#ADR-15, AC-12, plan §7).
            log.warn("2단계 이미지 OCR 실패 — 1단계 결과로 진행: coffee={}, url={}", query.coffeeName(), url, e);
            return firstStage;
        }
    }

    // 검색 2단계 병합 대상인 공식 5필드(roastery/origin/process/roast_level/official_notes)가 전무한지 본다.
    // coffee_name은 검색이 쓰지 않으므로 검사에서 제외한다 — 이 필드가 채워져도 검색 동작은 불변(AC-Δ7).
    private static boolean hasNoOfficialInfo(VisionExtraction vision) {
        return isBlank(vision.roastery()) && isBlank(vision.origin()) && isBlank(vision.process())
                && isBlank(vision.roastLevel()) && vision.officialNotes().isEmpty();
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    // POLICY: 공식 페이지 유래(2단계) 값이 1단계 fallback 값보다 우선 병합 — 로스터리 공식 우선 (ref: plan.md#ADR-15, FR-3).
    //         official_page_url은 sources에 포함한다.
    private static SearchResult mergeOfficial(SearchResult first, VisionExtraction vision, String officialPageUrl) {
        List<String> officialNotes =
                !vision.officialNotes().isEmpty() ? vision.officialNotes() : first.officialNotes();
        List<String> sources = new ArrayList<>(first.sources());
        if (!sources.contains(officialPageUrl)) {
            sources.add(officialPageUrl);
        }
        return new SearchResult(
                preferOfficial(vision.roastery(), first.roastery()),
                preferOfficial(vision.origin(), first.origin()),
                preferOfficial(vision.process(), first.process()),
                preferOfficial(vision.roastLevel(), first.roastLevel()),
                officialNotes,
                sources);
    }

    private static String preferOfficial(String official, String fallback) {
        return (official != null && !official.isBlank()) ? official : fallback;
    }

    // 동일성 가드용 단순 정규화 contains — 공백 제거 + 소문자화 후 페이지 텍스트에 커피명이 있는지 본다(ADR-15).
    private static boolean pageMentionsCoffee(String pageText, String coffeeName) {
        String haystack = normalizeForGuard(pageText);
        String needle = normalizeForGuard(coffeeName);
        return !needle.isEmpty() && haystack.contains(needle);
    }

    private static String normalizeForGuard(String s) {
        if (s == null) {
            return "";
        }
        return s.toLowerCase(Locale.ROOT).replaceAll("\\s+", "");
    }

    /**
     * SDK 호출 경계 — Responses API에 web search tool을 붙여 보강 요청을 보내고 응답 텍스트를 돌려준다.
     * <p>테스트는 이 메서드를 override해 결정론적 응답으로 대체한다(실 API 스모크는 수동, CLAUDE.md §5.2).
     */
    protected String rawSearch(SearchQuery query) {
        Response response = client.responses().create(buildParams(query));
        return outputText(response);
    }

    // POLICY: 웹 검색 보강은 web_search를 tool_choice로 강제 + structured output(strict JSON)으로 수신 (ADR-13)
    // 파라미터 조립을 분리해 테스트가 SDK 호출 없이 도구·tool_choice·strict schema 배선을 검사할 수 있게 한다.
    ResponseCreateParams buildParams(SearchQuery query) {
        return ResponseCreateParams.builder()
                .model(model)
                .addTool(WebSearchPreviewTool.builder()
                        .type(WebSearchPreviewTool.Type.WEB_SEARCH_PREVIEW)
                        // ADR-16: 검색을 KR로 지역화해 영어권 기본 착지(→영어 기록)를 막는다. search_context_size=high로
                        // 텍스트 컨텍스트를 넓힌다(findings-TΔ0 ②: 파라미터 수용·정상 동작 확인).
                        .userLocation(WebSearchPreviewTool.UserLocation.builder()
                                .type(JsonValue.from("approximate"))
                                .country("KR")
                                .timezone("Asia/Seoul")
                                .build())
                        .searchContextSize(WebSearchPreviewTool.SearchContextSize.HIGH)
                        .build())
                // P3: web_search를 강제해 모델이 검색을 건너뛰지 못하게 한다(AC-Δ2).
                .toolChoice(ToolChoiceTypes.builder()
                        .type(ToolChoiceTypes.Type.WEB_SEARCH_PREVIEW)
                        .build())
                // P1: strict JSON schema로 받아 산문·인용 혼입에도 형식이 보장된다(AC-Δ1).
                .text(ResponseTextConfig.builder()
                        .format(searchSchemaFormat())
                        .build())
                .instructions(INSTRUCTIONS.formatted(maxResults))
                .input(buildInput(query))
                .build();
    }

    // SearchPayload 7필드(roastery/origin/process/roast_level/official_notes/official_page_url/sources)의 strict JSON schema.
    // roastery류·official_page_url은 미확인 시 null 허용(["string","null"]), notes/sources는 문자열 배열. 전 필드 required·additionalProperties=false.
    private static ResponseFormatTextJsonSchemaConfig searchSchemaFormat() {
        return ResponseFormatTextJsonSchemaConfig.builder()
                .name("coffee_enrichment")
                .strict(true)
                .schema(searchSchema())
                .build();
    }

    private static ResponseFormatTextJsonSchemaConfig.Schema searchSchema() {
        Map<String, Object> nullableString = Map.of("type", List.of("string", "null"));
        Map<String, Object> stringArray = Map.of("type", "array", "items", Map.of("type", "string"));

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("roastery", nullableString);
        properties.put("origin", nullableString);
        properties.put("process", nullableString);
        properties.put("roast_level", nullableString);
        properties.put("official_notes", stringArray);
        // ADR-15: 공식 상품 페이지 URL — 2단계 이미지 OCR의 입력(미확인 시 null → 2단계 미발동).
        properties.put("official_page_url", nullableString);
        properties.put("sources", stringArray);

        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", List.of(
                "roastery", "origin", "process", "roast_level", "official_notes", "official_page_url", "sources"));
        schema.put("additionalProperties", false);

        ResponseFormatTextJsonSchemaConfig.Schema.Builder builder =
                ResponseFormatTextJsonSchemaConfig.Schema.builder();
        schema.forEach((key, value) -> builder.putAdditionalProperty(key, JsonValue.from(value)));
        return builder.build();
    }

    // POLICY: web_search 검색 앵커는 "커피 이름:"/"로스터리:" 필드 라벨 없이 로스터리명+커피명을 자연
    //         검색어로 넘긴다 — 라벨 통짜가 쿼리로 나가 공식 페이지 착지를 막던 회귀를 제거(ref: delta 0005 D4/AC-Δ4, plan#ADR-14).
    // POLICY: 도메인 키워드 '원두'를 앵커에 더해 커피 문맥을 고정한다 — 문맥 없는 원두명이 지명·동명 상품으로
    //         새던 문제를 막는다. 필드 라벨이 아니라 자연 검색어라 ADR-14와 양립한다(ref: delta 0006 ADR-16).
    String buildInput(SearchQuery query) {
        String anchor;
        if (query.roastery() != null && !query.roastery().isBlank()) {
            anchor = query.roastery().strip() + " " + query.coffeeName().strip();
        } else {
            anchor = query.coffeeName().strip();
        }
        return anchor + " 원두";
    }

    // Responses 출력 아이템 중 메시지 텍스트만 이어붙인다(web search 호출 아이템 등은 건너뜀).
    private String outputText(Response response) {
        StringBuilder sb = new StringBuilder();
        for (ResponseOutputItem item : response.output()) {
            if (!item.isMessage()) {
                continue;
            }
            for (ResponseOutputMessage.Content content : item.asMessage().content()) {
                if (content.isOutputText()) {
                    sb.append(content.asOutputText().text());
                }
            }
        }
        return sb.toString();
    }

    // 응답에 코드펜스·인용 문구가 섞여도 첫 '{' ~ 마지막 '}' 구간만 취한다. 없으면 null(무결과 취급).
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

    /** 검색 응답 매핑용 관대한 DTO — 알 수 없는 필드는 무시(LLM 출력의 잉여 필드가 전체 결과를 깨지 않게). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SearchPayload(
            String roastery,
            String origin,
            String process,
            String roastLevel,
            List<String> officialNotes,
            // 2단계 입력 — 공식 상품 페이지 URL(1단계 strict schema에서 추가, ADR-15). 미확인 시 null → 2단계 미발동.
            String officialPageUrl,
            List<String> sources) {
    }
}
