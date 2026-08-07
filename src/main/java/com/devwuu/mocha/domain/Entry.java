package com.devwuu.mocha.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

/**
 * 날짜별 시음 기록 — Note에 내장. 버전 = 날짜 (ref: data-model.md#2.2, FR-15 · 회차 구조 changes/0021 ADR-59).
 *
 * @param date      YYYY-MM-DD, Asia/Seoul 기준. entries 내 유일 키 (V-3).
 * @param cups      회차 배열 — 요소 = 회차 1개({@link Cup}), <b>배열 순서 = 회차 번호</b>. 구 엔트리 레벨
 *                  {@code my_taste}/{@code my_taste_original}/{@code rating}/{@code recipe} 단일 필드는 폐지 —
 *                  감상·레시피는 오직 회차 안에만 존재한다(ADR-59 POLICY). 기존 노트 마이그레이션 없음
 *                  (사용자 삭제·재등록, ADR-28 관례).
 * @param updatedAt 같은 날 갱신 추적용 최종 시각(이력 아님).
 *
 * <p>사진은 아카이브 전용이라 노트와 함께 저장하지 않는다 — 폴더 경로 규약
 * ({@code photos/<id>-<로스터리>-<커피명>/<date>/})이 유일한 연결이다(changes/0014 ADR-32,
 * changes/0028 §파일 경로 규약, data-model §2.2). 기존 JSON의 {@code photos} 키는 역직렬화에서 무시된다.
 */
public record Entry(
        LocalDate date,
        // TΔ3 임시 조치: JSON 키는 아직 brews다 — 이 타입은 TurnDraft를 통해 그대로 직렬화되므로
        // (agent-turn·turn-draft 계약) 키를 여기서 옮기면 계약 스냅샷·프론트가 함께 깨진다.
        // 계약 표면 개명은 TΔ4가 한 번에 하고 이 애너테이션을 걷어낸다(tasks.md#Phase 1의 분할 축).
        @JsonProperty("brews") List<Cup> cups,
        OffsetDateTime updatedAt
) {

    // V-15: cups는 배열(null 불가) — 요소 검증·드롭은 저장·로드 경계의 Cup.normalize 몫(ADR-66).
    public Entry {
        cups = cups == null ? List.of() : List.copyOf(cups);
    }
}
