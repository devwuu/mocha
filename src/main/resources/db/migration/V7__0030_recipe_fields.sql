-- 레시피 필드 재편: pouring → detail, machine 폐기, grind 2컬럼 분리
-- (ref: specs/coffee-note-agent/changes/0030-model-rename-and-recipe-rework/delta.md#D-3·D-4·D-8,
--       plan.md#ADR-86, tasks.md TΔ9)
--
-- V4~V6가 「이름만」 옮긴 것과 달리 여기는 «형태»가 바뀐다 — 실사용 기록이 필드 구성과 어긋나 있던
-- 자리를 정리한다(delta §1.2 실측).
--
-- ── pouring → detail (D-3) ──
-- pouring은 핸드드립 어휘인데 에스프레소·아메리카노의 과정 서술도 받고 있었다. 구조화되지 않는 것의
-- 행선지를 detail 하나로 모은다(plan §4 POLICY). RENAME이라 기존 4행의 값이 그대로 보존된다.
--
-- ── machine DROP (D-3) ──
-- 실기록 0건이다 — 유일한 값 `ㅇㅇㅇㅇ`는 입력 시험 흔적이다(delta §1.2). 머신·기구명은 detail이 흡수한다.
--
-- ── grind TEXT → NUMERIC + grinder TEXT (D-4) ──
-- 구 규칙(FR-18)은 `<분쇄값> (<그라인더명>)`을 한 문자열에 담았고, 서버는 그것을 RecipeAmounts에서
-- 정규식으로 되쪼개 썼다 — 합쳐 저장하고 다시 나누는 왕복이다. 두 컬럼으로 가르면 그 왕복이 사라지고
-- 분쇄값이 V-8(양수 유한) 수치 6종에 편입된다.
--
-- POLICY: 타입이 바뀌므로 DROP 후 재생성하고 기존 값은 백필하지 않는다 (ref: delta.md#D-8).
--         `210클릭`·`210 (매버릭)` 4건은 파싱해 옮길 수 있지만 관례대로 하지 않는다 — 표본이 4행이고
--         필요하면 사용자가 손으로 넣는다. 결과는 «행 소실»이 아니라 «필드 공란»이다(AC-Δ9).
--
-- CHECK는 기존 수치 5종과 같은 원형을 쓴다(recipe_dose_g_check 외 4종 — V1의 문장 그대로).
-- numeric은 double과 달리 Infinity/NaN이 값으로 들어올 수 있어 `> 0`만으로는 부족하다(V1 주석 참조).

ALTER TABLE recipe RENAME COLUMN pouring TO detail;

ALTER TABLE recipe DROP COLUMN machine;

ALTER TABLE recipe DROP COLUMN grind;

ALTER TABLE recipe ADD COLUMN grind NUMERIC CHECK (grind > 0 AND grind < 'Infinity'::NUMERIC);

ALTER TABLE recipe ADD COLUMN grinder TEXT;
