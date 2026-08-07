-- 회차 테이블 개명: brew → cup (+ 자식 조인 컬럼 brew_id → cup_id)
-- (ref: specs/coffee-note-agent/changes/0030-model-rename-and-recipe-rework/delta.md#D-1·D-2,
--       plan.md#ADR-85, tasks.md TΔ3)
--
-- 개명은 «이름만» 옮긴다 — 컬럼 구성·제약·판정 규칙(V-8·V-11·V-15)은 한 줄도 바뀌지 않는다.
--
-- POLICY: 심볼마다 마이그레이션 파일을 가른다 (ref: tasks.md#Phase 1의 분할 축). V4가 tasting을,
--         여기가 brew를, V6가 entry를 옮긴다 — 한 파일로 묶으면 개명이 진행 중인 어느 시점에도
--         엔티티 매핑이 스키마와 맞지 않아 `ddl-auto: validate`가 서지 않는다.
--
-- recipe·review의 PK가 곧 조인 컬럼이다 — `recipe_pkey PRIMARY KEY btree (brew_id)`. 즉 아래 두 문장은
-- 「FK 컬럼 rename」이 아니라 «PK 컬럼 rename»이다. 1:1 짝을 PK 공유로 표현하는 구조(ADR-75 — FK 제약
-- 없음)라 참조 무결성 객체가 딸려 있지 않고, 컬럼 이름만 옮기면 끝난다.
--
-- 제약 이름(recipe_pkey·review_pkey·brew_pkey·brew_entry_id_seq_key)은 따라 바꾸지 않는다 — 표시되는
-- 자리가 없고, 이름을 맞추려면 rename 문이 늘 뿐 스키마의 의미는 같다(V4의 tasting_pkey와 같은 판단).
--
-- cup.entry_id는 여기서 건드리지 않는다 — 심볼 ③(entry → tasting_day)의 몫이라 V6가 옮긴다.

ALTER TABLE brew RENAME TO cup;

ALTER TABLE recipe RENAME COLUMN brew_id TO cup_id;
ALTER TABLE review RENAME COLUMN brew_id TO cup_id;
