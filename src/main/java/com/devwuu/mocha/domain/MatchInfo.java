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
 *   <li>{@code { "type": "existing", "note_id": 12, "date": "YYYY-MM-DD" }} — 기존 노트 대상</li>
 * </ul>
 * <p>{@code noteId}는 {@code EXISTING}에서만 유효하다 — {@code NEW}는 아직 저장되지 않아 식별자가 없다
 * (D-1, changes/0028 ADR-75). slug 시절에도 같은 구조였고 이름만 바뀐다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MatchInfo(MatchType type, Long noteId, LocalDate date) {

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

    public static MatchInfo existing(Long noteId, LocalDate date) {
        return new MatchInfo(MatchType.EXISTING, noteId, date);
    }
}
