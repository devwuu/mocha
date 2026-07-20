package com.devwuu.mocha.domain;

/**
 * 회차 맛 감상 — {@link Brew}에 내장 (ref: data-model.md#2.2, changes/0021 ADR-59).
 * <p>구 엔트리 레벨 {@code my_taste}/{@code my_taste_original}/{@code rating}의 이동처 — 감상마다 평가가
 * 다를 수 있어 rating도 회차 tasting 안에 둔다. 감상↔피드백 분리: 커피 맛의 감상·평가는 여기,
 * 추출 관찰·진단·다음 계획은 그 회차 {@link Recipe#feedback()}(시도 귀속 맛 코멘트도 tasting이다).
 *
 * @param myTaste         그 회차의 맛 감상 — 표현·뉘앙스 보존 + 한국어 음슴체 정규화(ADR-30), 키워드화 금지(US-3).
 *                        렌더(감상 카드)는 이 필드만 쓴다. 비어 있지 않아야 한다(V-15).
 * @param myTasteOriginal 말한 그대로의 원문(언어 불문) — {@code myTaste}와 항상 병존(V-11, 요소 단위 적용).
 * @param rating          4범주 평가 또는 null(미언급, V-1) — 구 엔트리 레벨 필드에서 이동.
 */
public record Tasting(String myTaste, String myTasteOriginal, Rating rating) {

    // POLICY: my_taste가 존재하면 my_taste_original도 함께 존재해야 한다 — 원문 누락 시 정규화본을 양쪽에
    //         담아 저장한다(감상 유실 방지가 우선) (ref: data-model.md#V-11, changes/0021 요소 단위 개정).
    public Tasting {
        if (myTaste != null && myTasteOriginal == null) {
            myTasteOriginal = myTaste;
        }
    }

    /**
     * V-15 정규화: {@code myTaste}가 비어 있으면 tasting 자체를 {@code null}로 드롭한다
     * (빈 감상 tasting 금지 — 감상 없는 시도는 tasting 없이 recipe만 남는다).
     *
     * @return 정규화된 Tasting, 또는 감상 전무 시 {@code null}.
     */
    public static Tasting normalize(String myTaste, String myTasteOriginal, Rating rating) {
        if (myTaste == null || myTaste.isBlank()) {
            return null;
        }
        String original = myTasteOriginal == null || myTasteOriginal.isBlank() ? null : myTasteOriginal;
        return new Tasting(myTaste, original, rating);
    }
}
