package com.devwuu.mocha.render;

import java.time.LocalDate;

/**
 * 템플릿용 한국어 날짜 포맷 헬퍼 — 템플릿 컨텍스트에 {@code fmt}로 주입한다.
 * <p>{@code thymeleaf-extras-java8time}(#temporals)를 클래스패스에 두지 않으므로 포맷은 여기서 한다.
 * 테마마다 원하는 표기가 달라 여러 형태를 제공하고 각 템플릿이 골라 쓴다. null은 빈 문자열로 흡수한다.
 */
public final class KoreanDates {

    /** "2026년 7월 10일" — 귀여운 노트 헤더. */
    public String full(LocalDate d) {
        return d == null ? "" : d.getYear() + "년 " + d.getMonthValue() + "월 " + d.getDayOfMonth() + "일";
    }

    /** "2026. 7. 10" — 세리프 노트 헤더. */
    public String dotted(LocalDate d) {
        return d == null ? "" : d.getYear() + ". " + d.getMonthValue() + ". " + d.getDayOfMonth();
    }
}
