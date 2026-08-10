package com.devwuu.mocha.render;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * TΔ4b(changes/0021): 레시피 카드 파생 표기 계산 — 비율·시간은 저장하지 않고 렌더 시 계산한다
 * (ADR-54, ADR-1 POLICY). 계산 불가 시 null → 템플릿이 행·서브라벨을 숨긴다.
 * <p>grind 서브라벨 분리는 changes/0030에서 파생 표기가 아니게 됐다 — {@code grinder}가 별 컬럼이라
 * 계산할 것이 없다(ADR-86, TΔ19).
 */
class RecipeAmountsTest {

    @Test
    @DisplayName("AC-76/ADR-54: 비율은 도징·추출량 둘 다 있을 때만 '1 : N'(소수 1자리 반올림)로 계산된다")
    void ratioRequiresBothAndRoundsToOneDecimal() {
        assertEquals("1 : 2", RecipeAmounts.ratio(18.0, 36.0), "정수비는 소수점 없이");
        assertEquals("1 : 16", RecipeAmounts.ratio(15.0, 240.0));
        assertEquals("1 : 2.2", RecipeAmounts.ratio(18.0, 40.0), "40/18=2.22… → 소수 1자리 반올림");
        assertNull(RecipeAmounts.ratio(null, 36.0), "도징 없으면 비율 생략(AC-76)");
        assertNull(RecipeAmounts.ratio(18.0, null), "추출량 없으면 비율 생략(AC-76)");
        assertNull(RecipeAmounts.ratio(null, null));
    }

    @Test
    @DisplayName("AC-76/ADR-54: 시간은 60초 미만 'N초', 이상 'N분 N초'(정확히 분 단위면 'N분')로 포맷된다")
    void timeFormatsBySixtySecondRule() {
        assertEquals("28초", RecipeAmounts.time(28.0));
        assertEquals("2분 40초", RecipeAmounts.time(160.0), "AC-76: time_sec 160 → '2분 40초'");
        assertEquals("3분", RecipeAmounts.time(180.0), "정확히 분 단위면 초 생략");
        assertEquals("1분 1초", RecipeAmounts.time(61.0));
        assertNull(RecipeAmounts.time(null), "값 없으면 행 생략");
    }

    @Test
    @DisplayName("changes/0025 TΔ1c: 시간은 반올림 후 0초 이하·비유한값이면 null — '0초' 표기가 어디에도 새지 않는다")
    void timeOmitsNonPositiveAfterRounding() {
        assertNull(RecipeAmounts.time(0.0), "0초는 행 생략(카드·미리보기 공통)");
        assertNull(RecipeAmounts.time(-30.0), "음수는 행 생략");
        assertNull(RecipeAmounts.time(0.3), "반올림하면 0초가 되는 입력도 생략 — 가드는 반올림 후 값 기준");
        assertEquals("1초", RecipeAmounts.time(0.6), "반올림해서 1초 이상이면 표기");
        assertNull(RecipeAmounts.time(Double.POSITIVE_INFINITY), "비유한값은 표기 불가(num과 동일 취급)");
        assertNull(RecipeAmounts.time(Double.NaN));
    }

    // 구 grindSplitsValueAndGrinder(FR-18 정규화 형식의 되쪼개기)는 changes/0030 TΔ19에서 함께 삭제됐다 —
    // grind가 수치, grinder가 이름으로 갈려 저장되므로 분리 계산이 없다(ADR-86). 분쇄도 타일이 그 두 값을
    // 값·서브라벨로 나눠 받는 것은 이제 NoteViewRecipeCardTest가 뷰 모델 층에서 단언한다.

    @Test
    @DisplayName("수량 표기: 정수는 소수점 생략, 소수는 그대로, null은 빈 문자열(기존 num 계약 회귀 가드)")
    void numKeepsExistingContract() {
        assertEquals("15", RecipeAmounts.num(15.0));
        assertEquals("15.5", RecipeAmounts.num(15.5));
        assertEquals("", RecipeAmounts.num(null));
        assertEquals("", RecipeAmounts.num(Double.POSITIVE_INFINITY), "비유한값은 표기 불가 — 빈 문자열(소비처가 행 생략)");
        assertEquals("", RecipeAmounts.num(Double.NaN));
    }
}
