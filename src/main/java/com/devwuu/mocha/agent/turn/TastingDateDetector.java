package com.devwuu.mocha.agent.turn;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Collections;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 결정론 시음 날짜 탐지기 — 사용자 원문에서 <b>절대 날짜 표기만</b> 세어 서로 다른 날짜 집합을 돌려준다
 * (ref: specs/coffee-note-agent/plan.md#ADR-60, data-model.md#V-16, changes/0023 TΔ2a).
 * <p>다중 날짜 게이트(V-16, {@code ProposalValidator})와 자동 분해 트리거(ADR-61, 라우터)가 공유하는
 * 순수 유틸 — LLM·I/O 무관. 정렬 집합을 돌려줘 "가장 이른 날짜" 소비(ADR-61)를 바로 지원한다.
 * <p>POLICY: 상대 날짜("어제"·"엊그제")는 세지 않는다 — 참조·수정 발화 오탐 방지. 오탐·미탐이 관측되면
 * 그때 패턴을 조정한다 (ref: plan.md#ADR-60).
 */
public final class TastingDateDetector {

    // 연도 포함 숫자 표기: "2026-07-18"·"2026.7.18"·"2026/07/18"·"2026 07 18" — 구분자는 백레퍼런스로
    // 통일 강제(혼용 표기는 날짜로 보지 않는다). 앞뒤 숫자 연접은 배제(긴 숫자열 내부 오탐 방지).
    private static final Pattern NUMERIC_FULL =
            Pattern.compile("(?<!\\d)(\\d{4})([-./ ])(\\d{1,2})\\2(\\d{1,2})(?!\\d)");

    // 월/일 축약 표기: "7/18" — 슬래시만 허용한다. 점·하이픈 축약("7.18"·"7-18")은 소수·범위 표기와
    // 겹쳐 오탐 위험이 커서 세지 않는다(미탐 관측 시 조정 — ADR-60).
    private static final Pattern NUMERIC_MONTH_DAY =
            Pattern.compile("(?<![\\d/])(\\d{1,2})/(\\d{1,2})(?![\\d/])");

    // 한국어 표기: "[2026년] 7월 18일" — 연도는 선택. "일"까지 갖춘 표기만 센다(월만·일만은 모호).
    private static final Pattern KOREAN =
            Pattern.compile("(?<!\\d)(?:(\\d{4})\\s*년\\s*)?(\\d{1,2})\\s*월\\s*(\\d{1,2})\\s*일(?!\\d)");

    private TastingDateDetector() {
    }

    /**
     * 원문에서 서로 다른 절대 날짜 집합을 추출한다 — 같은 날짜의 중복·이표기("2026-07-18"과 "7/18")는
     * 1건으로 센다. 달력에 없는 날짜(13월·2/30 등)는 무시한다.
     *
     * @param text  턴의 사용자 원문 — null·공백이면 빈 집합.
     * @param today 발화 시점 기준일 — 연도 없는 표기의 연도 해석 기준.
     * @return 오름차순 정렬된 불변 날짜 집합.
     */
    public static NavigableSet<LocalDate> detect(String text, LocalDate today) {
        NavigableSet<LocalDate> dates = new TreeSet<>();
        if (text != null && !text.isBlank()) {
            collectNumericFull(text, dates);
            collectKorean(text, dates, today);
            collectMonthDay(text, dates, today);
        }
        return Collections.unmodifiableNavigableSet(dates);
    }

    private static void collectNumericFull(String text, NavigableSet<LocalDate> dates) {
        Matcher m = NUMERIC_FULL.matcher(text);
        while (m.find()) {
            addIfValid(dates, Integer.parseInt(m.group(1)), Integer.parseInt(m.group(3)),
                    Integer.parseInt(m.group(4)), null);
        }
    }

    private static void collectKorean(String text, NavigableSet<LocalDate> dates, LocalDate today) {
        Matcher m = KOREAN.matcher(text);
        while (m.find()) {
            Integer year = m.group(1) == null ? null : Integer.valueOf(m.group(1));
            addIfValid(dates, year, Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3)), today);
        }
    }

    private static void collectMonthDay(String text, NavigableSet<LocalDate> dates, LocalDate today) {
        Matcher m = NUMERIC_MONTH_DAY.matcher(text);
        while (m.find()) {
            addIfValid(dates, null, Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), today);
        }
    }

    private static void addIfValid(NavigableSet<LocalDate> dates, Integer year, int month, int day,
                                   LocalDate today) {
        try {
            dates.add(year != null ? LocalDate.of(year, month, day) : resolveYearless(month, day, today));
        } catch (DateTimeException invalidDate) {
            // 달력에 없는 조합(13월·2/30 등)은 날짜 표기가 아니다 — 조용히 건너뛴다(오탐 억제).
        }
    }

    // 연도 없는 표기는 발화 시점(today) 기준 올해로 해석하되, 미래가 되면 작년으로 내린다 — 시음 기록은
    // 과거 시점이라 연초 발화의 "12/31"은 작년을 가리킨다. 에이전트의 today 기준 해석(시스템 프롬프트)과
    // 어긋나는 오탐이 관측되면 조정한다(ADR-60).
    private static LocalDate resolveYearless(int month, int day, LocalDate today) {
        LocalDate resolved = LocalDate.of(today.getYear(), month, day);
        return resolved.isAfter(today) ? resolved.minusYears(1) : resolved;
    }
}
