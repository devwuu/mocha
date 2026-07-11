package com.devwuu.mocha.llm;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 공식 상품 페이지에서 상세 이미지 URL과 동일성 가드용 텍스트를 수집하는 Jsoup 경계 클래스
 * (ref: specs/coffee-note-agent/changes/0006-official-page-image-ocr, plan.md#ADR-15).
 * <p>검색 2단계({@link OpenAiSearchClient})가 1단계에서 받은 {@code official_page_url}을 여기에 넘겨
 * 상세 이미지 URL을 얻고(→ {@link VisionClient} OCR), 함께 나온 페이지 텍스트로 동일성 가드를 건다.
 * <p><b>Jsoup 타입은 이 클래스 안에만</b> 존재한다 — 소비자는 {@link OfficialPageContent}(SDK 중립 값객체)만
 * 본다(NFR-4). 어떤 실패(fetch 오류·이미지 0장)도 예외로 새지 않고 {@link OfficialPageContent#empty()} 또는
 * 빈 이미지 목록으로 수렴한다 — 2단계 실패는 1단계 결과로 진행(AC-Δ2).
 */
public class OfficialPageImageCollector {

    private static final Logger log = LoggerFactory.getLogger(OfficialPageImageCollector.class);

    // 상세 이미지 상한 — 통짜 세로 상세는 여러 장으로 잘려 올라오므로 넉넉히 8장, 초과분은 버린다.
    // 코드 상수로 둔다(설정 키를 늘리지 않는다, plan §5·ADR-15).
    static final int MAX_IMAGES = 8;

    private static final int TIMEOUT_MS = 10_000;
    // 로스터리 사이트가 기본 UA를 차단하는 경우가 있어 브라우저 UA로 요청한다(fetch 성공률).
    private static final String USER_AGENT =
            "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/124.0 Safari/537.36";

    // 상품 상세 본문 영역 선택자 — 한국 로스터리 다수가 쓰는 카페24는 상세 통짜 이미지를 #prdDetail에 담는다
    // (findings-TΔ0 모모스 실측). 해당 영역이 없으면 본문 전체(body)로 폴백한다.
    private static final String DETAIL_SELECTOR =
            "#prdDetail, #detail, .prdDetail, .detailArea, .cont, .goods_description";

    /**
     * URL의 페이지를 fetch해 상세 이미지 URL·페이지 텍스트를 수집한다.
     *
     * @param url 1단계가 반환한 공식 상품 페이지 URL.
     * @return 수집 결과. fetch 실패 시 {@link OfficialPageContent#empty()}(예외 미전파, AC-Δ2).
     */
    public OfficialPageContent collect(String url) {
        Document doc;
        try {
            doc = fetch(url);
        } catch (IOException | RuntimeException e) {
            // POLICY: 어떤 2단계 실패도 예외로 새지 않고 빈 결과로 수렴 — 1단계 결과로 진행 (ADR-15, AC-Δ2, plan §7).
            log.warn("공식 페이지 fetch 실패 — 2단계 포기하고 1단계 결과로 진행: url={}", url, e);
            return OfficialPageContent.empty();
        }
        return extract(doc);
    }

    /**
     * 네트워크 경계 — Jsoup으로 페이지를 가져온다.
     * <p>테스트는 네트워크 없이 {@link #parse(String, String)}로 fixture HTML을 직접 넣어 검증한다
     * (CLAUDE.md §5.2, 실 fetch 스모크는 수동).
     */
    protected Document fetch(String url) throws IOException {
        return Jsoup.connect(url)
                .timeout(TIMEOUT_MS)
                .userAgent(USER_AGENT)
                .get();
    }

    /**
     * fixture HTML 문자열을 파싱해 수집한다 — 테스트가 네트워크 없이 수집·상한·절대화·페이지 텍스트를
     * 검증하는 진입점(CLAUDE.md §5.2).
     *
     * @param html    페이지 HTML.
     * @param baseUri 상대 URL 절대화 기준(페이지 URL).
     */
    OfficialPageContent parse(String html, String baseUri) {
        return extract(Jsoup.parse(html, baseUri));
    }

    private OfficialPageContent extract(Document doc) {
        Element scope = doc.selectFirst(DETAIL_SELECTOR);
        if (scope == null) {
            scope = doc.body();
        }

        List<String> imageUrls = new ArrayList<>();
        if (scope != null) {
            for (Element img : scope.select("img")) {
                // absUrl은 baseUri 기준으로 상대 URL을 절대화한다(해석 불가 시 빈 문자열).
                String abs = img.absUrl("src");
                if (abs.isBlank() || imageUrls.contains(abs)) {
                    continue;
                }
                imageUrls.add(abs);
                if (imageUrls.size() >= MAX_IMAGES) {
                    break;
                }
            }
        }

        // 동일성 가드용 텍스트는 상세 영역이 아니라 페이지 전체(제목+본문) — 잘린 상세 이미지엔 상품명이 없어도
        // 상품 페이지 텍스트엔 있기 때문이다(ADR-15 동일성 가드, findings-TΔ0).
        String bodyText = doc.body() != null ? doc.body().text() : "";
        String pageText = (doc.title() + " " + bodyText).strip();

        if (imageUrls.isEmpty()) {
            // 상세 이미지 0장 — 2단계 실패 모드 중 하나. 텍스트는 채워 반환하고, vision 단계에서 빈 결과로 수렴한다(AC-Δ2).
            log.info("공식 페이지 상세 이미지 0장 — 2단계 vision 생략 가능");
        }
        return new OfficialPageContent(imageUrls, pageText);
    }
}
