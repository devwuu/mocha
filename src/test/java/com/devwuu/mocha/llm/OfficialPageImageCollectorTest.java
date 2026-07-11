package com.devwuu.mocha.llm;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * OfficialPageImageCollector 계약 검증 — 카페24형 상세 구조 fixture로 상세 이미지 수집·상한·상대 URL 절대화·
 * 동일성 가드용 페이지 텍스트 포함·이미지 0장 경로, fetch 실패의 빈 결과 수렴을 네트워크 없이 결정론적으로
 * 확인한다 (ref: CLAUDE.md §5.2, 실 fetch 스모크는 수동; plan.md#ADR-15, AC-Δ1/AC-Δ2).
 */
class OfficialPageImageCollectorTest {

    private static final String BASE = "https://momos.co.kr/product/detail.html?product_no=123";

    // 카페24형: 상품명·로스터리는 페이지 상단에 텍스트로, 스펙은 #prdDetail 통짜 상세 이미지에.
    private static final String CAFE24_HTML = """
            <html><head><title>모모스 커피 와이키키 원두 200g</title></head>
            <body>
              <div id="header"><img src="/img/logo.png"></div>
              <div class="headingArea"><h2>와이키키</h2><span>모모스 커피</span></div>
              <div id="prdDetail" class="cont">
                <p>테이스팅 노트: 패션프루트, 베르가못</p>
                <img src="/web/upload/detail1.jpg">
                <img src="web/upload/detail2.jpg">
                <img src="https://cdn.momos.co.kr/detail3.jpg">
              </div>
            </body></html>
            """;

    @Test
    @DisplayName("AC-Δ1: #prdDetail 상세 영역의 이미지를 절대 URL로 수집한다")
    void collectsDetailImagesAsAbsoluteUrls() {
        OfficialPageContent content = new OfficialPageImageCollector().parse(CAFE24_HTML, BASE);

        assertThat(content.imageUrls()).containsExactly(
                "https://momos.co.kr/web/upload/detail1.jpg",   // 루트 상대
                "https://momos.co.kr/product/web/upload/detail2.jpg", // 문서 상대
                "https://cdn.momos.co.kr/detail3.jpg");         // 이미 절대(그대로)

        // 상세 영역 밖(#header 로고)은 수집하지 않는다.
        assertThat(content.imageUrls()).noneMatch(u -> u.contains("logo.png"));
    }

    @Test
    @DisplayName("AC-Δ1: 페이지 텍스트에 제목+본문(상품명·로스터리)이 담겨 동일성 가드가 쓸 수 있다")
    void includesPageTextForIdentityGuard() {
        OfficialPageContent content = new OfficialPageImageCollector().parse(CAFE24_HTML, BASE);

        // 잘린 상세 이미지엔 상품명이 없어도 페이지 텍스트엔 있다(ADR-15 동일성 가드).
        assertThat(content.pageText())
                .contains("와이키키")
                .contains("모모스 커피");
    }

    @Test
    @DisplayName("이미지 상한 8장을 초과하면 8장까지만 수집한다")
    void capsImagesAtMax() {
        String imgs = IntStream.rangeClosed(1, 20)
                .mapToObj(i -> "<img src=\"/web/upload/d" + i + ".jpg\">")
                .collect(Collectors.joining("\n"));
        String html = "<html><head><title>t</title></head><body>"
                + "<div id=\"prdDetail\">" + imgs + "</div></body></html>";

        OfficialPageContent content = new OfficialPageImageCollector().parse(html, BASE);

        assertThat(content.imageUrls()).hasSize(OfficialPageImageCollector.MAX_IMAGES);
    }

    @Test
    @DisplayName("중복 이미지 URL은 한 번만 수집한다")
    void deduplicatesImageUrls() {
        String html = "<html><head><title>t</title></head><body><div id=\"prdDetail\">"
                + "<img src=\"/web/upload/same.jpg\">"
                + "<img src=\"/web/upload/same.jpg\">"
                + "<img src=\"/web/upload/other.jpg\">"
                + "</div></body></html>";

        OfficialPageContent content = new OfficialPageImageCollector().parse(html, BASE);

        assertThat(content.imageUrls()).containsExactly(
                "https://momos.co.kr/web/upload/same.jpg",
                "https://momos.co.kr/web/upload/other.jpg");
    }

    @Test
    @DisplayName("AC-Δ2: 상세 영역에 이미지가 0장이면 빈 이미지 목록으로 수렴하되 텍스트는 남는다")
    void zeroImagesYieldsEmptyListButKeepsText() {
        String html = "<html><head><title>모모스 와이키키</title></head><body>"
                + "<div id=\"prdDetail\"><p>테이스팅 노트만 텍스트로 있음</p></div></body></html>";

        OfficialPageContent content = new OfficialPageImageCollector().parse(html, BASE);

        assertThat(content.imageUrls()).isEmpty();
        assertThat(content.pageText()).contains("와이키키");
    }

    @Test
    @DisplayName("상세 영역 선택자가 없으면 본문 전체(body)에서 이미지를 수집한다")
    void fallsBackToBodyWhenNoDetailContainer() {
        String html = "<html><head><title>t</title></head><body>"
                + "<img src=\"/a.jpg\"><img src=\"/b.jpg\"></body></html>";

        OfficialPageContent content = new OfficialPageImageCollector().parse(html, BASE);

        assertThat(content.imageUrls()).containsExactly(
                "https://momos.co.kr/a.jpg",
                "https://momos.co.kr/b.jpg");
    }

    @Test
    @DisplayName("AC-Δ2: fetch가 실패하면 예외로 새지 않고 빈 결과로 수렴한다")
    void fetchFailureYieldsEmpty() {
        OfficialPageImageCollector collector = new OfficialPageImageCollector() {
            @Override
            protected org.jsoup.nodes.Document fetch(String url) throws IOException {
                throw new IOException("connection refused");
            }
        };

        OfficialPageContent content = collector.collect(BASE);

        assertThat(content).isEqualTo(OfficialPageContent.empty());
        assertThat(content.imageUrls()).isEmpty();
        assertThat(content.pageText()).isEmpty();
    }

    @Test
    @DisplayName("collect가 fetch 결과를 파싱해 상세 이미지를 수집한다")
    void collectFetchesThenExtracts() {
        OfficialPageImageCollector collector = new OfficialPageImageCollector() {
            @Override
            protected org.jsoup.nodes.Document fetch(String url) {
                return org.jsoup.Jsoup.parse(CAFE24_HTML, BASE);
            }
        };

        OfficialPageContent content = collector.collect(BASE);

        assertThat(content.imageUrls()).hasSize(3);
        assertThat(content.pageText()).contains("와이키키");
    }

    @Test
    @DisplayName("empty()는 이미지 없음·빈 텍스트로 방어적 복사된 불변 값이다")
    void emptyIsImmutable() {
        OfficialPageContent empty = OfficialPageContent.empty();
        assertThat(empty.imageUrls()).isEmpty();
        assertThat(empty.pageText()).isEmpty();

        List<String> urls = new java.util.ArrayList<>(List.of("https://x/a.jpg"));
        OfficialPageContent content = new OfficialPageContent(urls, "text");
        urls.add("https://x/b.jpg"); // 원본 변경이 값객체에 영향 없어야 한다.
        assertThat(content.imageUrls()).containsExactly("https://x/a.jpg");
    }
}
