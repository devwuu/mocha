package com.devwuu.mocha.render;

import com.devwuu.mocha.config.RenderConfig;
import com.devwuu.mocha.domain.Recipe;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TΔ4b(changes/0021): 레시피 카드 템플릿({@code <theme>/recipe.html}) 이식 계약 검증 — 실 래스터화 없이
 * Thymeleaf 산출 HTML로 FR-7 필드↔영역 매핑·변형 분기·파생 표기·null 분기를 결정론적으로 본다(백엔드 CLAUDE.md §5.2).
 * 실 Chromium 스모크(비주얼·오프라인 렌더)는 {@link RecipeCardChromiumSmokeTest}(태그 분리).
 * <ul>
 *   <li>AC-24: 발화 추출 레시피(원두량·물량)가 레시피 카드에 표시</li>
 *   <li>AC-25: 값 있는 항목만 표시(없는 행·영역 숨김)</li>
 *   <li>AC-66: grind 정규화 문자열이 값+그라인더 서브라벨로 렌더</li>
 *   <li>AC-76: yield_ml·time_sec 단위·포맷 렌더, 비율은 둘 다 있을 때만</li>
 *   <li>AC-Δ5: FR-7 매핑 렌더·이모티콘 없음·방식 뱃지</li>
 *   <li>AC-Δ9: CDN·외부 URL 참조 없음(로컬 번들 폰트 참조)</li>
 * </ul>
 */
class RecipeCardTemplateTest {

    private final SpringTemplateEngine engine = RenderConfig.offlineTemplateEngine();

    // 구 템플릿(note.html·RatingStyle.emoji)이 쓰던 장식 이모지 전수 — 이식 편차①로 레시피 카드에도 없어야 한다.
    private static final List<String> BANNED_EMOJIS = List.of("☕", "🌍", "💧", "🔥", "🏷", "🐾", "🔗", "❤", "😋", "🙂", "😓");

    // Recipe 필드 순서: method, doseG, waterMl, yieldMl, timeSec, tempC, grind, machine, pouring, feedback
    private static NoteView.RecipeCard handdripCard() {
        return new NoteView.RecipeCard(
                "레인보우 블렌드", "커피가게 동경", LocalDate.parse("2026-07-18"),
                new Recipe("핸드드립", 15.0, 240.0, null, 160.0, 92.0, "210클릭 (매버릭 2.0)", null,
                        "뜸 40ml 30초 → 100ml → 100ml", "첫 모금이 살짝 떫었으니 다음엔 분쇄를 한 단계 굵게 가볼 것."));
    }

    private static NoteView.RecipeCard espressoCard() {
        return new NoteView.RecipeCard(
                "레인보우 블렌드", "커피가게 동경", LocalDate.parse("2026-07-18"),
                new Recipe("에스프레소", 18.0, null, 36.0, 28.0, 93.0, "90클릭 (매버릭 2.0)", "게이지아 클래식",
                        null, "뒷맛이 살짝 쓰니 다음엔 분쇄를 두 클릭 굵게 하고 추출량을 열어볼 것."));
    }

    private String render(Theme theme, NoteView.RecipeCard card) {
        Context ctx = new Context(Locale.KOREAN);
        // TΔ5a 렌더러 baseContext와 같은 컨텍스트 계약 — fmt/rs/amt 헬퍼 + 마스코트 상대 경로.
        ctx.setVariable("fmt", new KoreanDates());
        ctx.setVariable("rs", new RatingStyle());
        ctx.setVariable("amt", new RecipeAmounts());
        ctx.setVariable("mascotHref", "mascot-face.png");
        ctx.setVariable("card", card);
        return engine.process(theme.id() + "/recipe", ctx);
    }

