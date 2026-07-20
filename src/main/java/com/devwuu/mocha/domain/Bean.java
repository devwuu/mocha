package com.devwuu.mocha.domain;

import java.util.List;

/**
 * 원두 1종 — {@code Note.beans} 배열의 요소 (ref: data-model.md#2.1, V-14, changes/0021 ADR-53).
 * <p>단일 원두도 요소 1개짜리 배열이고, 블렌드는 구성 원두마다 요소를 만들어 원두별 가공방식을 담는다
 * (구 노트 레벨 {@code origin}/{@code process} 필드 대체 — "origin 쉼표 나열" 폐기).
 * 출처 우선순위(V-6)는 <b>요소 서브필드 단위</b>로 적용된다 — 사용자가 말한 원두에 검색이 process만 보강 가능.
 *
 * @param description 원산지·품종 등을 묶은 자유 텍스트(한국어 표기, ADR-38) — 완전 구조화는 비범위(spec §8).
 * @param process     그 원두의 가공방식(한국어 관용 표기) — 모르면 null.
 */
public record Bean(Sourced<String> description, Sourced<String> process) {

    /**
     * V-14 정규화: null 배열은 빈 배열로, {@code description.value}가 빈 요소는 드롭한다.
     * <p>위반 <b>요소만</b> 드롭하고 나머지 원두는 유지한다 — 저장 거부가 아니다
     * (ref: data-model.md#V-14). {@code process.value}가 빈 process는 null로 정규화한다.
     */
    public static List<Bean> normalize(List<Bean> raw) {
        if (raw == null) {
            return List.of();
        }
        return raw.stream()
                .filter(b -> b != null && hasValue(b.description()))
                .map(b -> new Bean(
                        new Sourced<>(b.description().value().strip(), b.description().source()),
                        hasValue(b.process())
                                ? new Sourced<>(b.process().value().strip(), b.process().source())
                                : null))
                .toList();
    }

    private static boolean hasValue(Sourced<String> sourced) {
        return sourced != null && sourced.value() != null && !sourced.value().isBlank();
    }
}
