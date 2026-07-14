package com.devwuu.mocha.domain;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * 날짜별 시음 기록 — Note에 내장. 버전 = 날짜 (ref: data-model.md#2.2, FR-15).
 *
 * @param date            YYYY-MM-DD, Asia/Seoul 기준. entries 내 유일 키 (V-3).
 * @param myTaste         내가 느낀 맛. 표현·뉘앙스 보존 + 한국어 음슴체 정규화(영어는 번역). 키워드화하지 않음
 *                        (US-3, ADR-30, changes/0013). 렌더(카드·인덱스)는 이 필드만 쓴다.
 * @param myTasteOriginal 말한 그대로의 감상 표현(언어 불문, 발화 보존). {@code myTaste}와 항상 병존 —
 *                        렌더 대상 아님(V-11, ADR-30, changes/0013).
 * @param rating          4범주 평가 또는 null(미언급).
 * @param recipe          추출 레시피 또는 null(3항목 전무·미언급). 사용자 발화 전용 (FR-18, changes/0010).
 * @param updatedAt       같은 날 덮어쓰기 추적용 최종 시각(이력 아님).
 *
 * <p>사진은 아카이브 전용이라 노트 JSON에 기록하지 않는다 — 폴더 경로 규약({@code photos/<slug>/<date>/})이
 * 유일한 연결이다(changes/0014 ADR-32, data-model §2.2). 기존 JSON의 {@code photos} 키는 역직렬화에서 무시된다.
 */
public record Entry(
        LocalDate date,
        String myTaste,
        String myTasteOriginal,
        Rating rating,
        Recipe recipe,
        OffsetDateTime updatedAt
) {

    // POLICY: my_taste가 존재하면 my_taste_original도 함께 존재해야 한다 — 원문 누락 시 정규화본을 양쪽에
    //         담아 저장한다(감상 유실 방지가 우선) (ref: data-model.md#V-11, plan#ADR-30, changes/0013).
    public Entry {
        if (myTaste != null && myTasteOriginal == null) {
            myTasteOriginal = myTaste;
        }
    }

    /**
     * 원문 필드 도입 전(changes/0013 이전) 시그니처 호환 생성자 — {@code myTasteOriginal}은 정규화본으로
     * 수렴한다(위 V-11 규칙). 원문을 별도로 실어야 하는 신규/수정 경로는 canonical 생성자를 직접 쓴다.
     */
    public Entry(LocalDate date, String myTaste, Rating rating, Recipe recipe,
                 OffsetDateTime updatedAt) {
        this(date, myTaste, null, rating, recipe, updatedAt);
    }
}
