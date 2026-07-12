package com.devwuu.mocha.pipeline;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Arrays;

/**
 * 입구 의도 게이트 분류 — record/revise/search/end/other 5값 enum
 * (ref: specs/coffee-note-agent/data-model.md#4.1, plan.md#ADR-24, changes/0011 delta.md).
 * <p>{@code record}=새 기록 파이프라인 진입, {@code revise}=확인 대기 기록 수정,
 * {@code search}=노트 검색 세션(FR-20), {@code end}=검색 세션 종료, {@code other}=미진입 + 안내 응답.
 * <p>POLICY: 정의 외 값은 역직렬화에서 거부 (V-1과 동일 정신 — data-model §4.1).
 */
public enum MessageIntent {
    RECORD("record"),
    REVISE("revise"),
    SEARCH("search"),
    END("end"),
    OTHER("other");

    private final String value;

    MessageIntent(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    // 정의 외 값은 거부(예외) → 상위(라우팅 층)에서 폴백 우선순위 경로로 수렴(ADR-24).
    @JsonCreator
    public static MessageIntent from(String value) {
        return Arrays.stream(values())
                .filter(i -> i.value.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("invalid intent: " + value));
    }
}
