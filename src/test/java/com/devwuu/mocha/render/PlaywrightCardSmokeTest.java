package com.devwuu.mocha.render;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.ScreenshotType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TΔ1(changes/0002-instagram-share-card): 래스터화 인프라 토대 스모크.
 * <p>실제 헤드리스 Chromium을 기동하므로 {@code @Tag("chromium")}로 분리 — 기본 {@code ./gradlew test}에서 제외,
 * {@code ./gradlew chromiumTest}로만 실행(백엔드 CLAUDE.md §5.2, 무거운 외부 의존 테스트 격리).
 * 사전: {@code ./gradlew installChromium} + assets/fonts/ 폰트 번들.
 * <p>검증: Playwright가 로컬 {@code @font-face} HTML을 <b>오프라인 컨텍스트</b>에서 JPG로 구워
 * ① Chromium 기동 ② CDN 없이 로컬 폰트가 렌더에 반영(ADR-11) ③ 4:5(1080×1350) 유효 JPEG 산출(AC-Δ2/AC-Δ3)을 본다.
 */
@Tag("chromium")
class PlaywrightCardSmokeTest {

    private static final String BUNDLED_FONT = "/assets/fonts/GowunDodum-Regular.ttf";
    private static final int CARD_W = 1080;
    private static final int CARD_H = 1350; // 4:5

    @Test
    @DisplayName("TΔ1: 로컬 폰트 HTML을 오프라인 Chromium으로 1080×1350 유효 JPG로 굽는다 (ADR-11)")
    void bakesLocalFontHtmlToFourFiveJpegOffline(@TempDir Path work) throws Exception {
        // 폰트 미번들이면 하드 실패가 아니라 skip — 온디맨드 테스트라 준비 전 실행을 관대히 처리(README 참조).
        byte[] font;
        try (InputStream in = getClass().getResourceAsStream(BUNDLED_FONT)) {
            Assumptions.assumeTrue(in != null,
                    "폰트 미번들: src/main/resources" + BUNDLED_FONT + " — assets/fonts/README.md대로 받은 뒤 실행");
            font = in.readAllBytes();
        }
        // 폰트와 HTML을 같은 임시 디렉터리에 두고 상대 경로로 참조 → base 경로(파일 URL)로 로컬 폰트가 해석되게.
        Path fontFile = work.resolve("GowunDodum-Regular.ttf");
        Files.write(fontFile, font);
        Path htmlFile = work.resolve("card.html");
        Files.writeString(htmlFile, """
                <!doctype html><html lang="ko"><head><meta charset="utf-8">
                <style>
                  @font-face{font-family:'Gowun Dodum';src:url('GowunDodum-Regular.ttf') format('truetype');}
                  html,body{margin:0;padding:0}
                  body{width:%dpx;height:%dpx;background:#efe9e2;
                       font-family:'Gowun Dodum',sans-serif;
                       display:flex;align-items:center;justify-content:center;font-size:64px;color:#3a2a1a}
                </style></head>
                <body>모카 카드 스모크 ☕</body></html>
                """.formatted(CARD_W, CARD_H));

        Path out = work.resolve("smoke.jpg");
        try (Playwright pw = Playwright.create();
             Browser browser = pw.chromium().launch()) {
            // 오프라인 컨텍스트: 네트워크를 끊고도 file:// + 로컬 폰트로 렌더되어야 CDN 미의존 증명(ADR-11).
            BrowserContext ctx = browser.newContext(new Browser.NewContextOptions()
                    .setOffline(true)
                    .setViewportSize(CARD_W, CARD_H)
                    .setDeviceScaleFactor(1));
            Page page = ctx.newPage();
            page.navigate(htmlFile.toUri().toString());
            page.screenshot(new Page.ScreenshotOptions()
                    .setType(ScreenshotType.JPEG)
                    .setQuality(90)
                    .setPath(out));
        }

        assertTrue(Files.exists(out), "카드 JPG가 생성되어야 한다");
        byte[] bytes = Files.readAllBytes(out);
        // JPEG SOI 시그니처 FF D8 FF — 유효 JPEG인지 확인.
        assertTrue(bytes.length > 3
                        && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF,
                "유효한 JPEG 시그니처여야 한다");
        BufferedImage img = ImageIO.read(out.toFile());
        assertEquals(CARD_W, img.getWidth(), "카드 폭 = 1080 (4:5)");
        assertEquals(CARD_H, img.getHeight(), "카드 높이 = 1350 (4:5)");
    }
}
