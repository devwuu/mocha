package com.devwuu.mocha.llm;

import com.devwuu.mocha.domain.NoteMeta;
import com.devwuu.mocha.domain.Source;
import com.devwuu.mocha.domain.Sourced;
import com.devwuu.mocha.json.MochaObjectMapper;
import com.devwuu.mocha.pipeline.NoteEnricher;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Change 0006(2단계 이미지 OCR 보강) 회귀 가드 — 델타가 <b>바꾸지 않기로 한</b> 공개 계약이 실제로 유지되는지
 * 명시적으로 고정한다(AC-Δ6, delta.md "blast radius" UNCHANGED 행).
 * <p>2단계는 {@link OpenAiSearchClient} 구현체 내부에만 있어야 하고, 소비자(NoteEnricher)·값객체(SearchResult)·
 * 인터페이스(SearchClient)는 무변경이어야 한다. 개별 배선·경로별 동작은 {@link OpenAiSearchClientTest}·
 * {@link OpenAiSearchClientSecondStageTest}·{@code NoteEnricherTest}가 다루고, 이 클래스는 "무엇이 불변인가"를
 * 계약 형태·통합 흐름으로 못박는다.
 */
class ChangeD6RegressionGuardTest {

    // ── 계약 형태 불변(reflection) ──────────────────────────────────────────

    @Test
    @DisplayName("AC-Δ6: SearchResult 공개 필드가 불변 — 2단계 입력 official_page_url이 공개 계약에 누출되지 않는다")
    void searchResultFieldsUnchanged() {
        List<String> components = Arrays.stream(SearchResult.class.getRecordComponents())
                .map(RecordComponent::getName)
                .toList();

        // 공개 SearchResult는 6필드 그대로 — 델타 이전과 동일(roastery/origin/process/roastLevel/officialNotes/sources).
        assertThat(components).containsExactly(
                "roastery", "origin", "process", "roastLevel", "officialNotes", "sources");
        // 2단계 입력(official_page_url)은 구현체 내부 SearchPayload에만 있고 공개 값객체엔 없다(blast radius UNCHANGED).
        assertThat(components).noneMatch(name -> name.toLowerCase().contains("official") && name.toLowerCase().contains("url"));
    }

    @Test
    @DisplayName("AC-Δ6: SearchClient 계약이 불변 — search(SearchQuery): SearchResult 단일 메서드, 2단계로 인한 메서드 추가 없음")
    void searchClientContractUnchanged() {
        Method[] methods = SearchClient.class.getDeclaredMethods();

        assertThat(methods).hasSize(1);
        Method search = methods[0];
        assertThat(search.getName()).isEqualTo("search");
        assertThat(search.getReturnType()).isEqualTo(SearchResult.class);
        assertThat(search.getParameterTypes()).containsExactly(SearchQuery.class);
    }

    // ── 통합 불변: 2단계 산출물이 UNCHANGED NoteEnricher를 통과해도 source=user 불변 ──────────

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

    private static NoteMeta draft() {
        // origin/roastLevel/officialNotes는 빈 draft(보강 대상), roastery·process는 사용자 명시(불가침).
        return new NoteMeta(
                "와이키키",
                Sourced.user("모모스 커피"),
                null,
                Sourced.user("내추럴"),
                null,
                null,
                List.of());
    }

    @Test
    @DisplayName("AC-Δ6(V-6/AC-3): 2단계 공식 병합 결과가 NoteEnricher를 통과해도 source=user 필드는 덮이지 않고, 빈 필드만 search로 채워진다")
    void twoStageResultFlowsThroughUnchangedEnricherPreservingUserFields() {
        String firstStageJson = """
                {"roastery": null, "origin": "브라질", "process": "워시드", "roast_level": null,
                 "official_notes": ["초콜릿"], "official_page_url": "https://momos.co.kr/product/waikiki",
                 "sources": ["https://blog.example/x"]}
                """;
        // 동일성 가드 통과(pageText에 커피명 '와이키키' 포함) + 공식 OCR로 origin/roast_level/official_notes 채움.
        OfficialPageImageCollector collector = new OfficialPageImageCollector() {
            @Override
            public OfficialPageContent collect(String url) {
                return new OfficialPageContent(
                        List.of("https://momos.co.kr/img/waikiki1.jpg"), "모모스 와이키키 상세 페이지 시즈널 블렌드");
            }
        };
        VisionClient vision = (imageUrls, hint) -> new VisionExtraction(
                null, "에티오피아, 콜롬비아", "펄프드 내추럴", "라이트", List.of("패션프루트", "베르가못"));

        SearchClient search = new StubTwoStageSearch(firstStageJson, collector, vision);
        NoteMeta result = new NoteEnricher(search).enrich(draft());

        // 사용자 명시 필드는 2단계 공식값(process=펄프드 내추럴)이 있어도 불변 — V-6/AC-3.
        assertThat(result.roastery()).isEqualTo(Sourced.user("모모스 커피"));
        assertThat(result.process()).isEqualTo(Sourced.user("내추럴"));
        // 빈 필드는 2단계 공식 유래값으로 채워지고 source=search로 마킹된다.
        assertThat(result.origin()).isEqualTo(new Sourced<>("에티오피아, 콜롬비아", Source.SEARCH));
        assertThat(result.roastLevel()).isEqualTo(new Sourced<>("라이트", Source.SEARCH));
        assertThat(result.officialNotes().source()).isEqualTo(Source.SEARCH);
        assertThat(result.officialNotes().value()).containsExactly("패션프루트", "베르가못");
        // official_page_url이 sources에 병합된다(1단계 blog 링크 뒤).
        assertThat(result.sources()).containsExactly("https://blog.example/x", "https://momos.co.kr/product/waikiki");
    }

    // ── AC-12 흐름: 어떤 검색 실패도 예외로 새지 않고 미리보기 가능한 draft로 진행 ──────────

    @Test
    @DisplayName("AC-Δ6(AC-12): 검색 호출이 완전 실패해도 enrich는 예외 없이 draft를 그대로 통과시켜 미리보기로 진행한다")
    void totalSearchFailureStillYieldsUsableDraftForPreview() {
        // rawSearch가 예외 → OpenAiSearchClient가 empty()로 수렴 → NoteEnricher가 draft 그대로 통과(AC-12).
        SearchClient failing = new OpenAiSearchClient(null, "test-model", 3, MochaObjectMapper.create()) {
            @Override
            protected String rawSearch(SearchQuery query) {
                throw new RuntimeException("timeout");
            }
        };

        NoteMeta input = draft();
        NoteMeta result = assertThatCodeReturns(() -> new NoteEnricher(failing).enrich(input));

        // 미리보기에 필요한 사용자 값은 온전하고, 검색으로 못 채운 필드는 빈 상태로 통과한다.
        assertThat(result.roastery()).isEqualTo(Sourced.user("모모스 커피"));
        assertThat(result.process()).isEqualTo(Sourced.user("내추럴"));
        assertThat(result.origin()).isNull();
        assertThat(result.roastLevel()).isNull();
        assertThat(result.officialNotes()).isNull();
        assertThat(result.sources()).isEmpty();
    }

    // enrich가 예외를 던지지 않음을 단언하며 결과를 돌려주는 헬퍼.
    private static NoteMeta assertThatCodeReturns(java.util.concurrent.Callable<NoteMeta> call) {
        NoteMeta[] holder = new NoteMeta[1];
        assertThatCode(() -> holder[0] = call.call()).doesNotThrowAnyException();
        return holder[0];
    }
}
