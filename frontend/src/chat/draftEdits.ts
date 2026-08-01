import type { Bean, Brew, Draft, DraftNote, Entry, NoteCandidate, Recipe, Tasting } from '../api/contract'

/**
 * 폼 편집 = draft의 불변 갱신 (changes/0029 TΔ10).
 *
 * 입력값 → 도메인 값 변환(고친 필드는 출처가 `user`가 된다는 규칙 포함)은 **`../formValues`가 소유한다** —
 * 수정 폼(TΔ13b)이 같은 규칙을 쓰기 때문에 어느 한 화면에 두면 갈라진다. 여기 남는 것은 `Draft` 구조를
 * 불변으로 갈아끼우는 조작뿐이다.
 */

export function patchNote(draft: Draft, patch: Partial<DraftNote>): Draft {
  return { ...draft, note: { ...draft.note, ...patch } }
}

/**
 * 변경 시트에서 기존 노트를 골랐다 (changes/0029 TΔ11, AC-1).
 *
 * **`note.id`와 `match`를 한 함수가 함께 옮기는 것이 이 함수의 존재 이유다.** 서버에서 둘을 읽는 곳이
 * 다르기 때문이다 — 어느 노트에 병합할지는 `note.id`가 정하고(`NoteTxService.commit`), `match.type`은
 * 별칭 생성 LLM 콜을 쏠지만 가른다(`NoteService.commit`). 따로 두면 "새 노트인데 기존 id에 병합" 같은
 * 조합이 만들어지고, 그것이 delta.md §1.2가 기록한 실패의 형태다.
 *
 * 커피명·로스터리를 후보 값으로 맞추는 것은 **화면 정직성** 때문이다: 기존 노트 병합에서 서버는 노트
 * 메타를 갱신하지 않으므로(ADR-4 — 재기록은 그날의 엔트리를 쌓는 일이지 커피의 사실을 다시 쓰는 일이
 * 아니다), 폼에 다른 커피명을 남겨 두면 저장 후 조용히 무시될 값을 보여주게 된다. 사실을 고치는 경로는
 * 상세 화면의 수정 폼이다(TΔ13).
 *
 * `match.date`는 넣지 않는다 — 미리보기 표기용 필드이고(data-model §2.3) 후보 선택이 만들어 낼 값이 아니다.
 */
export function selectExisting(draft: Draft, candidate: NoteCandidate): Draft {
  return {
    note: {
      ...draft.note,
      id: candidate.note_id,
      coffee_name: { value: candidate.coffee_name, source: 'user' },
      // 로스터리를 모르는 노트면 폼 값을 유지한다 — 지우는 것이 정보를 늘리지 않는다.
      roastery: candidate.roastery === null
        ? draft.note.roastery
        : { value: candidate.roastery, source: 'user' },
    },
    match: { type: 'existing', note_id: candidate.note_id },
  }
}

/**
 * 변경 시트에서 [새 노트로 등록]을 골랐다 — 에이전트의 기존 노트 판정을 되돌린다 (TΔ11).
 *
 * **이 경로가 필요한 이유가 도메인이다**(사용자 확정 2026-08-01): 싱글 오리진은 커피명이 산지·농장·품종에서
 * 오므로 **이름이 같은 다른 커피가 흔하다**. 발화에 로스터리가 없으면 에이전트가 남의 노트에 회차를 붙일 수
 * 있고, 그것을 되돌릴 경로가 없으면 배지가 한 방향으로만 정정 가능해진다.
 *
 * 커피명·로스터리는 그대로 둔다 — 폼에 있는 값이 곧 새 노트의 값이 된다.
 */
export function selectNew(draft: Draft): Draft {
  return { note: { ...draft.note, id: null }, match: { type: 'new' } }
}

export function patchBean(draft: Draft, beanIndex: number, patch: Partial<Bean>): Draft {
  return patchNote(draft, { beans: replaceAt(draft.note.beans, beanIndex, (bean) => ({ ...bean, ...patch })) })
}

export function patchEntry(draft: Draft, entryIndex: number, patch: Partial<Entry>): Draft {
  return patchNote(draft, { entries: replaceAt(draft.note.entries, entryIndex, (entry) => ({ ...entry, ...patch })) })
}

export function patchBrew(draft: Draft, entryIndex: number, brewIndex: number, patch: Partial<Brew>): Draft {
  const entry = draft.note.entries[entryIndex]
  return patchEntry(draft, entryIndex, {
    brews: replaceAt(entry.brews, brewIndex, (brew) => ({ ...brew, ...patch })),
  })
}

/** 레시피가 없던 회차에 값을 넣으면 레시피가 생긴다 — 전 필드 null인 레시피는 서버가 드롭한다(V-8). */
export function patchRecipe(draft: Draft, entryIndex: number, brewIndex: number, patch: Partial<Recipe>): Draft {
  const current = draft.note.entries[entryIndex].brews[brewIndex].recipe ?? EMPTY_RECIPE
  return patchBrew(draft, entryIndex, brewIndex, { recipe: { ...current, ...patch } })
}

/** 감상이 없던 회차도 같다 — `my_taste`가 빈 tasting은 서버가 드롭한다(V-15). */
export function patchTasting(draft: Draft, entryIndex: number, brewIndex: number, patch: Partial<Tasting>): Draft {
  const current = draft.note.entries[entryIndex].brews[brewIndex].tasting ?? EMPTY_TASTING
  return patchBrew(draft, entryIndex, brewIndex, { tasting: { ...current, ...patch } })
}

const EMPTY_RECIPE: Recipe = {
  method: null,
  dose_g: null,
  water_ml: null,
  yield_ml: null,
  time_sec: null,
  temp_c: null,
  grind: null,
  machine: null,
  pouring: null,
  feedback: null,
}

// my_taste_original은 "말한 그대로의 원문"이라 폼 편집으로 다시 쓰지 않는다(V-11) — 사용자가 고치는
// 것은 정규화본(my_taste)뿐이고, 원문이 비어 있으면 서버가 정규화본으로 채운다.
const EMPTY_TASTING: Tasting = { my_taste: null, my_taste_original: null, rating: null }

function replaceAt<T>(items: T[], index: number, update: (item: T) => T): T[] {
  return items.map((item, i) => (i === index ? update(item) : item))
}
