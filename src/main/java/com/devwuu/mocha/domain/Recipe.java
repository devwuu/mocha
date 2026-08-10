package com.devwuu.mocha.domain;

/**
 * 회차 추출 레시피 — {@link Cup}에 내장 (ref: data-model.md#2.2, FR-18, changes/0010 도입 · 0021 회차 확장
 * · <b>0030 필드 재편</b>).
 * <p>방식별 분기 없는 단일 flat 스키마, 전 필드 nullable(V-8). 비율(도징:추출량)·시간 표기("2분 40초")는
 * 파생값이라 저장하지 않고 렌더 시 계산한다(ADR-1 POLICY).
 * <b>사용자 발화 전용</b> — {@code source} 개념 없음(항상 사용자 것), 검색·OCR 보강 금지(ADR-22, ADR-59 승계).
 *
 * <p><b>changes/0030 재편</b>(plan ADR-86): 구 {@code machine} 폐기 · 구 {@code pouring} → {@code detail} ·
 * 구 단일 문자열 {@code grind}("{@code <분쇄값> (<그라인더명>)}" 정규화)를 <b>{@code grind}(수치) + {@code grinder}(이름)</b>로
 * 분리. 한 문자열에 두 개념을 담은 탓에 실데이터가 {@code 210클릭}·{@code 210 (매버릭)}로 갈렸고 렌더가 그것을
 * 정규식으로 되쪼개는 왕복이 있었다 — 분리 저장이 그 왕복을 없앤다.
 *
 * @param method   추출 방식 뱃지(자유 문자열, 대표값 "핸드드립"·"에스프레소"·"아메리카노") — string 또는 null.
 *                 <b>표시 전용이고 분기 조건이 아니다</b> — 카드 레이아웃은 이 값을 보지 않는다(ADR-87).
 * @param doseG    원두량(g) — 양수 또는 null (V-8).
 * @param waterMl  부은 물량(ml) — 양수 또는 null.
 * @param yieldMl  추출량(ml) — 양수 또는 null. 대략 발화("10ml정도")는 숫자만 취한다(ADR-59).
 * @param timeSec  <b>총</b> 추출 시간(초) — 양수 또는 null. 뜸·프리인퓨징 등 과정 시간은 {@code detail}에.
 * @param tempC    물/머신 온도(℃) — 양수 또는 null.
 * @param grind    분쇄값 — <b>수치만</b>, 양수 또는 null. <b>V-8 수치 규칙의 대상</b>이다(구 텍스트 필드에서 이동 — ADR-86).
 *                 숫자로 떨어지지 않는 분쇄 표현("중간 굵기")은 여기가 아니라 {@code detail}로 간다.
 * @param grinder  그라인더명("매버릭 2.0") — string 또는 null.
 * @param detail   상세 레시피(구 {@code pouring}) — 과정·푸어링 자유 텍스트("뜸 40ml 30초 → 100ml → 100ml").
 *                 구조화하지 않는다. <b>구조화되지 않는 것의 행선지는 여기 하나</b>이고, 폐기된 {@code machine}의
 *                 기구·머신 정보도 여기로 모인다("게이지아 클래식으로 93℃에 내림" — ADR-86 POLICY).
 * @param feedback 그 시도의 관찰·진단·다음 계획 — 한국어 음슴체 정규화, 원문 보존 필드 없음(사용자 확정).
 */
public record Recipe(
        String method,
        Double doseG,
        Double waterMl,
        Double yieldMl,
        Double timeSec,
        Double tempC,
        Double grind,
        String grinder,
        String detail,
        String feedback
) {

    /**
     * V-8 정규화. 수치 필드의 위반 값(음수·0·비유한값)과 텍스트 필드의 공백은 <b>해당 항목만</b> null로 드롭하고,
     * 전 필드가 null이면 Recipe 자체를 {@code null}로 정규화한다(레시피 카드 미생성 근거).
     * <p>레시피는 부속 정보라 위반이 있어도 저장을 거부하지 않는다 (ref: data-model.md#V-8).
     * <p><b>{@code grind}는 changes/0030부터 수치 검사를 탄다</b> — 구 텍스트 필드였을 때의 {@code blankToNull}이
     * 아니라 나머지 수치 6종과 같은 {@code positiveOrNull}이고, 그래서 {@code 0}·음수·{@code Infinity}·{@code NaN}이
     * 여기서 드롭된다(AC-Δ6의 애플리케이션 절반 — DB CHECK가 나머지 절반을 진다).
     *
     * @return 정규화된 Recipe, 또는 전 필드 전무 시 {@code null}.
     */
    public static Recipe normalize(Recipe raw) {
        if (raw == null) {
            return null;
        }
        Recipe normalized = new Recipe(
                blankToNull(raw.method()),
                positiveOrNull(raw.doseG()),
                positiveOrNull(raw.waterMl()),
                positiveOrNull(raw.yieldMl()),
                positiveOrNull(raw.timeSec()),
                positiveOrNull(raw.tempC()),
                positiveOrNull(raw.grind()),
                blankToNull(raw.grinder()),
                blankToNull(raw.detail()),
                blankToNull(raw.feedback()));
        if (normalized.method() == null && normalized.doseG() == null && normalized.waterMl() == null
                && normalized.yieldMl() == null && normalized.timeSec() == null && normalized.tempC() == null
                && normalized.grind() == null && normalized.grinder() == null && normalized.detail() == null
                && normalized.feedback() == null) {
            return null;
        }
        return normalized;
    }

    // V-8: 수치 필드는 양수 유한 number 또는 null. 0·음수·비유한값(Infinity — NaN은 v>0이 걸러줌)은 위반 → null로 드롭.
    // 비유한값을 여기서 한 번 걸러 렌더 표기(RecipeAmounts)·미리보기·비율 계산이 재가드 없이 정렬된다(changes/0025 리뷰 후속).
    private static Double positiveOrNull(Double v) {
        return v != null && !v.isInfinite() && v > 0 ? v : null;
    }

    // V-8: 텍스트 필드는 string 또는 null. 공백·빈 문자열은 null로 드롭.
    private static String blankToNull(String s) {
        return s != null && !s.isBlank() ? s.strip() : null;
    }
}
