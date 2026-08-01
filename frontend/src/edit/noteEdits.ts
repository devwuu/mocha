import type {
  Bean,
  NoteDetail,
  NoteDetailBrew,
  NoteDetailTasting,
  NoteEntryUpdate,
  NoteMetaUpdate,
  Recipe,
} from '../api'

/**
 * 수정 폼 편집 = 요청 본문의 불변 갱신 (changes/0029 TΔ13b).
 *
 * 캡처 쪽(`chat/draftEdits.ts`)과 대칭이지만 **다루는 값이 다르다**: 저쪽은 아직 저장되지 않은 `Draft`
 * 하나이고 이쪽은 **저장 단위가 갈린 두 요청 본문**이다(`NoteMetaUpdate` · `NoteEntryUpdate` — TΔ4a가
 * 나눈 `updateMeta`·`replaceEntry` 2경로). 그래서 한 화면 안에서도 편집 상태가 하나로 합쳐지지 않는다.
 *
 * 입력값 변환(고친 필드는 출처가 `user`)은 `../formValues`가 소유한다 — 두 폼의 공유 규칙이다.
 */

/**
 * 날짜 하나의 편집 상태.
 *
 * **`targetDate`와 `value.date`가 따로 있는 것이 이 타입의 존재 이유다.** 앞은 경로의 `{date}`(서버가 고칠
 * 대상을 찾는 키)이고 뒤는 본문의 `date`(결과 날짜)다. 사용자가 날짜를 바꾸면 뒤만 움직이고 앞은 로드
 * 시점의 값에 못 박힌다 — 둘을 한 필드로 두면 날짜를 고치는 순간 *"어느 엔트리를 고치는 중인가"*를 잃는다.
 */
export interface EntryDraft {
  targetDate: string
  value: NoteEntryUpdate
}

/** 상세 응답 → 메타 수정 본문. `coffee_name`은 옮기지 않는다 — 계약에 필드가 없다(V-9 구조 차단). */
export function toMetaUpdate(note: NoteDetail): NoteMetaUpdate {
  return {
    roastery: note.roastery,
    beans: note.beans,
    roast_level: note.roast_level,
    official_notes: note.official_notes,
    sources: note.sources,
  }
}

/** 상세 응답 → 날짜별 편집 상태. 사진은 옮기지 않는다 — 폼이 고치는 값이 아니다. */
export function toEntryDrafts(note: NoteDetail): EntryDraft[] {
  return note.entries.map((entry) => ({
    targetDate: entry.date,
    value: { date: entry.date, brews: entry.brews },
  }))
}

/**
 * 원두 입력 줄 — 저장된 원두 + **끝에 빈 슬롯 하나**.
 *
 * 빈 슬롯이 있는 이유는 구멍을 막기 위해서다: 원두가 0개로 저장된 노트는 다른 어떤 경로로도 원두를 채울 수
 * 없다 — 기존 노트에 회차를 더하는 캡처 경로는 노트 메타를 갱신하지 않으므로(ADR-4) 대화로도 못 넣는다.
 * FR-21이 원두 구성을 수정 범위에 넣어 두고 실제로는 못 고치는 상태였다.
 *
 * 슬롯을 비운 채 저장하면 아무 일도 없다 — `trimBeans`가 걷고, 새면 서버도 같은 규칙으로 드롭한다(V-14).
 *
 * 빈 줄이 **이미 끝에 있으면 더 붙이지 않는다**: `withBean`이 슬롯 배열을 그대로 상태로 되돌리므로,
 * 무조건 붙이면 한 글자 칠 때마다 빈 줄이 하나씩 쌓인다.
 */
export function beanSlots(meta: NoteMetaUpdate): Bean[] {
  const last = meta.beans.at(-1)
  return last !== undefined && last.description.value.trim() === '' ? [...meta.beans] : [...meta.beans, EMPTY_BEAN]
}

/** 원두 한 줄 갱신 — 마지막(빈) 슬롯을 건드리면 원두가 하나 는다. */
export function withBean(meta: NoteMetaUpdate, index: number, patch: Partial<Bean>): NoteMetaUpdate {
  const slots = beanSlots(meta).map((bean, i) => (i === index ? { ...bean, ...patch } : bean))
  return { ...meta, beans: slots }
}

