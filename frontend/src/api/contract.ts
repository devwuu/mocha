/**
 * 서버와 공유하는 REST 계약의 TypeScript 판본 (changes/0029 TΔ10).
 *
 * 정본은 `src/test/resources/contract/`의 JSON 파일이고 — `turn-draft.contract.json`(TΔ2 캡처본),
 * `agent-turn.contract.json`, `note-commit.contract.json` — 자바 쪽에서 `ClientApiContractTest`가
 * 서로의 정합을 단언한다. 이 파일은 그 형태를 타입으로 옮긴 것이다.
 *
 * 계약을 바꾸려면 JSON 파일과 이 파일을 함께 고친다. 필드명이 어긋나면 컴파일러가 아니라
 * **런타임에 값이 조용히 사라지는** 방식으로 실패한다 — 사용자가 폼에서 고친 값이 되돌아가는
 * 이 델타의 실패(delta.md §1.2)가 그대로 재현되는 경로다.
 */

/** 출처 우선순위 user > photo > search (V-6). 사용자가 폼에서 고치면 그 필드는 `user`가 된다. */
export type Source = 'user' | 'photo' | 'search'

/** 출처 표시 필드 — `{ value, source }` (V-5). */
export interface Sourced<T> {
  value: T
  source: Source
}

/** 4범주 평가 또는 null(미언급) — 정의 외 값은 서버가 거부한다(V-1). */
export const RATINGS = ['완전 내스타일', '맛있다', '맛은 있는데 내스타일은 아님', '맛이 없다'] as const
export type Rating = (typeof RATINGS)[number]

/** 원두 1종 — 단일 원두도 요소 1개, 정보 전무면 빈 배열(V-14). */
export interface Bean {
  description: Sourced<string>
  process: Sourced<string> | null
}

/** 회차 추출 레시피 — 방식별 분기 없는 flat 스키마, 전 필드 nullable(V-8). 사용자 발화 전용(보강 금지). */
export interface Recipe {
  method: string | null
  dose_g: number | null
  water_ml: number | null
  yield_ml: number | null
  time_sec: number | null
  temp_c: number | null
  grind: string | null
  machine: string | null
  pouring: string | null
  feedback: string | null
}

/** 회차 맛 감상 — `my_taste`가 있으면 `my_taste_original`도 함께 있다(V-11). */
export interface Tasting {
  my_taste: string | null
  my_taste_original: string | null
  rating: Rating | null
}

/** 회차 1개 — 배열 순서가 곧 회차 번호다. 둘 다 null인 회차는 서버가 드롭한다(V-15). */
export interface Brew {
  recipe: Recipe | null
  tasting: Tasting | null
}

/** 날짜별 시음 기록 — `date`가 entries 내 유일 키다(V-3). */
export interface Entry {
  date: string
  brews: Brew[]
  updated_at: string | null
}

/**
 * 작성 중인 노트.
 *
 * `aliases`가 없는 것이 이 델타의 결정이다(TΔ10, TΔ2 이월 (b) 해소): 내부 매칭 전용 별칭(V-13)은
 * 폼이 표시하지도 편집하지도 않고, 커밋 시 축적은 저장된 값을 읽어 서버가 계산한다. 계약에서 뺀다.
 */
export interface DraftNote {
  id: number | null
  coffee_name: Sourced<string> | null
  roastery: Sourced<string> | null
  beans: Bean[]
  roast_level: Sourced<string> | null
  official_notes: Sourced<string[]> | null
  sources: string[]
  entries: Entry[]
  created_at: string | null
  updated_at: string | null
}

/** 매칭 배지 — 신규/기존. 사용자가 배지에서 바꿀 수 있으므로 draft의 일부다(OQ-2 ㉢, 시트는 TΔ11). */
export type MatchInfo =
  | { type: 'new' }
  | { type: 'existing'; note_id: number; date?: string }

/** 폼 상태 전체 = 다음 턴의 draft = 저장 본문. 방향만 다른 같은 값이다. */
export interface Draft {
  note: DraftNote
  match: MatchInfo
}

/** `POST /api/agent/turn` — 첫 턴은 폼이 없으므로 `draft`가 null이다. */
export interface AgentTurnRequest {
  utterance: string
  draft: Draft | null
}

/** 턴 응답. 제안이 없었던 턴(잡담·조회·검증 거부)은 `draft`가 null이고, 그때 폼은 그대로 남는다. */
export interface AgentTurnResponse {
  reply: string
  draft: Draft | null
}

/** `POST /api/notes` — 폼 확정 저장(= 구 [저장] 버튼). 본문이 곧 확정된 draft다. */
export type NoteCommitRequest = Draft

export interface NoteCommitResponse {
  note_id: number
}
