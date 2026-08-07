-- 회차 맛 감상 테이블 개명: tasting → review
-- (ref: specs/coffee-note-agent/changes/0030-model-rename-and-recipe-rework/delta.md#D-1·D-2,
--       plan.md#ADR-85, tasks.md TΔ1)
--
-- 개명은 «이름만» 옮긴다 — 컬럼·제약·판정 규칙(V-1·V-11·V-15)은 한 줄도 바뀌지 않는다.
--
-- POLICY: 심볼마다 마이그레이션 파일을 가른다 (ref: tasks.md#Phase 1의 분할 축). 세 테이블을
--         한 파일에서 동시에 rename하면 개명이 진행 중인 어느 시점에도 엔티티 매핑이 스키마와
--         맞지 않아 `ddl-auto: validate`가 서지 않는다. 이 테이블은 조인 컬럼이 자기 쪽에만
--         있어(brew_id) 단독 rename이 성립한다 — brew_id → cup_id는 V5(TΔ3)가 옮긴다.
--
-- 제약 이름(tasting_pkey·tasting_rating_check)은 따라 바꾸지 않는다. 표시되는 자리가 없고,
-- 이름을 맞추려면 rename 문이 늘 뿐 스키마의 의미는 같다(recipe_pkey를 그대로 두는 것과 같은 판단).
--
-- my_taste·my_taste_original은 유지한다 (ref: delta.md D-Δ0-1, findings-TΔ0.md §8) — 개명 대상은
-- 「어느 쪽이 날짜이고 어느 쪽이 회차인지 안 읽히는」 추상어이지 감상 필드가 아니다.

ALTER TABLE tasting RENAME TO review;
