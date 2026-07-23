package com.devwuu.mocha.domain;

/**
 * 출처 표시 필드 공통 타입 — {@code { "value": ..., "source": "user"|"photo"|"search" }}.
 * <p>coffee_name/roastery/roast_level·beans 요소의 description/process(=Sourced&lt;String&gt;),
 * official_notes(=Sourced&lt;List&lt;String&gt;&gt;)에 쓰인다.
 * (ref: specs/coffee-note-agent/data-model.md#2.1, V-5, FR-12)
 */
public record Sourced<T>(T value, Source source) {

    /**
     * 출처 표시 필드의 표시값 추출(null 안전) — 원문 값만 필요할 때 쓴다.
     * <p>POLICY: null 안전 표시값 추출의 프로덕션 *헬퍼 정의*는 이 1곳뿐이다 — 클래스별 private
     * 헬퍼를 다시 만들지 않는다. 사용처의 인라인 3항 연산은 정의가 아니므로 대상 밖
     * (ref: specs/coffee-note-agent/plan.md#ADR-67).
     */
    public static <T> T valueOrNull(Sourced<T> sourced) {
        return sourced == null ? null : sourced.value();
    }
}
