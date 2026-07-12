package com.devwuu.mocha.llm;

import com.devwuu.mocha.json.MochaObjectMapper;
import com.devwuu.mocha.pipeline.PhotoInfoExtractor;
import com.devwuu.mocha.repository.StagedImage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Change 0010(추출 레시피 + 수신 사진 OCR) 회귀 가드 — 델타가 <b>바꾸지 않기로 한</b> 기존 경로가 실제로 불변인지
 * 명시적으로 못박는다(delta.md AC-Δ7, "blast radius" UNCHANGED 행).
 * <p>0010은 {@link VisionExtraction}에 {@code coffeeName}(coffee_name)을 추가하고 수신 사진 OCR([2.5])을
 * 신설했다. 이 변경이 (1) 검색 2단계 병합({@code mergeOfficial})과 (2) 사진 없는 텍스트 기록 흐름에
 * 영향을 주지 않아야 한다. 경로별 동작·계약은 {@link OpenAiSearchClientSecondStageTest}·
 * {@link ChangeD6RegressionGuardTest}가 다루고, 이 클래스는 "0010이 무엇을 불변으로 두는가"를 못박는다.
 */
class ChangeD10RegressionGuardTest {

    private static final String OFFICIAL_URL = "https://momos.co.kr/product/waikiki";

    /** 1단계 JSON을 고정 반환하고 2단계 협력자를 주입하는 스텁 — SDK/네트워크 없이 2단계 전체 경로를 태운다. */
    static class StubTwoStageSearch extends OpenAiSearchClient {
        private final String firstStageJson;

        StubTwoStageSearch(String firstStageJson, OfficialPageImageCollector collector, VisionClient vision) {
            super(null, "test-model", 3, MochaObjectMapper.create(), collector, vision);
            this.firstStageJson = firstStageJson;
        }

        @Override
        protected String rawSearch(SearchQuery query) {
            return firstStageJson;
        }
    }

    private static String firstStageJson() {
        return """
                {"roastery": "모모스", "origin": "브라질", "process": "내추럴", "roast_level": "미디엄",
                 "official_notes": ["초콜릿"], "official_page_url": "%s",
                 "sources": ["https://blog.example/x"]}
                """.formatted(OFFICIAL_URL);
    }

    // 동일성 가드 통과(pageText에 '와이키키' 포함) + 상세 이미지 1장.
    private static OfficialPageImageCollector collectorWithPage() {
        return new OfficialPageImageCollector() {
            @Override
            public OfficialPageContent collect(String url) {
                return new OfficialPageContent(
                        List.of("https://momos.co.kr/img/waikiki1.jpg"), "모모스 와이키키 상세 페이지");
            }
        };
    }

    private static SearchQuery query() {
        return new SearchQuery("와이키키", "모모스 커피");
    }

    // ── (1) 검색 보강 동작 불변: coffee_name은 mergeOfficial에서 미사용 ────────────────────────

    @Test
    @DisplayName("AC-Δ7: VisionExtraction.coffeeName 값이 무엇이든 검색 2단계 병합 결과는 동일 — coffee_name은 mergeOfficial 미사용")
    void coffeeNameDoesNotAffectSearchMerge() {
        // 동일한 공식 5필드, coffee_name만 다른 두 vision — 검색 결과가 완전히 같아야 한다(coffee_name 미사용).
        VisionClient withoutName = (imageUrls, hint) -> new VisionExtraction(
                null, null, "에티오피아", "워시드", "라이트", List.of("패션프루트"));
        VisionClient withName = (imageUrls, hint) -> new VisionExtraction(
                "사진에서 읽은 커피명", null, "에티오피아", "워시드", "라이트", List.of("패션프루트"));

        SearchResult resultWithoutName =
                new StubTwoStageSearch(firstStageJson(), collectorWithPage(), withoutName).search(query());
        SearchResult resultWithName =
                new StubTwoStageSearch(firstStageJson(), collectorWithPage(), withName).search(query());

        // SearchResult에는 커피명 필드가 없고(계약 불변), 5필드·sources 병합 결과가 종전과 동일하다.
        assertThat(resultWithName).isEqualTo(resultWithoutName);
        assertThat(resultWithName.origin()).isEqualTo("에티오피아");
        assertThat(resultWithName.process()).isEqualTo("워시드");
        assertThat(resultWithName.roastLevel()).isEqualTo("라이트");
        assertThat(resultWithName.officialNotes()).containsExactly("패션프루트");
        assertThat(resultWithName.sources()).containsExactly("https://blog.example/x", OFFICIAL_URL);
    }

    @Test
    @DisplayName("AC-Δ7: coffee_name만 채워지고 공식 5필드가 전무하면 병합하지 않고 1단계 결과로 진행 — coffee_name은 검색 트리거 아님")
    void coffeeNameOnlyDoesNotTriggerMerge() {
        // 공식 5필드는 전부 비어 있고 coffee_name만 있는 vision — hasNoOfficialInfo가 참이라 1단계로 수렴.
        VisionClient nameOnly = (imageUrls, hint) -> new VisionExtraction(
                "사진에서 읽은 커피명", null, null, null, null, List.of());

        SearchResult result =
                new StubTwoStageSearch(firstStageJson(), collectorWithPage(), nameOnly).search(query());

        // 병합 미발생 — 1단계 값 그대로, official_page_url이 sources에 추가되지 않는다.
        assertThat(result.origin()).isEqualTo("브라질");
        assertThat(result.process()).isEqualTo("내추럴");
        assertThat(result.roastLevel()).isEqualTo("미디엄");
        assertThat(result.officialNotes()).containsExactly("초콜릿");
        assertThat(result.sources()).containsExactly("https://blog.example/x");
    }

    // ── (2) 사진 없는 텍스트 기록 흐름 불변(AC-1) — [2.5]는 사진이 없으면 no-op ──────────────

    /** 호출되면 즉시 테스트를 깨는 vision 스텁 — 텍스트-only 흐름에서 OCR이 절대 호출되지 않음을 못박는다. */
    private static VisionClient failIfCalled() {
        return (imageUrls, hint) -> {
            throw new AssertionError("사진 없는 흐름에서 vision(OCR)이 호출되어서는 안 된다(AC-1 불변)");
        };
    }

    @Test
    @DisplayName("AC-Δ7(AC-1): 사진 없는 기록 흐름 — PhotoInfoExtractor는 vision 호출 없이 empty() 반환")
    void noPhotoFlowSkipsVision() {
        PhotoInfoExtractor extractor = new PhotoInfoExtractor(failIfCalled(), 4);

        VisionExtraction fromEmpty = extractor.extract(List.of(), new VisionHint(null, null));
        VisionExtraction fromNull = extractor.extract(null, new VisionHint(null, null));

        // vision 미호출(failIfCalled가 안 터짐) + 빈 결과 — [2.5]가 텍스트-only 흐름에 개입하지 않는다.
        assertThat(fromEmpty).isEqualTo(VisionExtraction.empty());
        assertThat(fromNull).isEqualTo(VisionExtraction.empty());
    }
}
