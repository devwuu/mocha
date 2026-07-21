package com.devwuu.mocha.render;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.ScreenshotType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Playwright + 헤드리스 Chromium 기반 {@link CardImageRenderer} (ref: plan.md#ADR-11, changes/0002 TΔ2).
 * <p>카드 HTML을 뷰포트 {@code mocha.card.width}×{@code mocha.card.height}(4:5)로 열어 JPG 스크린샷한다.
 * flexbox·컬러 이모지·한글 웹폰트를 온전히 렌더하려면 실제 브라우저 엔진이 필요하다(순수 Java는 flexbox 미지원).
 * <p><b>오프라인·결정성</b>: 컨텍스트를 offline으로 두어 CDN 없이 로컬 자원(폰트·썸네일·마스코트)만으로 렌더됨을
 * 강제한다 — CDN 미의존을 계약으로 못 박는다(ADR-11).
 * <p><b>base 경로</b>: 임시 HTML을 {@code baseDir} 안에 써서 그 {@code file://} 위치를 상대 URL 기준으로 삼는다.
 * <p><b>수명</b>: {@link Playwright}/{@link Browser}는 render 호출마다 try-with-resources로 생성·해제한다.
 * 싱글턴 재사용은 저장 지연이 실제 관측된 뒤로 유보한다(§4 right-sizing) — 스레드 귀속 문제를 피하고 단순함을 택했다.
 */
@Component
public class PlaywrightCardImageRenderer implements CardImageRenderer {

    private static final Logger log = LoggerFactory.getLogger(PlaywrightCardImageRenderer.class);

    // 카드 템플릿의 autofit 스크립트가 축소 완료 시 <html>에 남기는 마커 — 없는 HTML(autofit 요소 부재)은
    // 즉시 통과한다. navigate의 load 대기만으론 fonts.ready 후에 도는 축소가 스크린샷보다 늦을 수 있어
    // 마커를 명시적으로 기다린다(changes/0021 TΔ5a, findings-TΔ0 §5.2 래스터화 타이밍 리스크).
    private static final String AUTOFIT_READY_PREDICATE =
            "() => !document.querySelector('[data-autofit]')"
                    + " || document.documentElement.hasAttribute('data-autofit-done')";

    private final int width;
    private final int height;
    private final int jpgQuality; // Playwright setQuality는 0~100 정수

    public PlaywrightCardImageRenderer(
            @Value("${mocha.card.width:1080}") int width,
            @Value("${mocha.card.height:1350}") int height,
            @Value("${mocha.card.jpg-quality:0.9}") double jpgQuality) {
        this.width = width;
        this.height = height;
        // 설정은 0~1 실수(mocha.card.jpg-quality) — Playwright의 0~100 정수 품질로 변환.
        this.jpgQuality = (int) Math.round(jpgQuality * 100);
    }

    @Override
    public void render(String html, Path baseDir, Path out) {
        if (html == null || html.isBlank()) {
            throw new IllegalArgumentException("카드 HTML이 비어 있다");
        }
        if (baseDir == null || out == null) {
            throw new IllegalArgumentException("baseDir·out 경로는 필수다");
        }
        try {
            Files.createDirectories(baseDir);
            if (out.getParent() != null) {
                Files.createDirectories(out.getParent());
            }
            // 상대 자원(폰트·thumbs/·마스코트)이 file:// 기준으로 해석되게 임시 HTML을 baseDir 안에 둔다(base 경로 처리).
            Path tmpHtml = Files.createTempFile(baseDir, "card-", ".html");
            try {
                Files.writeString(tmpHtml, html);
                try (Playwright pw = Playwright.create();
                     Browser browser = pw.chromium().launch()) {
                    // offline: 로컬 자원만으로 결정적 렌더 — CDN 참조가 있으면 실패해 미의존을 강제(ADR-11).
                    BrowserContext ctx = browser.newContext(new Browser.NewContextOptions()
                            .setOffline(true)
                            .setViewportSize(width, height)
                            .setDeviceScaleFactor(1));
                    Page page = ctx.newPage();
                    page.navigate(tmpHtml.toUri().toString());
                    // autofit(감상·피드백 축소)이 끝난 뒤에 찍는다 — 완료 마커 대기(ADR-54, TΔ5a).
                    page.waitForFunction(AUTOFIT_READY_PREDICATE);
                    // POLICY: autofit 하한 도달(하한에서도 넘침 = 잘림 가능)은 로깅으로 관측한다 —
                    //         반복 관측되면 하한·레이아웃 재론 (ref: plan.md §6, ADR-54, spec §8).
                    Object floorCount = page.evaluate(
                            "() => document.querySelectorAll('[data-autofit-floor]').length");
                    if (floorCount instanceof Number n && n.intValue() > 0) {
                        log.warn("카드 autofit 하한 도달 — 잘림 가능(plan §6 관측 대상): out={}", out);
                    }
                    // 뷰포트 스크린샷(fullPage 아님) → 정확히 width×height(4:5) JPG. 초과 내용 클램프는 템플릿 책임(TΔ3).
                    page.screenshot(new Page.ScreenshotOptions()
                            .setType(ScreenshotType.JPEG)
                            .setQuality(jpgQuality)
                            .setPath(out));
                }
            } finally {
                // 카드 HTML은 중간 입력 — 파일로 남기지 않는다(ADR-10).
                Files.deleteIfExists(tmpHtml);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("카드 래스터화 실패: " + out, e);
        }
    }
}
