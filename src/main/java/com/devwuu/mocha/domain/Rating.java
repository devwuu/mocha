package com.devwuu.mocha.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

/**
 * 시음 평가 — 4범주 enum (ref: specs/coffee-note-agent/data-model.md#2.2).
 * <p>POLICY: rating ∈ 4범주 또는 null. 정의 외 값은 역직렬화에서 거부 (V-1, AC-9).
 */
public enum Rating {
    PERFECT("완전 내스타일"),
    GOOD("맛있다"),
    OKAY_NOT_MINE("맛은 있는데 내스타일은 아님"),
    BAD("맛이 없다");

    private final String label;

    Rating(String label) {
        this.label = label;
    }

    @JsonValue
    public String label() {
        return label;
    }

    // V-1: 4범주 외 값은 거부(예외) → 상위에서 재추출/오류 경로로 수렴.
    @JsonCreator
    public static Rating from(String value) {
        return Arrays.stream(values())
                .filter(r -> r.label.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("invalid rating: " + value));
    }
}
