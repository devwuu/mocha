package com.devwuu.mocha.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

/**
 * 출처 표시 필드의 source 값.
 * <p>POLICY: source ∈ {user, photo, search} (ref: specs/coffee-note-agent/data-model.md#V-5)
 * <ul>
 *   <li>{@code user} — 사용자가 직접 언급/불러준 값</li>
 *   <li>{@code photo} — 수신 사진 OCR(FR-19, changes/0010)이 빈 필드에 채운 값</li>
 *   <li>{@code search} — 에이전트 루프의 web_search 보강(ADR-49)으로 채운 값</li>
 * </ul>
 * <p>우선순위 {@code user > photo > search}(V-6). {@code coffee_name}은 검색 보강 대상이 아니므로
 * source ∈ {user, photo}만 가진다(검색 앵커·정체성, V-5).
 */
public enum Source {
    USER("user"),
    PHOTO("photo"),
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