    @ParameterizedTest
    @EnumSource(Theme.class)
    @DisplayName("AC-24/AC-66/AC-Δ5: 핸드드립 변형이 FR-7 매핑(원두·물·분쇄도 타일 + 물온도·푸어링·총 시간)을 렌더한다")
    void rendersHanddripMapping(Theme theme) {
        String html = render(theme, handdripCard());

        // ②: 로스터리·커피명 + 방식 뱃지
        assertTrue(html.contains("레인보우 블렌드") && html.contains("커피가게 동경"), "커피명·로스터리");
        assertTrue(html.contains("핸드드립"), "방식 뱃지 = method 그대로");
        // ③ 타일: 원두·물·분쇄도(값+그라인더 서브라벨 — AC-66)
        assertTrue(html.contains("15g"), "원두량(AC-24)");
        assertTrue(html.contains("240ml"), "물량(AC-24)");
        assertTrue(html.contains("210클릭"), "분쇄값(AC-66)");
        assertTrue(html.contains("매버릭 2.0 기준"), "그라인더 서브라벨(AC-66)");
        // ③ 상세 행: 물온도·푸어링 + 총 시간(60초 기준 포맷 — AC-76)
        assertTrue(html.contains("92℃"), "물온도");
        assertTrue(html.contains("뜸 40ml 30초 → 100ml → 100ml"), "푸어링 자유 텍스트 한 행");
        assertTrue(html.contains("2분 40초"), "time_sec 160 → '2분 40초'(AC-76)");
        // yield 없음 → 추출량·비율 표기 없음(AC-76 음성 가드)
        assertFalse(html.contains("추출량") || html.contains("비율"), "추출량 없으면 행·비율 생략");
        // ④: 피드백 autofit
        assertTrue(html.contains("첫 모금이 살짝 떫었으니"), "피드백 본문");
        assertTrue(html.contains("data-autofit=\"10.5\""), "피드백 영역 autofit(하한 = 시안 값)");
        // ①: 날짜 헤더(테마별 포맷)
        assertTrue(theme == Theme.TYPE_A ? html.contains("2026. 7. 18") : html.contains("2026년 7월 18일"),
                "날짜 헤더");
        // 시안 예시 데이터가 남지 않았다(편차③ — Thymeleaf 바인딩 대체)
        assertFalse(html.contains("봄맞이 블렌드"), "시안 예시 데이터 잔존 없음");
    }

    @ParameterizedTest
    @EnumSource(Theme.class)
    @DisplayName("AC-76/AC-Δ5: 에스프레소 변형이 도징·추출량(+비율)·추출 시간 타일과 분쇄도·머신 행을 렌더한다")
    void rendersEspressoMapping(Theme theme) {
        String html = render(theme, espressoCard());

        assertTrue(html.contains("에스프레소"), "방식 뱃지 = method 그대로");
        assertTrue(html.contains("도징") && html.contains("18g"), "도징 타일");
        assertTrue(html.contains("추출량") && html.contains("36ml"), "추출량 타일 — yield_ml 기준 ml 표기(AC-76)");
        assertTrue(html.contains("비율 1 : 2"), "비율 파생 표기 — 둘 다 있을 때만(AC-76)");
        assertTrue(html.contains("추출 시간") && html.contains("28초"), "추출 시간 타일 — 60초 미만 'N초'(AC-76)");
        assertTrue(html.contains("90클릭") && html.contains("매버릭 2.0 기준"), "분쇄도 행 값+서브라벨(AC-66)");
        assertTrue(html.contains("게이지아 클래식"), "머신 행");
        assertTrue(html.contains("93℃"), "온도(머신 병기 또는 단독 행)");
        assertTrue(html.contains("뒷맛이 살짝 쓰니"), "피드백 본문");
        // water 없음 → 물 라벨 없음, 핸드드립 전용 라벨(원두·물온도·푸어링)도 없음
        assertFalse(html.contains(">물<"), "물 없으면 행 생략");
        assertFalse(html.contains("원두") || html.contains("푸어링"), "에스프레소 변형에 핸드드립 전용 라벨 없음");
    }

