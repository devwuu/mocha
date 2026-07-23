package com.devwuu.mocha.agent.turn;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.NavigableSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TΔ2a(changes/0023): 결정론 날짜 탐지기 — 절대 날짜 패턴별 탐지·상대 날짜 무시·중복 1건 계산을
 * LLM 무관 순수 단위로 단언한다 (plan ADR-60, data-model V-16 — 게이트·분해가 공유하는 유틸).
 */
class TastingDateDetectorTest {

    // 발화 시점 고정 — 연도 없는 표기의 해석 기준(결정론).
    private static final LocalDate TODAY = LocalDate.of(2026, 7, 22);

    private static NavigableSet<LocalDate> detect(String text) {
        return TastingDateDetector.detect(text, TODAY);
    }

    @Test
    @DisplayName("ADR-60: 연도 포함 숫자 표기 4종(하이픈·점·슬래시·공백)을 같은 날짜로 탐지한다")
    void detectsNumericFullPatterns() {
        assertThat(detect("2026-07-18에 마셨다")).containsExactly(LocalDate.of(2026, 7, 18));
        assertThat(detect("2026.7.18에 마셨다")).containsExactly(LocalDate.of(2026, 7, 18));
        assertThat(detect("2026/07/18에 마셨다")).containsExactly(LocalDate.of(2026, 7, 18));
        assertThat(detect("2026 07 18 케냐 AA")).containsExactly(LocalDate.of(2026, 7, 18));
    }

    @Test
    @DisplayName("ADR-60: 한국어 표기(연도 유무)를 탐지한다 — 연도 없으면 발화 시점 기준 올해")
    void detectsKoreanPatterns() {
        assertThat(detect("2026년 7월 18일에 마신 커피")).containsExactly(LocalDate.of(2026, 7, 18));
        assertThat(detect("7월 18일에 마신 커피")).containsExactly(LocalDate.of(2026, 7, 18));
        assertThat(detect("2026년7월18일")).containsExactly(LocalDate.of(2026, 7, 18));
    }

    @Test
    @DisplayName("ADR-60: 월/일 슬래시 축약(7/18)을 탐지한다 — 발화 시점 기준 올해")
    void detectsMonthDayShorthand() {
        assertThat(detect("7/18에 내린 거")).containsExactly(LocalDate.of(2026, 7, 18));
    }

    @Test
    @DisplayName("ADR-60: 연도 없는 표기가 미래 날짜가 되면 작년으로 해석한다 — 시음 기록은 과거 시점")
    void resolvesYearlessFutureToPreviousYear() {
        LocalDate january = LocalDate.of(2027, 1, 5);
        assertThat(TastingDateDetector.detect("12/31에 마신 거 기록해줘", january))
                .containsExactly(LocalDate.of(2026, 12, 31));
        assertThat(TastingDateDetector.detect("12월 31일 케냐", january))
                .containsExactly(LocalDate.of(2026, 12, 31));
    }

    @Test
    @DisplayName("ADR-60 POLICY: 상대 날짜(어제·엊그제·지난주)는 세지 않는다 — 참조·수정 발화 오탐 방지")
    void ignoresRelativeDates() {
        assertThat(detect("어제 마신 거랑 엊그제 마신 거 둘 다 기록해줘")).isEmpty();
        assertThat(detect("지난주에 마셨던 그 커피 카드 보여줘")).isEmpty();
    }

    @Test
    @DisplayName("ADR-60: 같은 날짜의 중복·이표기는 1건으로 센다")
    void countsDuplicateDateOnce() {
        NavigableSet<LocalDate> dates =
                detect("2026-07-18 케냐. 7/18에 또 내렸고, 7월 18일 저녁에도 마셨다");
        assertThat(dates).containsExactly(LocalDate.of(2026, 7, 18));
    }

    @Test
    @DisplayName("V-16: 서로 다른 날짜는 각각 세고 오름차순으로 돌려준다 — 가장 이른 날짜 소비(ADR-61) 지원")
    void returnsDistinctDatesSorted() {
        NavigableSet<LocalDate> dates =
                detect("2026 07 19 내추럴 좋았고, 2026 07 18 워시드는 밍밍했다");
        assertThat(dates).containsExactly(LocalDate.of(2026, 7, 18), LocalDate.of(2026, 7, 19));
        assertThat(dates.first()).isEqualTo(LocalDate.of(2026, 7, 18));
    }

    @Test
    @DisplayName("ADR-60: 달력에 없는 조합(13월·2/30)·구분자 혼용은 날짜로 세지 않는다")
    void skipsInvalidCalendarDates() {
        assertThat(detect("2026-13-40 같은 건 날짜가 아니다")).isEmpty();
        assertThat(detect("2/30에 마셨다는 건 오타다")).isEmpty();
        assertThat(detect("2026-07 18 혼용 표기")).isEmpty();
    }

    @Test
    @DisplayName("ADR-60: 날짜 아닌 숫자 표기(소수·용량)는 세지 않는다 — 오탐 억제")
    void ignoresNonDateNumbers() {
        assertThat(detect("92.5도 물로 20.5g 도징, 1:16 비율로 내렸다")).isEmpty();
        assertThat(detect("점수로 치면 7.18 정도")).isEmpty();
    }

    @Test
    @DisplayName("ADR-60: null·공백 원문은 빈 집합이다")
    void emptyInputYieldsEmptySet() {
        assertThat(TastingDateDetector.detect(null, TODAY)).isEmpty();
        assertThat(detect("   ")).isEmpty();
    }
}
