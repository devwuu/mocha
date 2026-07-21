package com.devwuu.mocha.render;

import com.devwuu.mocha.config.RenderConfig;
import com.devwuu.mocha.domain.Recipe;
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
 * TΔ4b(changes/0021): 레시피 카드 실 렌더 스모크 — 두 테마의 {@code recipe.html}을 핸드드립·에스프레소
 * 변형 각 1건씩 헤드리스 Chromium으로 실제 래스터화한다. 실 브라우저 기동이라 {@code @Tag("chromium")}로
 * 분리({@code ./gradlew chromiumTest}).
 * <ul>
 *   <li>AC-Δ5: 두 테마 × 두 변형 레시피 카드가 1080×1350(4:5) 유효 JPG로 구워진다 — 비주얼 확인용 산출은
 *       {@code build/card-previews/}에 남긴다.</li>
 *   <li>AC-Δ9: 렌더 중 외부 네트워크 요청 0 — 오프라인 컨텍스트 + 전 요청 {@code file://} 검증.</li>
 *   <li>FR-13: 장문 피드백이 autofit으로 축소된다(기준 크기 미만 확인) + 완료 마커(TΔ5a 대기 계약) 세팅.</li>
 * </ul>
 */
@Tag("chromium")
class RecipeCardChromiumSmokeTest {

    private static final int CARD_W = 1080;
    private static final int CARD_H = 1350;
    // 비주얼 확인용 산출 위치 — 테스트 임시 디렉터리는 소멸되므로 build/ 아래에 남긴다.
    private static final Path PREVIEW_DIR = Path.of("build", "card-previews");

    private final SpringTemplateEngine engine = RenderConfig.offlineTemplateEngine();

    // 장문 피드백 — 시안 기준 크기로는 넘치게 만들어 autofit 축소를 실제로 태운다(AC-65 성격의 스모크).
    private static final String LONG_FEEDBACK =
            "첫 모금이 살짝 떫었으니 다음엔 분쇄를 한 단계 굵게(220클릭) 가고, 물 온도는 90℃로 낮춰볼 것. 푸어링은 "
                    + "지금 그대로 두되 뜸 시간을 10초 늘려서 가스를 더 빼보기. 원두가 물을 빨아들이는 속도가 지난번보다 "
                    + "빨라서 배전 후 시간이 꽤 지난 듯 — 남은 원두는 일주일 안에 소진할 것. 식었을 때가 제일 맛있으니 "
                    + "서둘러 마시지 말고, 다음 시도에서는 총 추출 시간을 3분 안쪽으로 끊어서 뒷맛의 텁텁함이 사라지는지 "
                    + "확인해 보기. 추출량을 조금 줄이는 대신 도징을 1g 늘리는 비교 실험도 해볼 것. 컵을 데워서 마시면 "
                    + "향이 더 오래 가는 것 같으니 다음엔 예열도 잊지 말 것.";

    // Recipe 필드 순서: method, doseG, waterMl, yieldMl, timeSec, tempC, grind, machine, pouring, feedback
    private static NoteView.RecipeCard handdripCard() {
        return new NoteView.RecipeCard(
                "레인보우 블렌드", "커피가게 동경", LocalDate.parse("2026-07-18"),
                new Recipe("핸드드립", 15.0, 240.0, null, 160.0, 92.0, "210클릭 (매버릭 2.0)", null,
                        "뜸 40ml 30초 → 100ml → 100ml", LONG_FEEDBACK));
    }

    private static NoteView.RecipeCard espressoCard() {
        return new NoteView.RecipeCard(
                "레인보우 블렌드", "커피가게 동경", LocalDate.parse("2026-07-18"),
                new Recipe("에스프레소", 18.0, null, 36.0, 28.0, 93.0, "90클릭 (매버릭 2.0)", "게이지아 클래식",
                        null, LONG_FEEDBACK));
    }

    @ParameterizedTest
    @EnumSource(Theme.class)
    @DisplayName("AC-Δ5/AC-Δ9/FR-13: 핸드드립 레시피 카드가 오프라인·외부 요청 0으로 4:5 JPG로 구워지고 autofit이 동작한다")
    void bakesHanddripRecipeCard(Theme theme, @TempDir Path work) throws Exception {
        bakeAndAssert(theme, handdripCard(), "recipe-handdrip", work);
    }

    @ParameterizedTest
    @EnumSource(Theme.class)
    @DisplayName("AC-Δ5/AC-Δ9/FR-13: 에스프레소 레시피 카드가 오프라인·외부 요청 0으로 4:5 JPG로 구워지고 autofit이 동작한다")
    void bakesEspressoRecipeCard(Theme theme, @TempDir Path work) throws Exception {
        bakeAndAssert(theme, espressoCard(), "recipe-espresso", work);
    }

    private void bakeAndAssert(Theme theme, NoteView.RecipeCard card, String previewName, Path work) throws Exception {
        copyBundledAssets(theme, work);

        Context ctx = new Context(Locale.KOREAN);
        ctx.setVariable("fmt", new KoreanDates());
        ctx.setVariable("rs", new RatingStyle());
        ctx.setVariable("amt", new RecipeAmounts());
        ctx.setVariable("mascotHref", "mascot-face.png");
        ctx.setVariable("card", card);
        String html = engine.process(theme.id() + "/recipe", ctx);

        Path htmlFile = work.resolve("recipe.html");
        Files.writeString(htmlFile, html);
        Path out = work.resolve(previewName + "-" + theme.id() + ".jpg");

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
        // 장문 피드백 → 기준 크기(type-a 15.5px/type-b 13px)에서 축소됐고 하한(10.5px) 아래로는 내려가지 않는다.
        double basePx = theme == Theme.TYPE_A ? 15.5 : 13.0;
        assertTrue(fittedPx < basePx, "장문 피드백이 autofit으로 축소됨: " + fittedPx + "px < " + basePx + "px");
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
