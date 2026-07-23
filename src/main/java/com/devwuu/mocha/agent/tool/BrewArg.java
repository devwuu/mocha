package com.devwuu.mocha.agent.tool;

import com.devwuu.mocha.domain.Recipe;

/**
 * 제안 tool 인자의 {@code brews} 배열 요소 — 회차 1개의 <b>미검증 원시 형태</b>
 * (ref: specs/coffee-note-agent/data-model.md#3.3, changes/0021 ADR-59).
 * <p>recipe는 전 필드 nullable 원시값이라 도메인 {@link Recipe}를 그대로 재사용하고(V-8 정규화는 검증
 * 단계), tasting은 rating을 String으로 들어 V-1 위반을 역직렬화 예외가 아니라 검증 진입점
 * ({@code RecordProposalValidator}·{@code EditProposalValidator})의 사유 있는 거부로 다룬다.
 * 배열 순서 = 회차 번호.
 *
 * @param recipe  그 시도의 추출 레시피 — 사용자 발화 전용, 전무면 null(V-8).
 * @param tasting 그 회차의 맛 감상 — 감상 없는 시도면 null(V-15).
 */
public record BrewArg(Recipe recipe, TastingArg tasting) {

    /**
     * 회차 감상 인자 — 도메인 {@link com.devwuu.mocha.domain.Tasting}의 미검증 원시 형태.
     *
     * @param myTaste         내 느낌(한국어 음슴체 정규화본, ADR-30) — 비어 있으면 tasting이 드롭된다(V-15).
     * @param myTasteOriginal 말한 그대로의 감상 원문 — {@code myTaste}와 병존(V-11, 누락 시 정규화본 폴백).
     * @param rating          4범주 enum 라벨 또는 null — 위반은 검증 거부(V-1).
     */
    public record TastingArg(String myTaste, String myTasteOriginal, String rating) {
    }
}
