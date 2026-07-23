package com.devwuu.mocha.agent.tool.validation;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * 검증 규칙 패밀리 공용 조각 — 빈 값 위생·날짜 파싱 헬퍼를 한 곳에 둬 규칙 간 판정을 일치시킨다
 * ({@code ToolSupport}와 같은 정신, ref: plan.md#ADR-64).
 */
final class ValidationSupport {

    private ValidationSupport() {
    }

    // V-3 형식 준용: 날짜 인자는 YYYY-MM-DD — 형식 위반은 사유와 함께 거부(에이전트가 절대 날짜로 정정).
    // record(target_date·match.date)·edit(date·new_date) 양 진입점이 공유한다.
    static LocalDate parseDate(String field, String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(raw.strip());
        } catch (DateTimeParseException e) {
            throw new RejectedException(field + " '" + raw + "'는 날짜 형식이 아니다 — YYYY-MM-DD로 보내라. "
                    + "상대 날짜는 컨텍스트의 today 기준으로 해석해라.");
        }
    }

    static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    static List<String> dropBlanks(List<String> values) {
        if (values == null) {
            return List.of();
        }
        return values.stream().filter(v -> v != null && !v.isBlank()).map(String::strip).toList();
    }
}
