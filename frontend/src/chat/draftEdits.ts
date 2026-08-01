import type { Bean, Brew, Draft, DraftNote, Entry, Recipe, Sourced, Tasting } from '../api/contract'

/**
 * 폼 편집 = draft의 불변 갱신 (changes/0029 TΔ10).
 *
 * **여기 사는 유일한 규칙**: 사용자가 폼에서 고친 출처 표시 필드는 출처가 `user`가 된다
 * (FR-12 우선순위 `user > photo > search`). 이것이 없으면 다음 턴에서 검색 보강이 사용자가 고친 값을
 * 덮어쓰고, 그것이 이 델타가 없애려는 실패(delta.md §1.2)다. 서버는 같은 규칙을 V-6 게이트로 대조하므로
 * (`RecordProposalValidator`, TΔ2), 여기서 출처를 올리지 않으면 **정정이 게이트에 거부당한다**.
 *
 * 빈 문자열은 필드 제거(null)로 수렴시킨다 — 서버 정규화(V-8·V-14·V-15)와 같은 취급이라 폼에서
 * 지운 값이 공백 문자열로 살아 돌아오지 않는다.
 */

/** 사용자가 입력한 스칼라 — 비우면 필드 자체가 없어진다. */
export function userValue(text: string): Sourced<string> | null {
  const trimmed = text.trim()
  return trimmed === '' ? null : { value: trimmed, source: 'user' }
}

/** 쉼표로 나열한 목록형 필드(공식 노트) — 빈 항목은 버린다. */
export function userList(text: string): Sourced<string[]> | null {
  const values = text.split(',').map((item) => item.trim()).filter((item) => item !== '')
  return values.length === 0 ? null : { value: values, source: 'user' }
}

/** 수치 레시피 필드 — 양수만 유효하고 나머지는 null이다(V-8). 레시피에는 출처 개념이 없다. */
export function numberValue(text: string): number | null {
  const parsed = Number(text.trim())
  return text.trim() === '' || !Number.isFinite(parsed) || parsed <= 0 ? null : parsed
}

/** 텍스트 레시피·감상 필드 — 공백은 null. */
export function textValue(text: string): string | null {
  const trimmed = text.trim()
  return trimmed === '' ? null : trimmed
}

export function patchNote(draft: Draft, patch: Partial<DraftNote>): Draft {
  return { ...draft, note: { ...draft.note, ...patch } }
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
