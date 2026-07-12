package com.devwuu.mocha.domain;

/**
 * 추출 레시피 — Entry에 내장 (ref: data-model.md#2.2, FR-18, changes/0010).
 * <p>JSON: {@code { "dose_g": number|null, "water_ml": number|null, "grind": string|null }}.
 * <b>사용자 발화 전용</b> — {@code source} 개념 없음(항상 사용자 것), 검색·OCR 보강 금지(ADR-22).
 * 확장 필드(물온도·추출시간 등)는 실사용 관측 후 추가(spec §8).
 *
 * @param doseG   원두량(g) — 양수 또는 null (V-8).
 * @param waterMl 물량(ml) — 양수 또는 null (V-8).
 * @param grind   분쇄도 — 자유 문자열 또는 null (V-8).
 */
public record Recipe(Double doseG, Double waterMl, String grind) {

    /**
     * V-8 정규화 후 Recipe를 만든다. 위반 값(음수·0·공백)은 <b>해당 항목만</b> null로 드롭하고,
     * 3항목이 전부 null이면 Recipe 자체를 {@code null}로 정규화한다(카드 레시피 영역 미표시 근거).
     * <p>레시피는 부속 정보라 위반이 있어도 저장을 거부하지 않는다 (ref: data-model.md#V-8).
     *
     * @return 정규화된 Recipe, 또는 3항목 전무 시 {@code null}.
     */
    public static Recipe normalize(Double doseG, Double waterMl, String grind) {
        Double dose = positiveOrNull(doseG);
        Double water = positiveOrNull(waterMl);
        String g = blankToNull(grind);
        if (dose == null && water == null && g == null) {
            return null;
        }
        return new Recipe(dose, water, g);
    }

    // V-8: dose_g/water_ml는 양수 number 또는 null. 0·음수는 위반 → null로 드롭.
    private static Double positiveOrNull(Double v) {
        return v != null && v > 0 ? v : null;
    }

    // V-8: grind는 string 또는 null. 공백·빈 문자열은 null로 드롭.
    private static String blankToNull(String s) {
        return s != null && !s.isBlank() ? s.strip() : null;
    }
}
