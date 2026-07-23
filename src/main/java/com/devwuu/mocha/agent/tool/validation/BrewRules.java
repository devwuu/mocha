package com.devwuu.mocha.agent.tool.validation;

import com.devwuu.mocha.agent.tool.BrewArg;
import com.devwuu.mocha.domain.Brew;
import com.devwuu.mocha.domain.Rating;
import com.devwuu.mocha.domain.Tasting;

import java.util.ArrayList;
import java.util.List;

/**
 * 회차 규칙 패밀리(V-1·V-8·V-15) — brews 인자를 도메인 {@link Brew} 배열로 정규화한다(배열 순서 = 회차
 * 번호). record·edit 양 진입점이 공유한다 — 드롭 후 회차 0개 처리와 그 거부 문안은 진입점의 몫이라
 * record/edit가 다르다 (ref: specs/coffee-note-agent/data-model.md#5, plan.md#ADR-64 — 추출은 배치
 * 변경, 판정·문안 불변).
 */
final class BrewRules {

    private BrewRules() {
    }

    // V-15: brews 인자 → 도메인 Brew 배열(배열 순서 = 회차 번호). rating은 V-1로 검증(위반은 거부)하고,
    // recipe V-8 정규화·빈 감상 tasting 드롭·빈 회차 드롭은 Brew.normalize가 맡는다. 드롭 후 0개 처리
    // (record 거부·edit patch 거부)는 호출부의 몫이다.
    static List<Brew> brews(List<BrewArg> raw) {
        if (raw == null) {
            return List.of();
        }
        List<Brew> converted = new ArrayList<>();
        for (BrewArg arg : raw) {
            if (arg == null) {
                continue;
            }
            BrewArg.TastingArg tasting = arg.tasting();
            converted.add(new Brew(arg.recipe(), tasting == null ? null : Tasting.normalize(
                    ValidationSupport.blankToNull(tasting.myTaste()),
                    ValidationSupport.blankToNull(tasting.myTasteOriginal()),
                    parseRating(tasting.rating()))));
        }
        return Brew.normalize(converted);
    }

    // V-1: rating ∈ 4범주 enum 또는 null — 위반 시 오류 사유를 tool 결과로 반환해 루프 안에서 정정(AC-9).
    private static Rating parseRating(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return Rating.from(raw);
        } catch (IllegalArgumentException e) {
            throw new RejectedException("rating '" + raw + "'는 4범주를 벗어난다 — "
                    + "완전 내스타일|맛있다|맛은 있는데 내스타일은 아님|맛이 없다 중 하나이거나, 미언급이면 null이어야 한다(V-1).");
        }
    }
}
