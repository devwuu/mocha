package com.devwuu.mocha.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;

import java.time.LocalDate;
import java.util.Arrays;

/**
 * 신규/기존 판정 결과 — 미리보기 표시용 (ref: data-model.md#2.3, AC-15).
 * <ul>
 *   <li>{@code { "type": "new" }} — 새 노트</li>
 *   <li>{@code { "type": "existing", "slug": "...", "date": "YYYY-MM-DD" }} — 기존 노트 대상</li>
 * </ul>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MatchInfo(MatchType type, String slug, LocalDate date) {

    public enum MatchType {
        NEW("new"),
        EXISTING("existing");

        private final String json;

        MatchType(String json) {
            this.json = json;
        }

        @JsonValue
        public String json() {
            return json;
        }

        @JsonCreator
        public static MatchType from(String value) {
            return Arrays.stream(values())
                    .filter(t -> t.json.equals(value))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("unknown match type: " + value));
        }
    }

    public static MatchInfo newNote() {
        return new MatchInfo(MatchType.NEW, null, null);
    }

    public static MatchInfo existing(String slug, LocalDate date) {
        return new MatchInfo(MatchType.EXISTING, slug, date);
    }
}
