package com.devwuu.mocha.domain;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 날짜별 시음 기록 — Note에 내장. 버전 = 날짜 (ref: data-model.md#2.2, FR-15 · 회차 구조 changes/0021 ADR-59).
 *
 * @param date      YYYY-MM-DD, Asia/Seoul 기준. entries 내 유일 키 (V-3).
 * @param brews     회차 배열 — 요소 = 회차 1개({@link Brew}), <b>배열 순서 = 회차 번호</b>. 구 엔트리 레벨
 *                  {@code my_taste}/{@code my_taste_original}/{@code rating}/{@code recipe} 단일 필드는 폐지 —
 *                  감상·레시피는 오직 회차 안에만 존재한다(ADR-59 POLICY). 기존 노트 마이그레이션 없음
 *                  (사용자 삭제·재등록, ADR-28 관례).
 * @param updatedAt 같은 날 갱신 추적용 최종 시각(이력 아님).
 *
 * <p>사진은 아카이브 전용이라 노트 JSON에 기록하지 않는다 — 폴더 경로 규약({@code photos/<slug>/<date>/})이
 * 유일한 연결이다(changes/0014 ADR-32, data-model §2.2). 기존 JSON의 {@code photos} 키는 역직렬화에서 무시된다.
 */
public record Entry(
        LocalDate date,
        List<Brew> brews,
        OffsetDateTime updatedAt
) {

    // V-15: brews는 배열(null 불가) — 요소 검증·드롭은 쓰기 경로의 Brew.normalize 몫.
    public Entry {
        brews = brews == null ? List.of() : List.copyOf(brews);
    }
}
