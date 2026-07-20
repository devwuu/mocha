package com.devwuu.mocha.domain;

import java.util.ArrayList;
import java.util.List;

/**
 * 회차 1개(한 번 내려서 마신 단위) — {@code Entry.brews} 배열의 요소 (ref: data-model.md#2.2,
 * changes/0021 ADR-59).
 * <p>레시피와 그 결과물의 감상은 회차 안에서 <b>1:1</b>이며 참조 필드가 없다 — 짝은 구조 자체가 표현한다.
 * 배열 순서 = 회차 번호(별도 필드 없음). recipe만(감상 없는 시도), tasting만(레시피 없이 마신 날 — 카페 등)
 * 모두 허용하되, 둘 다 null인 요소는 드롭한다(V-15).
 *
 * @param recipe  그 시도의 추출 레시피 또는 null — 사용자 발화 전용(V-8).
 * @param tasting 그 회차의 맛 감상 또는 null (V-11·V-15).
 */
public record Brew(Recipe recipe, Tasting tasting) {

    /**
     * V-15 정규화: null 배열은 빈 배열로, 각 요소는 recipe(V-8)·tasting(V-15 빈 감상 드롭)을 정규화한 뒤
     * <b>둘 다 null인 요소를 드롭</b>한다. 드롭 후 회차 0개인 엔트리의 저장 거부(오류 사유 tool 결과 반환)는
     * 쓰기 경로(ProposalValidator)의 몫이다 (ref: data-model.md#V-15, plan#ADR-59).
     */
    public static List<Brew> normalize(List<Brew> raw) {
        if (raw == null) {
            return List.of();
        }
        List<Brew> normalized = new ArrayList<>();
        for (Brew brew : raw) {
            if (brew == null) {
                continue;
            }
            Recipe recipe = Recipe.normalize(brew.recipe());
            Tasting tasting = brew.tasting() == null ? null : Tasting.normalize(
                    brew.tasting().myTaste(), brew.tasting().myTasteOriginal(), brew.tasting().rating());
            if (recipe == null && tasting == null) {
                continue; // V-15: 빈 회차 드롭
            }
            normalized.add(new Brew(recipe, tasting));
        }
        return List.copyOf(normalized);
    }
}
