-- 시음일 테이블 개명: entry → tasting_day (+ 자식 조인 컬럼 entry_id → tasting_day_id)
-- (ref: specs/coffee-note-agent/changes/0030-model-rename-and-recipe-rework/delta.md#D-1·D-2,
--       plan.md#ADR-85, tasks.md TΔ5a)
--
-- 개명은 «이름만» 옮긴다 — 컬럼 구성·제약·판정 규칙(V-3·V-10)은 한 줄도 바뀌지 않는다.
--
-- POLICY: 심볼마다 마이그레이션 파일을 가른다 (ref: tasks.md#Phase 1의 분할 축). V4가 tasting을,
--         V5가 brew를, 여기가 entry를 옮긴다 — 한 파일로 묶으면 개명이 진행 중인 어느 시점에도
--         엔티티 매핑이 스키마와 맞지 않아 `ddl-auto: validate`가 서지 않는다.
--
-- UNIQUE(note_id, tasted_on) 제약 «자체»는 유지한다 (AC-Δ16) — 「하루 시음일 1건」이 DB 제약으로
-- 남는 것이 병합안을 기각한 근거였다(delta §1.1). ALTER TABLE … RENAME TO는 제약을 건드리지 않으므로
-- 제약은 자동으로 따라오고, 이름(entry_pkey·entry_note_id_tasted_on_key)만 옛 어휘로 남는다.
-- 따라 바꾸지 않는다 — 표시되는 자리가 없고 스키마의 의미는 같다(V4의 tasting_pkey와 같은 판단).
--
-- tasted_on은 유지한다 (ref: delta.md §어휘 매핑) — 이미 읽히는 이름이고 tasting_day.tasted_on이
-- 중복 어휘가 아니다. 개명 대상은 「어느 쪽이 날짜이고 어느 쪽이 회차인지 안 읽히는」 추상어다.
--
-- 인덱스는 이름이 표시되는 자리다(psql \d, 실행계획) — 제약과 달리 명시적으로 옮긴다.

ALTER TABLE entry RENAME TO tasting_day;

ALTER TABLE cup RENAME COLUMN entry_id TO tasting_day_id;

ALTER INDEX idx_entry_tasted_on RENAME TO idx_tasting_day_tasted_on;
