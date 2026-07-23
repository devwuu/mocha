package com.devwuu.mocha.render;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 레시피 수량·파생 표기 헬퍼 — 템플릿 컨텍스트에 {@code amt}로 주입한다(TΔ6 도입 · TΔ4b 확장).
 * 시간·수치 표기의 단일 소스로, Slack 미리보기({@code PreviewBlocks})도 이 계산에 위임한다(changes/0025 ADR-67).
 * <p>레시피 수량은 {@link com.devwuu.mocha.domain.Recipe}에서 {@code Double}이라 {@code 15.0} 그대로 찍으면
 * "15.0"이 된다. 정수면 소수점을 떼어 "15", 소수가 있으면 "15.5"로 보이게 다듬는다(단위는 템플릿이 붙인다).
 * <p>비율·시간 표기는 저장하지 않는 파생값이다 — 렌더 시에만 계산한다(ADR-1, changes/0021 ADR-54 POLICY).
 * 파생 헬퍼는 계산 불가 시 {@code null}을 돌려줘 템플릿 {@code th:if}가 행·서브라벨을 통째 숨기게 한다
 * ({@link #num(Double)}의 빈 문자열 관용과 다른 이유).
 * <p>전 메서드 정적 — stateless 헬퍼라 인스턴스 상태가 없고, 생성 지점이 분화해도 표기가 갈라질 수 없다
 * (backlog CR25-4 해소, 2026-07-23 사용자 확정). 인스턴스화는 템플릿 컨텍스트 {@code amt} 주입용으로만
 * 남긴다 — SpEL은 인스턴스 참조로도 정적 메서드를 해석하므로 템플릿 계약({@code amt.time(...)} 형태)은 불변.
 */
public final class RecipeAmounts {

    // FR-18 정규화 형식 "<분쇄값> (<그라인더명>)" — 그라인더 언급이 없으면 괄호 없이 값만.
    private static final Pattern GRIND = Pattern.compile("^(.+?)\\s*\\(([^()]+)\\)$");

    /** {@code 15.0 → "15"}, {@code 15.5 → "15.5"}. null·비유한값은 빈 문자열. */
    public static String num(Double v) {
        if (v == null || v.isNaN() || v.isInfinite()) {
            return "";
        }
        if (v == Math.rint(v)) {
            return String.valueOf(v.longValue());
        }
        return String.valueOf(v.doubleValue());
    }

    /**
     * 도징:추출량 비율 — {@code (18, 36) → "1 : 2"}, {@code (18, 40) → "1 : 2.2"}(소수 1자리 반올림).
     * 둘 다 양수일 때만 계산하고 아니면 {@code null}(비율 줄 생략 — ADR-54, AC-76).
     */
    public static String ratio(Double doseG, Double yieldMl) {
        if (doseG == null || yieldMl == null || doseG <= 0 || yieldMl <= 0) {
            return null;
        }
        double n = Math.round(yieldMl / doseG * 10) / 10.0;
        return "1 : " + num(n);
    }

    /**
     * 총 추출 시간 — 60초 미만 {@code "28초"}, 이상 {@code "2분 40초"}(정확히 분 단위면 {@code "3분"}).
     * null·비양수는 {@code null}(행 생략 — ADR-54). 가드는 반올림 <em>후</em> 값 기준이다 —
     * {@code 0.3}처럼 반올림하면 0초가 되는 입력이 "0초"로 새는 것을 막는다(changes/0025 TΔ1c 정렬).
     */
    public static String time(Double timeSec) {
        if (timeSec == null || timeSec.isNaN() || timeSec.isInfinite()) {
            return null; // 비유한값은 num과 동일하게 표기 불가 취급(Math.round(∞)=Long.MAX 누수 차단)
        }
        long total = Math.round(timeSec);
        if (total <= 0) {
            return null;
        }
        if (total < 60) {
            return total + "초";
        }
        long minutes = total / 60;
        long seconds = total % 60;
        return seconds == 0 ? minutes + "분" : minutes + "분 " + seconds + "초";
    }

    /**
     * grind의 분쇄값 부분 — {@code "210클릭 (매버릭 2.0)" → "210클릭"}. FR-18 정규화 형식의 괄호를 시안의
     * 값+그라인더 서브라벨 분리 표시로 되돌린다(파생 표기 — 저장 형식은 한 문자열 그대로).
     * 괄호가 없으면 원문 그대로, null이면 null.
     */
    public static String grindValue(String grind) {
        if (grind == null) {
            return null;
        }
        Matcher m = GRIND.matcher(grind.strip());
        return m.matches() ? m.group(1) : grind.strip();
    }

    /**
     * grind의 그라인더명 부분 — {@code "210클릭 (매버릭 2.0)" → "매버릭 2.0"}. 괄호가 없으면 {@code null}
     * (서브라벨 생략). "기준" 접미는 템플릿이 붙인다.
     */
    public static String grindGrinder(String grind) {
        if (grind == null) {
            return null;
        }
        Matcher m = GRIND.matcher(grind.strip());
        return m.matches() ? m.group(2) : null;
    }
}
