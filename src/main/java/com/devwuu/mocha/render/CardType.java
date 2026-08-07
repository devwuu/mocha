package com.devwuu.mocha.render;

import com.devwuu.mocha.domain.Brew;

/**
 * 회차 카드의 종류 — 감상·레시피 (ref: plan.md#ADR-54·59, changes/0029 tasks.md TΔ9).
 *
 * <p>카드가 둘로 갈린 것은 <b>렌더 단위</b>이지 사용자가 고르는 축이 아니다(구 Slack 배달도 그 엔트리의
 * 카드를 파트별로 고르게 하지 않고 전부 보냈다, FR-16). 그래서 이 enum이 사는 이유는 <b>온디맨드 API의
 * 대상 지정</b>뿐이다 — 서버는 요청 1건당 카드 1장을 만들고, 무엇을 만들지가 여기서 갈린다.
 *
 * <p>{@code id}가 그대로 {@code ?type=} 값이자 파일명 세그먼트다({@code <date>-taste-<n>.jpg}) — 두
 * 표기를 갈라 두면 URL과 캐시 파일이 서로 다른 어휘를 쓰게 되고, 그 매핑이 또 한 곳의 규칙이 된다.
 */
public enum CardType {

    /** 감상 카드 — {@code review}가 있는 회차만 산출된다(AC-78). */
    TASTE("taste"),

    /** 레시피 카드 — {@code recipe}가 있는 회차만 산출된다(AC-78). */
    RECIPE("recipe");

    private final String id;

    CardType(String id) {
        this.id = id;
    }

    /** {@code ?type=} 값이자 카드 파일명의 종류 세그먼트. */
    public String id() {
        return id;
    }

    /**
     * 요청 파라미터 → 카드 종류. 4범주 판정({@code Rating.from})과 같은 성질의 진입점이다 — 표기를 아는
     * 곳을 하나로 두면 본문·쿼리·파일명이 같은 어휘를 지난다.
     *
     * @throws IllegalArgumentException 알 수 없는 표기 — 전송 계층이 400으로 수렴시킨다.
     */
    public static CardType from(String raw) {
        for (CardType type : values()) {
            if (type.id.equals(raw)) {
                return type;
            }
        }
        throw new IllegalArgumentException("알 수 없는 카드 종류: " + raw);
    }

    /**
     * 그 회차에 이 종류의 카드가 있는가 — 없는 파트는 카드를 만들지 않는다(AC-78).
     *
     * <p>산출 규칙({@code ThymeleafNoteRenderer.bakeEntryCards})과 <b>같은 판정</b>이라 여기 둔다:
     * 갈리면 온디맨드가 <i>"있다"</i>고 답한 카드를 렌더러가 굽지 않는 조합이 생긴다.
     */
    public boolean presentIn(Brew brew) {
        return this == TASTE ? brew.review() != null : brew.recipe() != null;
    }
}
