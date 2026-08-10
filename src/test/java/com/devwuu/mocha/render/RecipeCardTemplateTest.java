package com.devwuu.mocha.render;

import com.devwuu.mocha.config.RenderConfig;
import com.devwuu.mocha.domain.Recipe;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.thymeleaf.context.Context;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TΔ4b(changes/0021) 도입 · <b>TΔ20(changes/0030) 재이식</b>: 레시피 카드 템플릿({@code <theme>/recipe.html})
 * 이식 계약 검증 — 실 래스터화 없이 Thymeleaf 산출 HTML로 FR-7 필드↔영역 매핑·파생 표기·null 분기를
 * 결정론적으로 본다(백엔드 CLAUDE.md §5.2). 실 Chromium 스모크(비주얼·오프라인 렌더)는
 * {@link RecipeCardChromiumSmokeTest}(태그 분리).
 * <p><b>이 클래스가 무는 것은 «템플릿이 뷰 모델을 어떻게 그리는가»다.</b> 어느 값이 어느 타일에 가는지는
 * {@link NoteView.RecipeCard#tiles()}가 확정하고 {@link NoteViewRecipeCardTest}가 그 층을 단언한다 —
 * 여기서는 그 목록이 <b>행 우선 순서 그대로</b> 그리드에 흘러 들어가는지, 없는 영역이 숨는지를 본다.
 * <ul>
 *   <li>AC-24: 발화 추출 레시피(원두량·물량)가 레시피 카드에 표시</li>
 *   <li>AC-25: 값 있는 항목만 표시(없는 타일·영역 숨김)</li>
 *   <li>AC-76: yield_ml·time_sec 단위·포맷 렌더, 비율은 둘 다 있을 때만</li>
 *   <li>AC-Δ8: 카드에 "머신"·"푸어링" 라벨이 없다(구 필드 폐기 — ADR-86)</li>
 *   <li>AC-Δ10: {@code method}가 어느 값이어도 레이아웃이 갈리지 않는다(방식 분기 폐기 — ADR-87)</li>
 *   <li>AC-Δ11: 값 6·3·2·0개 각각의 그리드 배치</li>
 *   <li>AC-Δ12: 비율은 추출 방식과 무관하게 추출량 타일 서브라벨로</li>
 *   <li>AC-Δ13: 시간 표기 {@code 2분 40초} · 상세 레시피 말미 {@code · 총 …} 병기 없음</li>
 *   <li>AC-Δ9(0021): CDN·외부 URL 참조 없음(로컬 번들 폰트 참조)</li>
 * </ul>
 */
class RecipeCardTemplateTest {

    private final SpringTemplateEngine engine = RenderConfig.offlineTemplateEngine();

    // 구 템플릿(note.html·RatingStyle.emoji)이 쓰던 장식 이모지 전수 — 이식 편차로 레시피 카드에도 없어야 한다.
    private static final List<String> BANNED_EMOJIS = List.of("☕", "🌍", "💧", "🔥", "🏷", "🐾", "🔗", "❤", "😋", "🙂", "😓");

    // 타일 6종의 시안 순서(NoteView.RecipeCard.tiles()가 담는 순서) = 그리드 행 우선 배치 순서.
    private static final List<String> TILE_LABELS = List.of("원두", "물", "시간", "온도", "추출량", "분쇄도");

    // Recipe 필드 순서: method, doseG, waterMl, yieldMl, timeSec, tempC, grind, grinder, detail, feedback
    // (changes/0030 ADR-86 재편 — 구 machine 폐기 · 구 pouring → detail · grind 수치 + grinder 분리)
    private static NoteView.RecipeCard cardOf(Recipe recipe) {
        return new NoteView.RecipeCard("레인보우 블렌드", "커피가게 동경", LocalDate.parse("2026-07-18"), recipe);
    }

    /** 6타일이 전부 차는 레시피 — 재편 이후 수치 6종 = 타일 6종이라 «전 필드 있음»과 «그리드 만석»이 같은 뜻이다. */
    private static NoteView.RecipeCard fullCard(String method) {
        return cardOf(new Recipe(method, 15.0, 240.0, 225.0, 160.0, 92.0, 210.0, "매버릭 2.0",
                "뜸 40ml 30초 → 1차 100ml 중심에서 원형으로 → 2차 100ml",
                "첫 모금이 살짝 떫었으니 다음엔 분쇄를 한 단계 굵게 가볼 것."));
    }

    private String render(Theme theme, NoteView.RecipeCard card) {
        Context ctx = new Context(Locale.KOREAN);
        // 렌더러 baseContext와 같은 컨텍스트 계약 — fmt/rs/amt 헬퍼 + 마스코트 상대 경로.
        ctx.setVariable("fmt", new KoreanDates());
        ctx.setVariable("rs", new RatingStyle());
        ctx.setVariable("amt", new RecipeAmounts());
        ctx.setVariable("mascotHref", "mascot-face.png");
        ctx.setVariable("card", card);
        return engine.process(theme.id() + "/recipe", ctx);
    }

    // 라벨은 자기 태그의 텍스트 전부라 ">라벨<"로 잡는다 — 상세 레시피·피드백 본문에 같은 낱말이 섞여도
    // 타일 라벨과 갈린다(테마 무관 — 두 템플릿 다 라벨을 원소 텍스트로 찍는다).
    private static List<String> tileLabelsIn(String html) {
        List<String> found = new ArrayList<>();
        for (String label : TILE_LABELS) {
            int at = html.indexOf(">" + label + "<");
            if (at >= 0) {
                found.add(label);
            }
        }
        found.sort((a, b) -> Integer.compare(html.indexOf(">" + a + "<"), html.indexOf(">" + b + "<")));
        return found;
    }

    @ParameterizedTest
    @EnumSource(Theme.class)
    @DisplayName("AC-24/AC-76/AC-Δ11: 6타일 그리드 + 상세 레시피·피드백을 FR-7 매핑대로 렌더한다")
    void rendersFieldMapping(Theme theme) {
        String html = render(theme, fullCard("핸드드립"));

        // ②: 로스터리·커피명 + 방식 뱃지
        assertTrue(html.contains("레인보우 블렌드") && html.contains("커피가게 동경"), "커피명·로스터리");
        assertTrue(html.contains("핸드드립"), "방식 뱃지 = method 그대로");
        // ③ 타일 6종이 시안 순서 그대로(= 행 우선 배치 순서) 들어간다
        assertThat(tileLabelsIn(html)).as("타일 6종·시안 순서").containsExactlyElementsOf(TILE_LABELS);
        assertTrue(html.contains("15g"), "원두량(AC-24)");
        assertTrue(html.contains("240ml"), "물량(AC-24)");
        assertTrue(html.contains("2분 40초"), "time_sec 160 → '2분 40초'(AC-76)");
        assertTrue(html.contains("92℃"), "물온도");
        assertTrue(html.contains("225ml"), "추출량 — yield_ml 기준 ml 표기(AC-76)");
        assertTrue(html.contains("210") && !html.contains("210클릭"), "분쇄값 = 수치만(ADR-86 — 구 '210클릭' 형식 소멸)");
        assertTrue(html.contains("매버릭 2.0"), "그라인더명 서브라벨");
        // ④⑤ 텍스트 블록 2종 + autofit(D-9로 상세 레시피가 합류 — 하한은 시안 값)
        // 라벨은 ">라벨<"로 잡는다 — autofit 스크립트 주석이 같은 낱말을 담고 있어 평문 contains로는 갈리지 않는다.
        assertTrue(html.contains(">상세 레시피<") && html.contains("뜸 40ml 30초"), "상세 레시피 블록");
        assertTrue(html.contains("첫 모금이 살짝 떫었으니"), "피드백 본문");
        assertThat(html.split("data-autofit=\"10.5\"", -1).length - 1)
                .as("autofit 영역 2개 — 상세 레시피·피드백(D-9)").isEqualTo(2);
        // ①: 날짜 헤더(테마별 포맷)
        assertTrue(theme == Theme.TYPE_A ? html.contains("2026. 7. 18") : html.contains("2026년 7월 18일"),
                "날짜 헤더");
        // 시안 예시 데이터가 남지 않았다(이식 편차 — Thymeleaf 바인딩 대체)
        assertFalse(html.contains("봄맞이 블렌드"), "시안 예시 데이터 잔존 없음");
        // AC-Δ8: 폐기된 필드의 라벨이 카드에 없다
        assertFalse(html.contains("머신") || html.contains("푸어링"), "구 machine·pouring 라벨 없음(ADR-86)");
    }

    @ParameterizedTest
    @EnumSource(Theme.class)
    @DisplayName("AC-Δ10: method가 어느 값이어도 레이아웃이 갈리지 않는다 — 갈리는 것은 뱃지 문자열뿐")
    void layoutDoesNotBranchOnMethod(Theme theme) {
        String baseline = render(theme, fullCard("핸드드립"));

        for (String method : Arrays.asList("에스프레소", "아메리카노", "콜드브루")) {
            String html = render(theme, fullCard(method));
            // 뱃지 문자열만 되돌리면 산출 HTML이 한 글자도 다르지 않다 — "레이아웃이 같다"보다 강한 형태다
            // (구 espressoLayout()은 여기서 타일 집합·상세 행을 통째로 갈랐다 — ADR-87).
            assertThat(html.replace(method, "핸드드립")).as("method=%s는 뱃지 외에 무영향", method).isEqualTo(baseline);
        }
        // method 없음 = 뱃지만 사라지고 타일·블록은 그대로(AC-25 — 뱃지도 값 없으면 숨김)
        String noMethod = render(theme, fullCard(null));
        assertThat(tileLabelsIn(noMethod)).as("method null이어도 타일 6종").containsExactlyElementsOf(TILE_LABELS);
        assertFalse(noMethod.contains("핸드드립"), "method 없으면 뱃지 숨김");
    }

    @ParameterizedTest
    @EnumSource(Theme.class)
    @DisplayName("AC-Δ11: 값 6·3·2·0개가 각각 만석·1행·2칸·영역 소멸로 떨어진다")
    void gridFillsRowMajorByValueCount(Theme theme) {
        assertThat(tileLabelsIn(render(theme, fullCard("핸드드립")))).as("6개 = 3열 2행 만석").hasSize(6);

        // 3개(원두·시간·추출량) — 1행이 꽉 찬다. 순서는 값이 담긴 순서가 아니라 시안 순서다.
        NoteView.RecipeCard three = cardOf(new Recipe(null, 15.0, null, 225.0, 160.0, null, null, null, null, null));
        assertThat(tileLabelsIn(render(theme, three))).containsExactly("원두", "시간", "추출량");

        // 2개(물·온도) — 1행에 2칸만 차고 남는 칸은 시안 그대로 빈자리다(2026-08-10 사용자 확정).
        NoteView.RecipeCard two = cardOf(new Recipe(null, null, 240.0, null, null, 92.0, null, null, null, null));
        assertThat(tileLabelsIn(render(theme, two))).containsExactly("물", "온도");

        // 0개 — 수치가 하나도 없으면 그리드 영역 자체가 없다. 문장류만 남는다.
        NoteView.RecipeCard none = cardOf(new Recipe("아메리카노", null, null, null, null, null, null, "매버릭 2.0",
                "중간 굵기로 갈아서 내렸음", "다음엔 수치를 재 볼 것."));
        String noneHtml = render(theme, none);
        assertThat(tileLabelsIn(noneHtml)).as("타일 없음 = 그리드 영역 소멸(AC-25)").isEmpty();
        assertTrue(noneHtml.contains("중간 굵기로 갈아서 내렸음"), "상세 레시피는 그대로");
        assertFalse(noneHtml.contains("매버릭 2.0"), "그라인더만 있고 수치가 없으면 분쇄도 타일 자체가 없다(뷰 모델 POLICY)");
    }

    @ParameterizedTest
    @EnumSource(Theme.class)
    @DisplayName("AC-Δ12: 비율은 추출 방식과 무관하게 붙는다 — 핸드드립 15g:225ml도 '비율 1 : 15'")
    void ratioAppliesRegardlessOfMethod(Theme theme) {
        assertTrue(render(theme, fullCard("핸드드립")).contains("비율 1 : 15"), "핸드드립에도 비율(AC-Δ12)");
        assertTrue(render(theme, fullCard("에스프레소")).contains("비율 1 : 15"), "에스프레소도 같은 규칙");
    }

    @ParameterizedTest
    @EnumSource(Theme.class)
    @DisplayName("AC-Δ13: 시간은 '2분 40초' 한 번만 — 상세 레시피 말미 '· 총 …' 자동 병기 없음")
    void timeAppearsOnceWithoutDetailSuffix(Theme theme) {
        String html = render(theme, fullCard("핸드드립"));
        assertThat(html.split("2분 40초", -1).length - 1).as("시간은 타일에서 한 번만").isEqualTo(1);
        assertFalse(html.contains("· 총"), "상세 레시피 말미 자동 병기 삭제(D-7)");
    }

    @ParameterizedTest
    @EnumSource(Theme.class)
    @DisplayName("AC-25: 값 없는 항목은 타일·영역·뱃지를 통째 숨긴다 — 일부 항목만 있는 레시피")
    void hidesAbsentFields(Theme theme) {
        NoteView.RecipeCard sparse = cardOf(
                new Recipe(null, 15.0, null, null, null, null, null, null, null, "간단히 내렸음."));
        String html = render(theme, sparse);

        assertTrue(html.contains("15g"), "값 있는 항목(원두량)은 표시(AC-25)");
        assertTrue(html.contains("간단히 내렸음."), "피드백 표시");
        assertThat(tileLabelsIn(html)).as("타일은 원두 하나뿐").containsExactly("원두");
        assertFalse(html.contains("에스프레소"), "method 없음 → 뱃지 숨김");
        assertFalse(html.contains(">상세 레시피<"), "detail 없음 → 상세 레시피 블록 숨김");
        assertFalse(html.contains("비율"), "추출량 없음 → 비율 서브라벨 없음");
    }

    @ParameterizedTest
    @EnumSource(Theme.class)
    @DisplayName("AC-76: 비율은 도징·추출량 둘 다 있을 때만 — 도징 없으면 추출량만 표시")
    void ratioOmittedWhenDoseMissing(Theme theme) {
        NoteView.RecipeCard noDose = cardOf(
                new Recipe("에스프레소", null, null, 36.0, null, null, null, null, null, "다음엔 도징도 기록할 것."));
        String html = render(theme, noDose);
        assertTrue(html.contains("36ml"), "추출량 표시");
        assertFalse(html.contains("비율"), "도징 없으면 비율 생략(AC-76)");
    }

    @ParameterizedTest
    @EnumSource(Theme.class)
    @DisplayName("AC-Δ9(0021): 레시피 카드 HTML은 외부 URL을 참조하지 않는다 — 로컬 번들 폰트만(ADR-11)")
    void referencesNoExternalUrls(Theme theme) {
        String html = render(theme, fullCard("핸드드립"));
        assertFalse(html.contains("http://") || html.contains("https://"), "CDN 등 외부 URL 참조 없음");
        assertTrue(html.contains("fonts/"), "로컬 번들 폰트 상대 참조");
        if (theme == Theme.TYPE_B) {
            assertTrue(html.contains("src=\"mascot-face.png\""), "마스코트 클래스패스 번들 복사본 상대 참조(이식 편차)");
        }
    }

    @ParameterizedTest
    @EnumSource(Theme.class)
    @DisplayName("AC-Δ5(0021): 레시피 카드에 이모티콘이 없다(이식 편차)")
    void hasNoEmoji(Theme theme) {
        for (String method : Arrays.asList("핸드드립", "에스프레소")) {
            String html = render(theme, fullCard(method));
            for (String emoji : BANNED_EMOJIS) {
                assertFalse(html.contains(emoji), "이모티콘 없음: " + emoji);
            }
        }
    }

    // 구 espressoLayoutMatchesByContains는 TΔ18에서 삭제됐다 — 단언 대상 espressoLayout()이 사라졌고
    // (ADR-87 방식 분기 폐기), 그 자리는 위 layoutDoesNotBranchOnMethod(템플릿 층)와
    // NoteViewRecipeCardTest(뷰 모델 층)가 나눠 진다. 구 rendersHanddripMapping·rendersEspressoMapping
    // 두 갈래는 TΔ20에서 rendersFieldMapping 하나로 합쳐졌다 — 이제 레이아웃이 갈리지 않으므로
    // 「방식별 매핑」이라는 단언 축 자체가 없다.
}
