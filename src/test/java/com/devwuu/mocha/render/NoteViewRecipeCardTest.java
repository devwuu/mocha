package com.devwuu.mocha.render;

import com.devwuu.mocha.domain.Recipe;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TΔ18(changes/0030): 레시피 카드 뷰 모델의 타일 조립 계약 — {@link NoteView.RecipeCard#tiles()}가
 * <b>method를 보지 않고</b> 값 있는 항목만 시안 순서로 담는지를 템플릿 없이 결정론적으로 본다
 * (백엔드 CLAUDE.md §5.2 — 렌더 산출 HTML 단언은 {@link RecipeCardTemplateTest}가 진다).
 * <ul>
 *   <li>AC-Δ10: method가 어떤 값이어도 같은 타일 집합 — 레이아웃이 갈리지 않는다</li>
 *   <li>AC-Δ11: 값 개수(6·3·2·0)만큼만 타일이 선다 — 하나도 없으면 빈 목록</li>
 *   <li>AC-Δ12: 비율은 도징·추출량 둘 다 있을 때 <b>방식과 무관하게</b> 추출량 타일 서브라벨로 붙는다</li>
 *   <li>AC-Δ13: time_sec 160 → "2분 40초"(시안의 2:40 기각 — D-7)</li>
 * </ul>
 */
class NoteViewRecipeCardTest {

    private static NoteView.RecipeCard cardOf(Recipe recipe) {
        return new NoteView.RecipeCard("레인보우 블렌드", "커피가게 동경", LocalDate.parse("2026-07-18"), recipe);
    }

    // 수치 6종이 전부 있는 레시피 — method만 갈아 끼워 「분기 없음」을 본다.
    private static Recipe fullRecipe(String method) {
        return new Recipe(method, 15.0, 240.0, 225.0, 160.0, 92.0, 210.0, "매버릭 2.0",
                "뜸 40ml 30초 → 100ml → 100ml", "다음엔 한 단계 굵게.");
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"핸드드립", "에스프레소", "아메리카노", "콜드브루"})
    @DisplayName("AC-Δ10: method가 아메리카노·콜드브루·null 어느 값이어도 같은 타일 집합이 선다(분기 없음)")
    void tilesDoNotDependOnMethod(String method) {
        List<NoteView.Tile> tiles = cardOf(fullRecipe(method)).tiles();

        // 라벨·값·서브라벨 전부가 method와 무관하게 동일하다 — 구 espressoLayout()이 가르던 자리다.
        assertThat(tiles).containsExactly(
                new NoteView.Tile("원두", "15g", null),
                new NoteView.Tile("물", "240ml", null),
                new NoteView.Tile("시간", "2분 40초", null),
                new NoteView.Tile("온도", "92℃", null),
                new NoteView.Tile("추출량", "225ml", "비율 1 : 15"),
                new NoteView.Tile("분쇄도", "210", "매버릭 2.0"));
    }

    @Test
    @DisplayName("AC-Δ11: 값 6개면 6타일이 시안 순서(원두·물·시간·온도·추출량·분쇄도)로 선다")
    void sixValuesFillSixTilesInDesignOrder() {
        assertThat(cardOf(fullRecipe("핸드드립")).tiles())
                .extracting(NoteView.Tile::label)
                .containsExactly("원두", "물", "시간", "온도", "추출량", "분쇄도");
    }

    @Test
    @DisplayName("AC-Δ11: 값 3개·2개면 그만큼만 선다 — 빠진 항목이 뒤 항목의 자리를 당기지 않는다")
    void partialValuesYieldOnlyThatManyTiles() {
        // 3개: 원두·온도·분쇄도(중간 항목인 물·시간·추출량이 빈 자리)
        Recipe three = new Recipe("아메리카노", 15.0, null, null, null, 92.0, 210.0, null, null, null);
        assertThat(cardOf(three).tiles())
                .extracting(NoteView.Tile::label)
                .containsExactly("원두", "온도", "분쇄도");

        // 2개: 물·시간
        Recipe two = new Recipe(null, null, 240.0, null, 160.0, null, null, null, null, "간단히 내렸음.");
        assertThat(cardOf(two).tiles())
                .extracting(NoteView.Tile::label)
                .containsExactly("물", "시간");
    }

    @Test
    @DisplayName("AC-Δ11: 수치가 하나도 없으면 빈 목록 — 템플릿이 그리드 영역 자체를 숨긴다")
    void noNumericValuesYieldEmptyTiles() {
        // 문장류(detail·feedback)와 method만 남은 레시피 — 타일에 오를 것이 없다.
        Recipe sentencesOnly = new Recipe("핸드드립", null, null, null, null, null, null, null,
                "중간 굵기로 갈아서 천천히 내림", "다음엔 좀 더 굵게.");
        assertThat(cardOf(sentencesOnly).tiles()).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"핸드드립", "에스프레소"})
    @DisplayName("AC-Δ12: 비율은 추출 방식과 무관하게 추출량 타일 서브라벨로 붙는다(핸드드립 포함)")
    void ratioAttachesRegardlessOfMethod(String method) {
        Recipe recipe = new Recipe(method, 15.0, null, 225.0, null, null, null, null, null, null);
        assertThat(cardOf(recipe).tiles())
                .containsExactly(new NoteView.Tile("원두", "15g", null),
                        new NoteView.Tile("추출량", "225ml", "비율 1 : 15"));
    }

    @Test
    @DisplayName("AC-Δ12: 도징이 없으면 추출량 타일은 서지만 비율 서브라벨은 생략된다")
    void ratioOmittedWhenDoseMissing() {
        Recipe noDose = new Recipe("에스프레소", null, null, 36.0, null, null, null, null, null, null);
        assertThat(cardOf(noDose).tiles()).containsExactly(new NoteView.Tile("추출량", "36ml", null));
    }

    @Test
    @DisplayName("AC-Δ13: time_sec 160 → '2분 40초', 60초 미만은 'N초' — 표기는 RecipeAmounts 단일 소스")
    void timeTileUsesMinuteSecondNotation() {
        Recipe under = new Recipe(null, null, null, null, 28.0, null, null, null, null, null);
        assertThat(cardOf(under).tiles()).containsExactly(new NoteView.Tile("시간", "28초", null));

        // 반올림하면 0초가 되는 값은 표기 불가라 타일 자체가 서지 않는다(빈 타일 누수 차단 — changes/0025).
        Recipe rounding = new Recipe(null, null, null, null, 0.3, null, null, null, null, null);
        assertThat(cardOf(rounding).tiles()).isEmpty();
    }

    @Test
    @DisplayName("분쇄도 POLICY: 그라인더명만 있고 수치가 없으면 타일 자체가 없다(2026-08-10 사용자 확정)")
    void grinderAloneDoesNotRaiseATile() {
        Recipe grinderOnly = new Recipe(null, null, null, null, null, null, null, "매버릭 2.0", null, null);
        assertThat(cardOf(grinderOnly).tiles()).isEmpty();

        // 수치가 있으면 그라인더명은 그 타일의 서브라벨로 따라 붙고, 없으면 서브라벨만 빈다.
        Recipe withGrind = new Recipe(null, null, null, null, null, null, 210.0, null, null, null);
        assertThat(cardOf(withGrind).tiles()).containsExactly(new NoteView.Tile("분쇄도", "210", null));
    }
}
