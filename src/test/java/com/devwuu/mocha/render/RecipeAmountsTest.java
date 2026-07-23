package com.devwuu.mocha.render;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * TΔ4b(changes/0021): 레시피 카드 파생 표기 계산 — 비율·시간·grind 서브라벨은 저장하지 않고
 * 렌더 시 계산한다(ADR-54, ADR-1 POLICY). 계산 불가 시 null → 템플릿이 행·서브라벨을 숨긴다.
 */
class RecipeAmountsTest {

    private final RecipeAmounts amt = new RecipeAmounts();

    @Test
    @DisplayName("AC-76/ADR-54: 비율은 도징·추출량 둘 다 있을 때만 '1 : N'(소수 1자리 반올림)로 계산된다")
    void ratioRequiresBothAndRoundsToOneDecimal() {
        assertEquals("1 : 2", amt.ratio(18.0, 36.0), "정수비는 소수점 없이");
        assertEquals("1 : 16", amt.ratio(15.0, 240.0));
        assertEquals("1 : 2.2", amt.ratio(18.0, 40.0), "40/18=2.22… → 소수 1자리 반올림");
        assertNull(amt.ratio(null, 36.0), "도징 없으면 비율 생략(AC-76)");
        assertNull(amt.ratio(18.0, null), "추출량 없으면 비율 생략(AC-76)");
        assertNull(amt.ratio(null, null));
    }

    @Test
    @DisplayName("AC-76/ADR-54: 시간은 60초 미만 'N초', 이상 'N분 N초'(정확히 분 단위면 'N분')로 포맷된다")
    void timeFormatsBySixtySecondRule() {
        assertEquals("28초", amt.time(28.0));
        assertEquals("2분 40초", amt.time(160.0), "AC-76: time_sec 160 → '2분 40초'");
        assertEquals("3분", amt.time(180.0), "정확히 분 단위면 초 생략");
        assertEquals("1분 1초", amt.time(61.0));
        assertNull(amt.time(null), "값 없으면 행 생략");
    }

    @Test
    @DisplayName("changes/0025 TΔ1c: 시간은 반올림 후 0초 이하·비유한값이면 null — '0초' 표기가 어디에도 새지 않는다")
    void timeOmitsNonPositiveAfterRounding() {
        assertNull(amt.time(0.0), "0초는 행 생략(카드·미리보기 공통)");
        assertNull(amt.time(-30.0), "음수는 행 생략");
        assertNull(amt.time(0.3), "반올림하면 0초가 되는 입력도 생략 — 가드는 반올림 후 값 기준");
        assertEquals("1초", amt.time(0.6), "반올림해서 1초 이상이면 표기");
        assertNull(amt.time(Double.POSITIVE_INFINITY), "비유한값은 표기 불가(num과 동일 취급)");
        assertNull(amt.time(Double.NaN));
    }

    @Test
    @DisplayName("FR-18: grind 정규화 형식 '<분쇄값> (<그라인더명>)'이 값·그라인더 서브라벨로 분리된다")
    void grindSplitsValueAndGrinder() {
        assertEquals("210클릭", amt.grindValue("210클릭 (매버릭 2.0)"));
        assertEquals("매버릭 2.0", amt.grindGrinder("210클릭 (매버릭 2.0)"));
        assertEquals("수동 3바퀴", amt.grindValue("수동 3바퀴"), "그라인더 언급 없으면 값만(FR-18)");
        assertNull(amt.grindGrinder("수동 3바퀴"), "그라인더 없으면 서브라벨 생략");
        assertNull(amt.grindValue(null));
        assertNull(amt.grindGrinder(null));
    }

    @Test
    @DisplayName("수량 표기: 정수는 소수점 생략, 소수는 그대로, null은 빈 문자열(기존 num 계약 회귀 가드)")
    void numKeepsExistingContract() {
        assertEquals("15", amt.num(15.0));
        assertEquals("15.5", amt.num(15.5));
        assertEquals("", amt.num(null));
        assertEquals("", amt.num(Double.POSITIVE_INFINITY), "비유한값은 표기 불가 — 빈 문자열(소비처가 행 생략)");
        assertEquals("", amt.num(Double.NaN));
    }
}
