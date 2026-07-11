package com.devwuu.mocha.llm;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.devwuu.mocha.json.MochaObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OpenAiSearchClient 2단계(공식 페이지 상세 이미지 OCR) 오케스트레이션의 경로별 계약 검증
 * (ref: specs/coffee-note-agent/changes/0006-official-page-image-ocr TΔ5, plan.md#ADR-15).
 * <p>1단계 응답(web_search)·이미지 수집기·vision을 모두 stub으로 대체해 SDK/네트워크 없이 결정론적으로
 * 검증한다(CLAUDE.md §5.2). 각 실패 모드(URL 없음·fetch 실패·동일성 불일치·이미지 0장·vision 실패)가
 * 예외로 새지 않고 1단계 결과로 수렴하는지(AC-Δ2), vision 성공 시 공식 유래 값이 우선 병합되고
 * official_page_url이 sources에 포함되는지(AC-Δ1)를 단언한다.
 */
class OpenAiSearchClientSecondStageTest {

    private static final String COFFEE = "와이키키";
    private static final String OFFICIAL_URL = "https://momos.co.kr/product/waikiki";

    /** 1단계 JSON을 고정 반환하는 스텁 — rawSearch 시임만 대체하고 2단계 협력자는 생성자로 주입한다. */
    static class StubFirstStage extends OpenAiSearchClient {
        private final String firstStageJson;

        StubFirstStage(String firstStageJson, OfficialPageImageCollector collector, VisionClient vision) {
            super(null, "test-model", 3, MochaObjectMapper.create(), collector, vision);
            this.firstStageJson = firstStageJson;
        }

        @Override
        protected String rawSearch(SearchQuery query) {
            return firstStageJson;
        }
    }

    /** collect 호출 여부·인자를 기록하고 고정 결과를 돌려주는 수집기 스텁. */
    static class RecordingCollector extends OfficialPageImageCollector {
        private final OfficialPageContent result;
        boolean called;
        String requestedUrl;

        RecordingCollector(OfficialPageContent result) {
            this.result = result;
        }

        @Override
        public OfficialPageContent collect(String url) {
            this.called = true;
            this.requestedUrl = url;
            return result;
        }
    }

    /** read 호출 여부를 기록하고 고정 결과를 돌려주는 vision 스텁. */
    static class RecordingVision implements VisionClient {
        private final VisionExtraction result;
        boolean called;

        RecordingVision(VisionExtraction result) {
            this.result = result;
        }

        @Override
        public VisionExtraction read(List<String> imageUrls, VisionHint hint) {
            this.called = true;
            return result;
        }
    }

    private static SearchQuery query() {
        return new SearchQuery(COFFEE, "모모스 커피");
    }

    // roastery/origin/process/roast_level/official_notes/official_page_url/sources 를 담은 1단계 JSON.
    private static String firstStageJson(String officialPageUrl) {
        String urlField = officialPageUrl == null ? "null" : "\"" + officialPageUrl + "\"";
        return """
                {"roastery": "모모스", "origin": "브라질", "process": "내추럴", "roast_level": "미디엄",
                 "official_notes": ["초콜릿"], "official_page_url": %s,
                 "sources": ["https://blog.example/x"]}
                """.formatted(urlField);
    }

    private ListAppender<ILoggingEvent> logs;
    private Logger clientLogger;

    @BeforeEach
    void attachLogAppender() {
        clientLogger = (Logger) LoggerFactory.getLogger(OpenAiSearchClient.class);
        logs = new ListAppender<>();
        logs.start();
        clientLogger.addAppender(logs);
    }

    @AfterEach
    void detachLogAppender() {
        clientLogger.detachAppender(logs);
    }

    @Test
    @DisplayName("AC-Δ2: official_page_url 없음 → 2단계 미발동, 1단계 결과 그대로(수집기 미호출)")
    void noOfficialUrlSkipsSecondStage() {
        RecordingCollector collector = new RecordingCollector(OfficialPageContent.empty());
        RecordingVision vision = new RecordingVision(VisionExtraction.empty());

        SearchResult result = new StubFirstStage(firstStageJson(null), collector, vision).search(query());

        assertThat(collector.called).isFalse();
        assertThat(vision.called).isFalse();
        // 1단계 값 그대로, sources에 official_page_url 추가 없음.
        assertThat(result.origin()).isEqualTo("브라질");
        assertThat(result.sources()).containsExactly("https://blog.example/x");
    }

    @Test
    @DisplayName("AC-Δ2: 페이지 fetch 실패(empty content) → 1단계 결과로 진행, vision 미호출·구분 로그")
    void fetchFailureFallsBackToFirstStage() {
        RecordingCollector collector = new RecordingCollector(OfficialPageContent.empty());
        RecordingVision vision = new RecordingVision(VisionExtraction.empty());

        SearchResult result = new StubFirstStage(firstStageJson(OFFICIAL_URL), collector, vision).search(query());

        assertThat(collector.called).isTrue();
        assertThat(vision.called).isFalse();
        assertThat(result.origin()).isEqualTo("브라질");
        assertThat(result.sources()).doesNotContain(OFFICIAL_URL);
        assertThat(logs.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.INFO);
            assertThat(e.getFormattedMessage()).contains("수집 결과 없음");
        });
    }

    @Test
    @DisplayName("AC-Δ2: 페이지 동일성 불일치(커피명 미확인) → vision 호출 전 2단계 포기, 1단계 결과")
    void identityMismatchAbortsBeforeVision() {
        // 실존하지만 다른 상품 페이지 — 텍스트에 '와이키키'가 없다(URL 환각·오상품).
        OfficialPageContent otherProduct = new OfficialPageContent(
                List.of("https://momos.co.kr/img/geisha.jpg"), "모모스 게이샤 상세 페이지 원산지 에티오피아");
        RecordingCollector collector = new RecordingCollector(otherProduct);
        RecordingVision vision = new RecordingVision(
                new VisionExtraction(null, "에티오피아", null, null, List.of()));

        SearchResult result = new StubFirstStage(firstStageJson(OFFICIAL_URL), collector, vision).search(query());

        // 동일성 가드가 vision 호출 전에 막는다(비용 절약).
        assertThat(vision.called).isFalse();
        assertThat(result.origin()).isEqualTo("브라질");
        assertThat(result.sources()).doesNotContain(OFFICIAL_URL);
        assertThat(logs.list).anySatisfy(e ->
                assertThat(e.getFormattedMessage()).contains("동일성 불일치"));
    }

    @Test
    @DisplayName("AC-Δ2: 동일성 일치·상세 이미지 0장 → vision 미호출, 1단계 결과로 진행")
    void zeroImagesFallsBackToFirstStage() {
        // 텍스트엔 커피명이 있으나(동일성 통과) 상세 이미지가 0장.
        OfficialPageContent noImages = new OfficialPageContent(
                List.of(), "모모스 와이키키 상세 페이지");
        RecordingCollector collector = new RecordingCollector(noImages);
        RecordingVision vision = new RecordingVision(VisionExtraction.empty());

        SearchResult result = new StubFirstStage(firstStageJson(OFFICIAL_URL), collector, vision).search(query());

        assertThat(vision.called).isFalse();
        assertThat(result.origin()).isEqualTo("브라질");
        assertThat(result.sources()).doesNotContain(OFFICIAL_URL);
        assertThat(logs.list).anySatisfy(e ->
                assertThat(e.getFormattedMessage()).contains("이미지 0장"));
    }

    @Test
    @DisplayName("AC-Δ2: vision 무결과/실패(empty) → 1단계 결과로 진행, sources에 URL 미추가")
    void visionEmptyFallsBackToFirstStage() {
        OfficialPageContent page = new OfficialPageContent(
                List.of("https://momos.co.kr/img/waikiki1.jpg"), "모모스 와이키키 상세 페이지");
        RecordingCollector collector = new RecordingCollector(page);
        RecordingVision vision = new RecordingVision(VisionExtraction.empty());

        SearchResult result = new StubFirstStage(firstStageJson(OFFICIAL_URL), collector, vision).search(query());

        assertThat(vision.called).isTrue();
        assertThat(result.origin()).isEqualTo("브라질");
        assertThat(result.sources()).doesNotContain(OFFICIAL_URL);
        assertThat(logs.list).anySatisfy(e ->
                assertThat(e.getFormattedMessage()).contains("vision 무결과/실패"));
    }

    @Test
    @DisplayName("AC-Δ1: vision 성공 → 공식 유래 값이 1단계 fallback을 덮고 official_page_url이 sources에 포함")
    void visionSuccessMergesOfficialFirst() {
        OfficialPageContent page = new OfficialPageContent(
                List.of("https://momos.co.kr/img/waikiki1.jpg", "https://momos.co.kr/img/waikiki2.jpg"),
                "모모스 와이키키 상세 페이지 시즈널 블렌드");
        RecordingCollector collector = new RecordingCollector(page);
        // 공식 페이지 OCR 결과 — origin/process/roast_level/official_notes를 공식값으로 채운다(roastery는 null).
        RecordingVision vision = new RecordingVision(new VisionExtraction(
                null, "에티오피아, 콜롬비아", "워시드", "라이트", List.of("패션프루트", "베르가못")));

        SearchResult result = new StubFirstStage(firstStageJson(OFFICIAL_URL), collector, vision).search(query());

        assertThat(vision.called).isTrue();
        assertThat(collector.requestedUrl).isEqualTo(OFFICIAL_URL);
        // 공식 유래(2단계) 값이 1단계 fallback을 덮는다(우선 병합).
        assertThat(result.origin()).isEqualTo("에티오피아, 콜롬비아");
        assertThat(result.process()).isEqualTo("워시드");
        assertThat(result.roastLevel()).isEqualTo("라이트");
        assertThat(result.officialNotes()).containsExactly("패션프루트", "베르가못");
        // vision이 채우지 않은 필드(roastery=null)는 1단계 값 유지.
        assertThat(result.roastery()).isEqualTo("모모스");
        // official_page_url이 sources에 포함된다(1단계 sources는 보존).
        assertThat(result.sources()).containsExactly("https://blog.example/x", OFFICIAL_URL);
    }

    @Test
    @DisplayName("AC-Δ2: 수집기가 예외를 던져도 예외 미전파 — 1단계 결과로 진행")
    void collectorExceptionDoesNotPropagate() {
        OfficialPageImageCollector throwing = new OfficialPageImageCollector() {
            @Override
            public OfficialPageContent collect(String url) {
                throw new RuntimeException("boom");
            }
        };
        RecordingVision vision = new RecordingVision(VisionExtraction.empty());

        SearchResult result = new StubFirstStage(firstStageJson(OFFICIAL_URL), throwing, vision).search(query());

        assertThat(vision.called).isFalse();
        assertThat(result.origin()).isEqualTo("브라질");
        assertThat(logs.list).anySatisfy(e -> {
            assertThat(e.getLevel()).isEqualTo(Level.WARN);
            assertThat(e.getFormattedMessage()).contains("2단계 이미지 OCR 실패");
        });
    }
}
