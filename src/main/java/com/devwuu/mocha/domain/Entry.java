package com.devwuu.mocha.domain;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 날짜별 시음 기록 — Note에 내장. 버전 = 날짜 (ref: data-model.md#2.2, FR-15).
 *
 * @param date      YYYY-MM-DD, Asia/Seoul 기준. entries 내 유일 키 (V-3).
 * @param myTaste   내가 느낀 맛. 자유 텍스트, 키워드화하지 않음 (US-3).
 * @param rating    4범주 평가 또는 null(미언급).
 * @param photos    상대 경로(photos/&lt;slug&gt;/&lt;date&gt;/ 하위)만 (V-4).
 * @param updatedAt 같은 날 덮어쓰기 추적용 최종 시각(이력 아님).
 */
public record Entry(
        LocalDate date,
        String myTaste,
        Rating rating,
        List<String> photos,
        OffsetDateTime updatedAt
) {
}
