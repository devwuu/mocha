package com.devwuu.mocha.domain;

/**
 * 출처 표시 필드 공통 타입 — {@code { "value": ..., "source": "user"|"search" }}.
 * <p>roastery/origin/process/roast_level(=Sourced&lt;String&gt;), official_notes(=Sourced&lt;List&lt;String&gt;&gt;)에 쓰인다.
 * (ref: specs/coffee-note-agent/data-model.md#2.1, V-5, FR-12)
 */
public record Sourced<T>(T value, Source source) {

    public static <T> Sourced<T> user(T value) {
        return new Sourced<>(value, Source.USER);
    }

    public static <T> Sourced<T> search(T value) {
        return new Sourced<>(value, Source.SEARCH);
    }
}
