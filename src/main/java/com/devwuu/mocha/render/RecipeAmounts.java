package com.devwuu.mocha.render;

/**
 * 레시피 수량(원두 g·물 ml) 표시용 헬퍼 — 템플릿 컨텍스트에 {@code amt}로 주입한다(TΔ6).
 * <p>레시피 수량은 {@link com.devwuu.mocha.domain.Recipe}에서 {@code Double}이라 {@code 15.0} 그대로 찍으면
 * "15.0"이 된다. 정수면 소수점을 떼어 "15", 소수가 있으면 "15.5"로 보이게 다듬는다(단위는 템플릿이 붙인다).
 * null은 빈 문자열로 흡수한다({@code fmt}·{@code KoreanDates}와 같은 관용).
 */
public final class RecipeAmounts {

    /** {@code 15.0 → "15"}, {@code 15.5 → "15.5"}. null·비유한값은 빈 문자열. */
    public String num(Double v) {
        if (v == null || v.isNaN() || v.isInfinite()) {
            return "";
        }
        if (v == Math.rint(v)) {
            return String.valueOf(v.longValue());
        }
        return String.valueOf(v.doubleValue());
    }
}
