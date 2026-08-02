package com.devwuu.mocha.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonValue;

import java.time.LocalDate;
import java.util.Arrays;

/**
 * 신규/기존/수정 판정 결과 — 폼의 모드를 정한다 (ref: data-model.md#2.3, AC-15;
 * {@code edit} 갈래는 changes/0029 delta.md#D-14, tasks TΔ28a·TΔ29a).
 * <ul>
 *   <li>{@code { "type": "new" }} — 새 노트</li>
 *   <li>{@code { "type": "existing", "note_id": 12, "date": "YYYY-MM-DD" }} — 기존 노트에 <b>더하는</b> 시음</li>
 *   <li>{@code { "type": "edit", "note_id": 12, "date": "YYYY-MM-DD" }} — 기존 엔트리를 <b>고치는</b> 대상</li>
 * </ul>
 * <p><b>{@code EXISTING}과 {@code EDIT}은 같은 노트를 가리켜도 의도가 반대다</b>(D-14 ②): 전자는
 * <i>"같은 커피를 또 마셨다"</i>(회차 추가), 후자는 <i>"그때 그 기록이 틀렸다"</i>(엔트리 교체). 그래서 축을
 * 합칠 수 없고, 저장 경로도 갈린다 — {@code POST /api/notes} ↔ {@code PATCH /api/notes/{id}/entries/{date}}.
 * <p>{@code noteId}는 {@code NEW}에서만 비어 있다 — 저장 전이라 식별자가 없기 때문이다
 * (D-1, changes/0028 ADR-75). {@code date}는 {@code EDIT}에서 <b>필수</b>다: 고칠 엔트리를 가리키는
 * 대상 키라 없으면 무엇을 고칠지 정해지지 않는다(추측하는 자리를 만들지 않는다 — TΔ28a).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record MatchInfo(MatchType type, Long noteId, LocalDate date) {

    public enum MatchType {
        NEW("new"),
        EXISTING("existing"),
        EDIT("edit");

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

    /** 수정 대상 — {@code (noteId, date)}가 함께 있어야 어느 기록을 고칠지 정해진다(D-14). */
    public static MatchInfo edit(Long noteId, LocalDate date) {
        return new MatchInfo(MatchType.EDIT, noteId, date);
    }
}
