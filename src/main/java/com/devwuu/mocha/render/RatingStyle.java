package com.devwuu.mocha.render;

import com.devwuu.mocha.domain.Rating;

/**
 * 4범주 평가의 표시 스타일 헬퍼 — 템플릿 컨텍스트에 {@code rs}로 주입한다 (ref: FR-11, tasks T5-2 표시 재료).
 * <p>이모지/색은 디자인 디테일(스펙 결정 아님)이라 여기에 모아 둔다. 라벨 자체는 {@link Rating#label()}.
 * null(미언급)은 각 템플릿의 {@code th:if}로 걸러지므로 여기서 다루지 않는다.
 */
public final class RatingStyle {

    /** 배지 배경색(귀여운 테마). */
    public String bg(Rating r) {
        return switch (r) {
            case PERFECT -> "#fbe3e0";
            case GOOD -> "#e8f0dd";
            case OKAY_NOT_MINE -> "#f0e7d8";
            case BAD -> "#efe1e1";
        };
    }

    /** 배지 글자색(귀여운 테마). */
    public String fg(Rating r) {
        return switch (r) {
            case PERFECT -> "#b05a4e";
            case GOOD -> "#5f7a43";
            case OKAY_NOT_MINE -> "#8c6a44";
            case BAD -> "#9a5148";
        };
    }

    /** 배지 테두리색(귀여운 감상 카드, TΔ4a) — GOOD은 시안 실측값, 나머지는 각 bg 계열을 어둡게 맞춘 파생. */
    public String border(Rating r) {
        return switch (r) {
            case PERFECT -> "#ecc9c4";
            case GOOD -> "#d3e0c2";
            case OKAY_NOT_MINE -> "#e3d3b8";
            case BAD -> "#ddc4c4";
        };
    }
}
