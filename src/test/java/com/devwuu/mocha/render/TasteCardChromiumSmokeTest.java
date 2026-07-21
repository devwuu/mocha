package com.devwuu.mocha.render;

import com.devwuu.mocha.config.RenderConfig;
import com.devwuu.mocha.domain.Rating;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.ScreenshotType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TΔ4a(changes/0021): 감상 카드 실 렌더 스모크 — 두 테마의 {@code taste.html}을 헤드리스 Chromium으로
 * 실제 래스터화한다. 실 브라우저 기동이라 {@code @Tag("chromium")}로 분리({@code ./gradlew chromiumTest}).
 * <ul>
 *   <li>AC-Δ5: 두 테마 감상 카드가 1080×1350(4:5) 유효 JPG로 구워진다 — 비주얼 확인용 산출은
 *       {@code build/card-previews/}에 남긴다.</li>
 *   <li>AC-Δ9: 렌더 중 외부 네트워크 요청 0 — 오프라인 컨텍스트 + 전 요청 {@code file://} 검증.</li>
 *   <li>FR-13: 장문 감상이 autofit으로 축소된다(기준 크기 미만 확인) + 완료 마커(TΔ5a 대기 계약) 세팅.</li>
 * </ul>
 */
@Tag("chromium")
class TasteCardChromiumSmokeTest {

    private static final int CARD_W = 1080;
    private static final int CARD_H = 1350;
    // 비주얼 확인용 산출 위치 — 테스트 임시 디렉터리는 소멸되므로 build/ 아래에 남긴다.
    private static final Path PREVIEW_DIR = Path.of("build", "card-previews");

    private final SpringTemplateEngine engine = RenderConfig.offlineTemplateEngine();

    // 장문 감상 — 시안 기준 크기로는 넘치게 만들어 autofit 축소를 실제로 태운다(AC-65 성격의 스모크).
    private static final String LONG_TASTE =
            "새콤하고 좋았음. 식으니까 자몽 같은 신맛이 올라옴. 첫 모금엔 살짝 떫은 듯했는데 온도가 내려가면서 단맛이 확 "
                    + "올라오고, 마지막엔 홍차 같은 여운이 길게 남았음. 뜨거울 때는 견과류 고소함이 앞서다가 미지근해지니 "
                    + "베르가못 향이 또렷해짐. 얼음 넣어 마셔보니 산미가 더 선명해지고 단맛은 얌전해짐. 이 원두는 식혀 "
                    + "마시는 게 정답인 듯. 다음엔 물 온도를 2도쯤 낮추고 분쇄를 한 클릭 굵게 가보고 싶음. "
                    + "두 번째 잔은 우유를 조금 섞어봤는데 캔디 같은 단맛이 라떼에서도 살아 있어서 놀랐음. 허브 피니쉬는 "
                    + "우유에 묻히긴 했지만 목 넘김 끝에 은은하게 남음. 자몽 산미를 좋아하면 꼭 블랙으로 먼저 마셔보길. "
                    + "전체적으로 향의 결이 시간대마다 달라져서 한 잔을 오래 두고 마시는 재미가 있는 커피였음.";

    private static NoteView.TasteCard fixtureCard() {
        return new NoteView.TasteCard(
                "레인보우 블렌드",
                "커피가게 동경",
                List.of(
                        new NoteView.BeanLine("에티오피아 예가체프 헤어룸", "워시드"),
                        new NoteView.BeanLine("콜롬비아 우일라 카투라", "내추럴"),
                        new NoteView.BeanLine("브라질 세하도 버번", "펄프드 내추럴")),
                "라이트",
                List.of("자몽", "베르가못", "허브 피니쉬", "홍차", "캔디"),
                LocalDate.parse("2026-07-18"),
                LONG_TASTE,
                Rating.GOOD);
    }

    @ParameterizedTest
    @EnumSource(Theme.class)
    @DisplayName("AC-Δ5/AC-Δ9/FR-13: 감상 카드가 오프라인·외부 요청 0으로 4:5 JPG로 구워지고 autofit이 동작한다")
    void bakesTasteCardOfflineWithAutofit(Theme theme, @TempDir Path work) throws Exception {
        copyBundledAssets(theme, work);

        Context ctx = new Context(Locale.KOREAN);
        ctx.setVariable("fmt", new KoreanDates());
        ctx.setVariable("rs", new RatingStyle());
        ctx.setVariable("amt", new RecipeAmounts());
        ctx.setVariable("mascotHref", "mascot-face.png");
        ctx.setVariable("card", fixtureCard());
        String html = engine.process(theme.id() + "/taste", ctx);

        Path htmlFile = work.resolve("taste.html");
        Files.writeString(htmlFile, html);
        Path out = work.resolve("taste-" + theme.id() + ".jpg");

        List<String> nonFileRequests = new CopyOnWriteArrayList<>();
        double fittedPx;
        boolean autofitDone;
        try (Playwright pw = Playwright.create();
             Browser browser = pw.chromium().launch()) {
            // 프로덕션 렌더러(PlaywrightCardImageRenderer)와 동일 계약: 오프라인 + 4:5 뷰포트 + DSF 1(ADR-11).
            BrowserContext ctx2 = browser.newContext(new Browser.NewContextOptions()
                    .setOffline(true)
                    .setViewportSize(CARD_W, CARD_H)
                    .setDeviceScaleFactor(1));
            Page page = ctx2.newPage();
            // AC-Δ9: 렌더가 발생시키는 전 요청을 수집 — file:// 외(외부 네트워크)가 하나라도 있으면 실패.
            page.onRequest(req -> {
                if (!req.url().startsWith("file://")) {
                    nonFileRequests.add(req.url());
                }
            });
            page.navigate(htmlFile.toUri().toString());
            // 폰트 로드 후 autofit 완료 마커(TΔ5a 렌더러 대기 계약과 동일 신호)를 기다린 뒤 찍는다.
            page.waitForFunction("() => document.documentElement.hasAttribute('data-autofit-done')");
            autofitDone = (Boolean) page.evaluate(
                    "() => document.documentElement.hasAttribute('data-autofit-done')");
            fittedPx = ((Number) page.evaluate(
                    "() => parseFloat(getComputedStyle(document.querySelector('[data-autofit]')).fontSize)"))
                    .doubleValue();
            page.screenshot(new Page.ScreenshotOptions()
                    .setType(ScreenshotType.JPEG)
                    .setQuality(90)
                    .setPath(out));
        }

        assertTrue(nonFileRequests.isEmpty(), "외부 네트워크 요청 0(AC-Δ9) — 발생분: " + nonFileRequests);
        assertTrue(autofitDone, "autofit 완료 마커 세팅(TΔ5a 대기 계약)");
        // 장문 감상 → 기준 크기(type-a 16.5px/type-b 13px)에서 축소됐고 하한(10.5px) 아래로는 내려가지 않는다.
        double basePx = theme == Theme.TYPE_A ? 16.5 : 13.0;
        assertTrue(fittedPx < basePx, "장문 감상이 autofit으로 축소됨: " + fittedPx + "px < " + basePx + "px");
        assertTrue(fittedPx >= 10.5 - 0.001, "autofit 하한(시안 값 10.5px) 준수: " + fittedPx + "px");

        assertTrue(Files.exists(out), "카드 JPG 생성");
        byte[] bytes = Files.readAllBytes(out);
        assertTrue(bytes.length > 3
                        && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF,
                "유효한 JPEG 시그니처");
        BufferedImage img = ImageIO.read(out.toFile());
        assertEquals(CARD_W, img.getWidth(), "카드 폭 = 1080 (4:5)");
        assertEquals(CARD_H, img.getHeight(), "카드 높이 = 1350 (4:5)");

        // 비주얼 확인용 사본(AC-Δ5는 자동 단언 불가 항목 — 백엔드 CLAUDE.md §5.2 수동 체크 분리).
        Files.createDirectories(PREVIEW_DIR);
        Files.copy(out, PREVIEW_DIR.resolve(out.getFileName()), StandardCopyOption.REPLACE_EXISTING);
    }

    // 템플릿이 상대 참조하는 번들 자산(폰트·마스코트)을 렌더 base 디렉터리에 깐다 — TΔ5a 렌더러 copyFonts/copyMascot와 동일 역할.
    private void copyBundledAssets(Theme theme, Path work) throws Exception {
        Path fontsDir = Files.createDirectories(work.resolve("fonts"));
        for (String font : theme.fontFiles()) {
            try (InputStream in = getClass().getResourceAsStream("/assets/fonts/" + font)) {
                Assumptions.assumeTrue(in != null, "폰트 미번들: /assets/fonts/" + font);
                Files.copy(in, fontsDir.resolve(font), StandardCopyOption.REPLACE_EXISTING);
            }
        }
        try (InputStream in = getClass().getResourceAsStream("/assets/mascot-face.png")) {
            Assumptions.assumeTrue(in != null, "마스코트 미번들: /assets/mascot-face.png");
            Files.copy(in, work.resolve("mascot-face.png"), StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