    @ParameterizedTest
    @EnumSource(Theme.class)
    @DisplayName("AC-25: 값 없는 항목은 행·영역·뱃지를 통째 숨긴다 — 일부 항목만 있는 레시피")
    void hidesAbsentFields(Theme theme) {
        NoteView.RecipeCard sparse = new NoteView.RecipeCard(
                "핸드드립 하우스 블렌드", null, LocalDate.parse("2026-07-18"),
                new Recipe(null, 15.0, null, null, null, null, null, null, null, "간단히 내렸음."));
        String html = render(theme, sparse);

        assertTrue(html.contains("15g"), "값 있는 항목(원두량)은 표시(AC-25)");
        assertTrue(html.contains("간단히 내렸음."), "피드백 표시");
        assertFalse(html.contains("에스프레소"), "method 없음 → 뱃지 숨김·핸드드립 변형 기본");
        assertFalse(html.contains(">물<"), "물 없음 → 타일 숨김");
        assertFalse(html.contains("분쇄도") || html.contains("물온도") || html.contains("푸어링")
                        || html.contains("머신") || html.contains("추출량") || html.contains("추출 시간")
                        || html.contains("비율"),
                "값 없는 행·상세 영역 전부 숨김(AC-25)");
    }

    @ParameterizedTest
    @EnumSource(Theme.class)
    @DisplayName("AC-76: 비율은 도징·추출량 둘 다 있을 때만 — 도징 없는 에스프레소는 추출량만 표시")
    void ratioOmittedWhenDoseMissing(Theme theme) {
        NoteView.RecipeCard noDose = new NoteView.RecipeCard(
                "레인보우 블렌드", null, LocalDate.parse("2026-07-18"),
                new Recipe("에스프레소", null, null, 36.0, null, null, null, null, null, "다음엔 도징도 기록할 것."));
        String html = render(theme, noDose);
        assertTrue(html.contains("36ml"), "추출량 표시");
        assertFalse(html.contains("비율"), "도징 없으면 비율 생략(AC-76)");
    }

    @ParameterizedTest
    @EnumSource(Theme.class)
    @DisplayName("AC-Δ9: 레시피 카드 HTML은 외부 URL을 참조하지 않는다 — 로컬 번들 폰트만(ADR-11)")
    void referencesNoExternalUrls(Theme theme) {
        String html = render(theme, handdripCard());
        assertFalse(html.contains("http://") || html.contains("https://"), "CDN 등 외부 URL 참조 없음");
        assertTrue(html.contains("fonts/"), "로컬 번들 폰트 상대 참조");
        if (theme == Theme.TYPE_B) {
            assertTrue(html.contains("src=\"mascot-face.png\""), "마스코트 클래스패스 번들 복사본 상대 참조(편차④)");
        }
    }

    @ParameterizedTest
    @EnumSource(Theme.class)
    @DisplayName("AC-Δ5: 레시피 카드에 이모티콘이 없다(이식 편차①)")
    void hasNoEmoji(Theme theme) {
        for (NoteView.RecipeCard card : List.of(handdripCard(), espressoCard())) {
            String html = render(theme, card);
            for (String emoji : BANNED_EMOJIS) {
                assertFalse(html.contains(emoji), "이모티콘 없음: " + emoji);
            }
        }
    }

    @Test
    @DisplayName("변형 분기 POLICY: method에 '에스프레소' 포함이면 에스프레소 변형, 그 외(null 포함)는 핸드드립 변형")
    void espressoLayoutMatchesByContains() {
        assertTrue(cardWithMethod("에스프레소").espressoLayout());
        assertTrue(cardWithMethod("아이스 에스프레소").espressoLayout(), "자유 문자열 포함 매칭");
        assertFalse(cardWithMethod("핸드드립").espressoLayout());
        assertFalse(cardWithMethod(null).espressoLayout(), "method 없으면 핸드드립 변형 기본");
    }

    private static NoteView.RecipeCard cardWithMethod(String method) {
        return new NoteView.RecipeCard("커피", null, LocalDate.parse("2026-07-18"),
                new Recipe(method, 15.0, null, null, null, null, null, null, null, null));
    }
}
