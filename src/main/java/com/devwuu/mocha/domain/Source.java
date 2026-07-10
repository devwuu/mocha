package com.devwuu.mocha.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

/**
 * 출처 표시 필드의 source 값.
 * <p>POLICY: source ∈ {user, search} (ref: specs/coffee-note-agent/data-model.md#V-5)
 * <ul>
 *   <li>{@code user} — 사용자가 직접 언급/불러준 값</li>
 *   <li>{@code search} — NoteEnricher 검색 보강으로 채운 값(단일 원산지 fallback 포함)</li>
 * </ul>
 */
public enum Source {
    USER("user"),
    SEARCH("search");

    private final String json;

    Source(String json) {
        this.json = json;
    }

    @JsonValue
    public String json() {
        return json;
    }

    // V-5 위반(정의되지 않은 source) 역직렬화 거부.
    @JsonCreator
    public static Source from(String value) {
        return Arrays.stream(values())
                .filter(s -> s.json.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown source: " + value));
    }
}
