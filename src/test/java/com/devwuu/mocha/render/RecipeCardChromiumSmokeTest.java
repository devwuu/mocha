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
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
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
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TΔ4b(changes/0021) → TΔ22(changes/0030) 재편: 레시피 카드 실 렌더 스모크 — 두 테마의 {@code recipe.html}을
 * 헤드리스 Chromium으로 실제 래스터화한다. 실 브라우저 기동이라 {@code @Tag("chromium")}로 분리
 * ({@code ./gradlew chromiumTest}).
 *
 * <p><b>스모크 축이 «추출 방식»에서 «타일 수»로 바뀌었다</b>(TΔ22). 구 축은 핸드드립·에스프레소 2변형이었는데,
 * 그것이 축이었던 이유는 템플릿이 {@code method.contains("에스프레소")}로 레이아웃을 갈랐기 때문이다.
 * ADR-87이 그 분기를 없앤 지금 <b>method는 뱃지 문자열 하나만 바꾸므로 두 변형을 굽는 것은 같은 카드를
 * 두 번 굽는 것</b>이다. 실제로 갈리는 것은 <b>타일이 몇 개 차는가</b>이고, 그것이 그리드 행 수·남는 빈 칸·
 * 텍스트 블록에 남는 세로 공간을 결정한다 — autofit·잘림이 걸리는 축이 정확히 여기다(AC-Δ10·Δ11).
 *
 * <p>단언은 HTML 층({@link RecipeCardTemplateTest})이 못 보는 것만 진다 — <b>실 브라우저가 있어야만
 * 답할 수 있는 것</b>:
 * <ul>
 *   <li><b>4:5 유지</b>: 1080×1350 유효 JPG(AC-Δ5 승계). 비주얼 확인용 산출은 {@code build/card-previews/}.</li>
 *   <li><b>그리드 기하</b>: 3열이 실제로 잡히고 타일 수에 맞는 행이 찬다(6→2행·4→2행·0→그리드 없음).
 *       HTML 문자열은 {@code grid-template-columns}가 «먹었는지»를 말해주지 못한다(AC-Δ11).</li>
 *   <li><b>푸터 고정</b>(type-a): 블록이 숨어도 푸터가 위로 딸려 올라오지 않는다 — TΔ20이 시안에 없는
 *       {@code flex:1} 래퍼를 넣은 이유이고, 그 근거는 <b>기하</b>라서 HTML로는 확인되지 않는다.</li>
 *   <li><b>autofit·잘림</b>: 아래 «용량» 항 참조.</li>
 *   <li><b>AC-Δ9</b>: 렌더 중 외부 네트워크 요청 0 — 오프라인 컨텍스트 + 전 요청 {@code file://} 검증.</li>
 * </ul>
 *
 * <p><b>«잘림 없음»은 무조건이 아니라 카드 용량 안에서 성립한다</b>(TΔ22 실측). 하한(10.5px)에서도 안 들어가는
 * 분량은 잘리고, 그 상태는 <b>이미 결정된 관측 대상</b>이다 — {@link PlaywrightCardImageRenderer}가
 * {@code data-autofit-floor}를 세어 «잘림 가능»으로 warn 로그를 남긴다(plan §6 POLICY). 그래서 이 클래스는
 * 둘을 나눠 단언한다:
 * <ul>
 *   <li>{@link #bakesRecipeCardAcrossTileCounts} — 실사용 분량 픽스처에서 <b>잘림 0</b>.
 *       분량 근거는 DB 실측(2026-08-10)이다: {@code detail} 34~62자 · {@code feedback} 32~40자.
 *       픽스처는 그 최댓값의 약 2배를 쓴다.</li>
 *   <li>{@link #longTextReachesFloorInsteadOfOverflowingTheCard} — 용량을 넘는 장문에서 <b>하한까지 내려가고
 *       마커가 뜬다</b>. 6타일 기준 한계는 type-a가 더 빡빡하다(텍스트 블록 1개당 76px vs type-b 100px):
 *       type-a 상세 ~120자·피드백 ~160자, type-b 각 ~240자. 이 경계를 테스트가 기록한다.</li>
 * </ul>
 */
@Tag("chromium")
class RecipeCardChromiumSmokeTest {

    private static final int CARD_W = 1080;
    private static final int CARD_H = 1350;
    // 비주얼 확인용 산출 위치 — 테스트 임시 디렉터리는 소멸되므로 build/ 아래에 남긴다.
    private static final Path PREVIEW_DIR = Path.of("build", "card-previews");
    // autofit 하한은 두 테마 공통 시안 값(data-autofit="10.5") — 여기 닿으면 더 줄일 곳이 없다는 뜻이다.
    private static final double FLOOR_PX = 10.5;

    private final SpringTemplateEngine engine = RenderConfig.offlineTemplateEngine();

    // 실사용 분량(DB 실측 detail 34~62자)의 약 2배 — 여유가 있는지 보려면 실측 그대로는 너무 짧다.
    private static final String DETAIL =
            "뜸 40ml로 30초 불린 뒤 중심에서 바깥으로 원을 그리며 100ml, 물줄기가 가라앉으면 다시 100ml. "
                    + "마지막은 가장자리를 건드리지 않고 중심만 얇게 부었다.";
    // 실사용 분량(feedback 32~40자)의 약 3배.
    private static final String FEEDBACK =
            "첫 모금이 살짝 떫었으니 다음엔 분쇄를 한 단계 굵게(220클릭) 가고, 물 온도는 90℃로 낮춰볼 것. "
                    + "뜸 시간도 10초쯤 늘려서 가스를 더 빼보기.";
    // 카드 용량을 넘기는 장문 — 하한 도달(= 프로덕션 warn 로그 조건)을 실제로 만든다.
    private static final String OVERFLOWING_FEEDBACK = FEEDBACK
            + " 원두가 물을 빨아들이는 속도가 지난번보다 빨라서 배전 후 시간이 꽤 지난 듯 — 남은 원두는 일주일 안에 "
            + "소진할 것. 식었을 때가 제일 맛있으니 서둘러 마시지 말고, 다음 시도에서는 총 추출 시간을 3분 안쪽으로 "
            + "끊어서 뒷맛의 텁텁함이 사라지는지 확인해 보기. 추출량을 조금 줄이는 대신 도징을 1g 늘리는 비교 실험도 "
            + "해볼 것. 컵을 데워서 마시면 향이 더 오래 가는 것 같으니 다음엔 예열도 잊지 말 것.";

    // Recipe 필드 순서: method, doseG, waterMl, yieldMl, timeSec, tempC, grind, grinder, detail, feedback
    // (changes/0030 ADR-86 재편 — 구 machine 폐기 · 구 pouring → detail · grind 수치 + grinder 분리)

    /**
     * 스모크 1건의 기대 — 타일 수 축(TΔ22).
     *
     * @param name          미리보기 파일명 접미
     * @param card          렌더 대상
     * @param tiles         그리드에 차야 하는 타일 수(0이면 그리드 영역 자체가 없다 — AC-Δ11)
     * @param rows          3열 그리드가 실제로 잡는 행 수
     * @param autofitBlocks 살아 있는 autofit 텍스트 블록 수(값 없는 블록은 라벨까지 숨는다 — AC-25)
     */
    private record Case(String name, NoteView.RecipeCard card, int tiles, int rows, int autofitBlocks) {
        @Override
        public String toString() {
            return name; // 파라미터 표시명 — record 기본 toString은 레시피 전문이라 리포트가 안 읽힌다.
        }
    }

    private static NoteView.RecipeCard card(Recipe recipe) {
        return new NoteView.RecipeCard(
                "레인보우 블렌드", "커피가게 동경", LocalDate.parse("2026-07-18"), recipe);
    }

    static Stream<Arguments> cases() {
        List<Case> cases = List.of(
                // ① 6타일 = 수치 필드 전량. method는 구 분기 어느 쪽도 아니던 "아메리카노"(delta §1.3의 실측값)
                //    — 그 값으로도 6개가 전부 타일에 오르는 것이 AC-Δ10의 실물 증거다. 3열 × 2행이 꽉 찬 상태라
                //    텍스트 블록에 남는 공간이 가장 좁다(용량 하한 케이스).
                new Case("6tiles", card(new Recipe("아메리카노", 15.0, 240.0, 100.0, 160.0, 92.0, 210.0,
                        "매버릭 2.0", DETAIL, FEEDBACK)), 6, 2, 2),
                // ② 부분값 = 4타일(원두·시간·온도·추출량). 둘째 행에 빈 칸 2개가 남는다 — «남는 칸은 시안 그대로
                //    비운다»(2026-08-10 사용자 확정, TΔ20·TΔ21)가 래스터에서 어떻게 보이는지 확인하는 자리다.
                //    grinder만 있고 grind가 없어 분쇄도 타일은 서지 않고(TΔ18 확정), feedback이 null이라
                //    피드백 블록이 통째로 숨는다 — type-a는 이때도 푸터가 제자리여야 한다(TΔ20 래퍼).
                new Case("partial", card(new Recipe("에스프레소", 18.0, null, 36.0, 28.0, 93.0, null,
                        "매버릭 2.0", "게이지아 클래식으로 내림. 예열 10분.", null)), 4, 2, 1),
                // ③ 값 없음 = 타일 0 → 그리드 영역 자체가 없다(AC-Δ11). method도 null이라 뱃지까지 빠지고,
                //    수치가 아닌 분쇄 표현은 detail에 남는다(AC-Δ7의 저장 결과가 카드에서 어떻게 보이는가).
                new Case("no-tiles", card(new Recipe(null, null, null, null, null, null, null,
                        null, "중간 굵기로 갈아서 천천히 부었다. 계량은 눈대중.", FEEDBACK)), 0, 0, 2));
        return Stream.of(Theme.values())
                .flatMap(theme -> cases.stream().map(c -> Arguments.of(theme, c)));
    }

    @ParameterizedTest(name = "{0} · {1}")
    @MethodSource("cases")
    @DisplayName("AC-Δ10/Δ11/Δ5/Δ9·FR-13: 타일 수 3종 × 두 테마가 4:5 JPG로 구워지고 그리드·autofit·잘림 없음을 만족한다")
    void bakesRecipeCardAcrossTileCounts(Theme theme, Case testCase, @TempDir Path work) throws Exception {
        Map<String, Object> m = bake(theme, testCase.card(), "recipe-" + testCase.name(), work);

        // ── 그리드 기하(AC-Δ11) — 브라우저만 답할 수 있는 것: 3열이 실제로 잡혔고 타일 수만큼 행이 찼는가.
        assertEquals(testCase.tiles() == 0 ? 0 : 1, num(m, "gridCount"),
                "타일이 없으면 그리드 영역 자체가 없고, 있으면 그리드는 하나다(AC-Δ11)");
        assertEquals(testCase.tiles(), num(m, "tileCount"), "값 있는 항목 수만큼 타일이 찬다");
        assertEquals(testCase.rows(), num(m, "rowCount"), "3열 배치에서 실제로 잡힌 행 수");
        if (testCase.tiles() > 0) {
            assertEquals(3, num(m, "columnCount"), "그리드는 3열이다 — 남는 칸은 시안 그대로 빈다");
        }

        // ── autofit(FR-13) — 값 없는 블록은 통째로 숨으므로 대상 수부터 기대와 맞아야 한다(AC-25).
        assertEquals(testCase.autofitBlocks(), num(m, "autofitCount"),
                "살아 있는 autofit 텍스트 블록 수(값 없는 블록은 라벨까지 숨는다)");
        double basePx = basePx(theme);
        assertTrue(dbl(m, "maxFontPx") <= basePx + 0.001,
                "autofit은 기준 크기(" + basePx + "px)를 넘겨 키우지 않는다: " + dbl(m, "maxFontPx") + "px");
        assertTrue(dbl(m, "minFontPx") >= FLOOR_PX - 0.001,
                "autofit 하한(시안 값 10.5px) 준수: " + dbl(m, "minFontPx") + "px");

        // ── 잘림 없음 — 하한 도달(더 줄일 수 없어 넘친 상태)과 실제 오버플로를 따로 본다.
        //    전자만 보면 하한에 안 닿고 그냥 넘친 경우가, 후자만 보면 «더 줄일 여지가 없다»가 안 갈린다.
        assertEquals(0, num(m, "flooredCount"),
                "실사용 분량(DB 실측의 2~3배)에서는 autofit 하한에 닿지 않는다 — 닿으면 잘림 가능이다");
        assertEquals(0, num(m, "clippedCount"), "autofit 블록의 내용이 제 상자를 넘지 않는다");
        assertTrue(num(m, "cardOverflowY") <= 1,
                "카드 프레임(575px)을 세로로 넘치는 내용이 없다: +" + num(m, "cardOverflowY") + "px");
        assertTrue(num(m, "cardOverflowX") <= 1,
                "카드 프레임(460px)을 가로로 넘치는 내용이 없다: +" + num(m, "cardOverflowX") + "px");

        // ── 푸터 고정(type-a만 — type-b 시안에는 푸터가 없다, TΔ21). 프레임 안쪽 여백은 카드 padding 16 +
        //    프레임 padding-bottom 14 = 30px(무스케일)이고 화면 좌표로는 ×2.3478 ≈ 70px이다. 블록이 숨어
        //    푸터가 딸려 올라오면 이 값이 수백 px로 벌어진다 — TΔ20 래퍼가 지키는 성질이 이것이다.
        if (theme == Theme.TYPE_A) {
            assertTrue(num(m, "footerGap") <= 80,
                    "푸터가 카드 하단에 붙어 있다(블록 숨김에 딸려 올라오지 않는다): " + num(m, "footerGap") + "px");
        }
    }

    /**
     * 카드 용량을 넘는 장문은 <b>하한까지 줄이고 거기서 멈춘다</b> — 무한 축소도, 프레임 밖으로의 범람도 아니다.
     * <p>이것이 {@link PlaywrightCardImageRenderer}가 «잘림 가능»으로 warn 로그를 남기는 조건이고
     * (plan §6 POLICY), 동시에 <b>autofit이 실제로 축소를 수행한다</b>는 FR-13의 증거다 — 실사용 분량 픽스처는
     * 여유가 있어 축소가 안 걸리는 테마가 있다(type-b 기준 13px, 하한 10.5px).
     */
    @ParameterizedTest
    @EnumSource(Theme.class)
    @DisplayName("FR-13/plan §6: 용량을 넘는 장문은 autofit 하한까지만 줄고 마커가 뜬다 — 카드 밖으로 넘치지는 않는다")
    void longTextReachesFloorInsteadOfOverflowingTheCard(Theme theme, @TempDir Path work) throws Exception {
        NoteView.RecipeCard overflowing = card(new Recipe("아메리카노", 15.0, 240.0, 100.0, 160.0, 92.0, 210.0,
                "매버릭 2.0", DETAIL, OVERFLOWING_FEEDBACK));
        Map<String, Object> m = bake(theme, overflowing, "recipe-overflow", work);

        assertEquals(FLOOR_PX, dbl(m, "minFontPx"), 0.001,
                "장문은 기준 크기(" + basePx(theme) + "px)에서 하한까지 줄어든다");
        assertEquals(1, num(m, "flooredCount"),
                "하한에서도 안 들어가는 블록에 data-autofit-floor가 선다 — 렌더러가 이 수를 세어 warn 한다");
        // 넘친 내용은 상자 밖으로 새지 않는다 — 블록의 overflow:hidden이 자르고, 카드 프레임은 그대로다.
        assertTrue(num(m, "cardOverflowY") <= 1,
                "하한 도달 상태에서도 카드 프레임을 넘치지 않는다: +" + num(m, "cardOverflowY") + "px");
        assertTrue(num(m, "cardOverflowX") <= 1, "가로도 마찬가지: +" + num(m, "cardOverflowX") + "px");
    }

    /** 두 테마 시안의 텍스트 기준 크기 — autofit은 여기서 시작해 아래로만 움직인다. */
    private static double basePx(Theme theme) {
        return theme == Theme.TYPE_A ? 15.5 : 13.0;
    }

    /**
     * 카드 1건을 실제로 굽고 계측값을 돌려준다 — 4:5·유효 JPEG·외부 요청 0은 전 케이스 공통이라 여기서 진다.
     * 비주얼 확인용 사본을 {@code build/card-previews/}에 남긴다(AC-Δ5는 자동 단언 불가 항목 —
     * 백엔드 CLAUDE.md §5.2 수동 체크 분리).
     */
    private Map<String, Object> bake(Theme theme, NoteView.RecipeCard card, String previewName, Path work)
            throws Exception {
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
        Map<String, Object> measured;
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
            // 폰트 로드 후 autofit 완료 마커(TΔ5a 렌더러 대기 계약과 동일 신호)를 기다린 뒤 잰다.
            page.waitForFunction("() => document.documentElement.hasAttribute('data-autofit-done')");
            @SuppressWarnings("unchecked")
            Map<String, Object> evaluated = (Map<String, Object>) page.evaluate(MEASURE_JS);
            measured = evaluated;
            page.screenshot(new Page.ScreenshotOptions()
                    .setType(ScreenshotType.JPEG)
                    .setQuality(90)
                    .setPath(out));
        }

        assertTrue(nonFileRequests.isEmpty(), "외부 네트워크 요청 0(AC-Δ9) — 발생분: " + nonFileRequests);
        assertTrue((Boolean) measured.get("autofitDone"), "autofit 완료 마커 세팅(TΔ5a 대기 계약)");

        assertTrue(Files.exists(out), "카드 JPG 생성");
        byte[] bytes = Files.readAllBytes(out);
        assertTrue(bytes.length > 3
                        && (bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF,
                "유효한 JPEG 시그니처");
        BufferedImage img = ImageIO.read(out.toFile());
        assertEquals(CARD_W, img.getWidth(), "카드 폭 = 1080 (4:5)");
        assertEquals(CARD_H, img.getHeight(), "카드 높이 = 1350 (4:5)");

        Files.createDirectories(PREVIEW_DIR);
        Files.copy(out, PREVIEW_DIR.resolve(out.getFileName()), StandardCopyOption.REPLACE_EXISTING);
        return measured;
    }

    /**
     * 렌더 결과를 한 번에 재는 스크립트 — 왕복을 나누면 autofit 재실행 사이에 값이 갈릴 수 있어 한 번에 걷는다.
     * <p>그리드는 클래스명이 아니라 <b>계산된 {@code display:grid}</b>로 찾는다 — 두 테마의 타일 그리드는
     * 인라인 스타일이고 클래스가 없다(TΔ21이 {@code .tiles}/{@code .tile} 우회 클래스를 걷었다).
     * 행 수는 타일의 화면 좌표 {@code top}을 묶어 센다(스케일 래퍼가 걸려 있어 값 자체는 배율이 실린
     * 좌표지만, 같은 행이면 같은 값이라 묶음 수는 영향받지 않는다).
     * <p>푸터는 type-a에만 있어 없으면 {@code -1}로 돌려준다.
     */
    private static final String MEASURE_JS = """
            () => {
              const fits = [...document.querySelectorAll('[data-autofit]')];
              const grids = [...document.querySelectorAll('div')]
                  .filter(e => getComputedStyle(e).display === 'grid');
              const tiles = grids.length ? [...grids[0].children] : [];
              const px = e => parseFloat(getComputedStyle(e).fontSize);
              const card = document.querySelector('.scale > div');
              const footer = [...document.querySelectorAll('div')]
                  .find(e => e.children.length === 0 && e.textContent.trim().startsWith('\\u2014 커피 독후감'));
              return {
                autofitDone: document.documentElement.hasAttribute('data-autofit-done'),
                autofitCount: fits.length,
                flooredCount: fits.filter(e => e.hasAttribute('data-autofit-floor')).length,
                clippedCount: fits.filter(e => e.scrollHeight > e.clientHeight + 1).length,
                minFontPx: fits.length ? Math.min(...fits.map(px)) : -1,
                maxFontPx: fits.length ? Math.max(...fits.map(px)) : -1,
                gridCount: grids.length,
                tileCount: tiles.length,
                rowCount: new Set(tiles.map(t => Math.round(t.getBoundingClientRect().top))).size,
                columnCount: grids.length
                    ? getComputedStyle(grids[0]).gridTemplateColumns.trim().split(/\\s+/).length : 0,
                cardOverflowY: card.scrollHeight - card.clientHeight,
                cardOverflowX: card.scrollWidth - card.clientWidth,
                footerGap: footer
                    ? Math.round(card.getBoundingClientRect().bottom - footer.getBoundingClientRect().bottom) : -1
              };
            }
            """;

    private static int num(Map<String, Object> m, String key) {
        return ((Number) m.get(key)).intValue();
    }

    private static double dbl(Map<String, Object> m, String key) {
        return ((Number) m.get(key)).doubleValue();
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
