package com.devwuu.mocha.pipeline;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

/**
 * 입구 의도 게이트 분류 — record/other 2값 enum
 * (ref: specs/coffee-note-agent/data-model.md#4.1, plan.md#ADR-18, changes/0007 delta.md).
 * <p>{@code record}=기록 파이프라인 진입, {@code other}=미진입 + 안내 응답.
 * <p>POLICY: 정의 외 값은 역직렬화에서 거부 (V-1과 동일 정신 — data-model §4.1).
 * 미래 의도(조회 등)는 enum 값 추가로 확장한다(선제 일반화 금지, right-sizing).
 */
public enum MessageIntent {
    RECORD("record"),
    OTHER("other");

    private final String value;

    MessageIntent(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    // 정의 외 값은 거부(예외) → 상위(IntentClassifier)에서 fail-open(record) 경로로 수렴.
    @JsonCreator
    public static MessageIntent from(String value) {
        return Arrays.stream(values())
                .filter(i -> i.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("invalid intent: " + value));
    }
}