/**
 * 공식 노트 목록 교체 — 칩을 더하거나 지운 결과 (시안의 칩 UI).
 *
 * **고치면 출처가 `user`가 된다**: 검색이 채운 노트를 손보는 순간 그것은 사용자의 값이고, 그래야 이후
 * 보강이 덮지 못한다(V-6). 목록이 통째로 비면 필드 자체가 없어진다 — 스칼라의 `userValue`와 같은 취급이다.
 */
export function withNotes(meta: NoteMetaUpdate, notes: string[]): NoteMetaUpdate {
  return { ...meta, official_notes: notes.length === 0 ? null : { value: notes, source: 'user' } }
}

/** 설명이 빈 원두는 보내지 않는다 — 폼에서 지우는 것이 곧 "이 원두는 아니었다"다(V-14와 같은 판정). */
export function trimBeans(meta: NoteMetaUpdate): NoteMetaUpdate {
  return { ...meta, beans: meta.beans.filter((bean) => bean.description.value.trim() !== '') }
}

/** 레시피가 없던 회차에 값을 넣으면 레시피가 생긴다 — 전 필드 null인 레시피는 서버가 드롭한다(V-8). */
export function withRecipe(entry: NoteEntryUpdate, brewIndex: number, patch: Partial<Recipe>): NoteEntryUpdate {
  return withBrew(entry, brewIndex, (brew) => ({ ...brew, recipe: { ...(brew.recipe ?? EMPTY_RECIPE), ...patch } }))
}

/** 감상이 없던 회차도 같다 — `my_taste`가 빈 tasting은 서버가 드롭한다(V-15). */
export function withTasting(
  entry: NoteEntryUpdate,
  brewIndex: number,
  patch: Partial<NoteDetailTasting>,
): NoteEntryUpdate {
  return withBrew(entry, brewIndex, (brew) => ({ ...brew, tasting: { ...(brew.tasting ?? EMPTY_TASTING), ...patch } }))
}

/** 결과 날짜 변경 — `targetDate`는 건드리지 않는다(위 `EntryDraft` 주석). */
export function withDate(entry: NoteEntryUpdate, date: string): NoteEntryUpdate {
  return { ...entry, date }
}

/**
 * 이 엔트리를 옮기면 합쳐질 상대 — 없으면 null (D-12).
 *
 * 판정 입력이 **화면이 이미 들고 있는 값**이라 서버에 묻지 않는다. 구 V-10은 충돌 여부를 서버가 계산해
 * pending에 실어 보냈는데(`date_conflict`, changes/0012), 그것이 필요했던 이유는 Slack에 폼이 없어
 * 클라이언트가 노트의 다른 날짜를 몰랐기 때문이다. 폼이 노트 전문을 들고 있는 지금은 사정이 다르다.
 */
export function mergeTargetOf(drafts: EntryDraft[], draft: EntryDraft): EntryDraft | null {
  if (draft.value.date === draft.targetDate) {
    return null
  }
  return drafts.find((other) => other !== draft && other.value.date === draft.value.date) ?? null
}

/** 로드 시점과 달라졌는가 — 안 바뀐 섹션의 [저장]을 잠그는 근거다(무변화 저장은 회차 행만 재발급한다). */
export function changed(before: unknown, after: unknown): boolean {
  return JSON.stringify(before) !== JSON.stringify(after)
}

function withBrew(
  entry: NoteEntryUpdate,
  brewIndex: number,
  update: (brew: NoteDetailBrew) => NoteDetailBrew,
): NoteEntryUpdate {
  return { ...entry, brews: entry.brews.map((brew, i) => (i === brewIndex ? update(brew) : brew)) }
}

const EMPTY_BEAN: Bean = { description: { value: '', source: 'user' }, process: null }

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

// my_taste_original은 계약에 없다 — 폼이 고치는 것은 정규화본이고, 원문 필드가 비면 서버가 정규화본을
// 양쪽에 담는다(V-11 뒷문장). 수정하면 "말한 그대로"가 편집본으로 수렴하는 것이 받아들인 대가다.
const EMPTY_TASTING: NoteDetailTasting = { my_taste: null, rating: null }
