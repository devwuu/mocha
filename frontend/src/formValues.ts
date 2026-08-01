import type { Sourced } from './api/contract'

/**
 * 폼 입력값 → 도메인 값 (changes/0029 TΔ10 도입, TΔ13b에서 `chat/draftEdits.ts`에서 뽑아냈다).
 *
 * **여기 사는 유일한 규칙**: 사용자가 폼에서 고친 출처 표시 필드는 출처가 `user`가 된다
 * (FR-12 우선순위 `user > photo > search`). 캡처 폼(작성 중인 draft)과 수정 폼(저장된 노트)이 **같은 규칙**을
 * 쓰기 때문에 어느 한쪽에 살면 안 된다 — 갈라지는 순간 "고친 값이 곧 user"라는 성질이 화면마다 달라진다.
 *
 * 캡처 쪽에서 이 규칙이 없으면 다음 턴의 검색 보강이 사용자가 고친 값을 덮어쓰고(delta.md §1.2), 서버 V-6
 * 게이트가 그 정정을 거부한다. 수정 쪽에서 없으면 고친 값이 `photo`·`search`인 채로 저장돼 이후 어떤 경로가
 * 다시 덮어도 규칙상 정당해진다 — 방향만 다른 같은 사고다.
 *
 * 빈 문자열은 필드 제거(null)로 수렴시킨다 — 서버 정규화(V-8·V-14·V-15)와 같은 취급이라 폼에서 지운 값이
 * 공백 문자열로 살아 돌아오지 않는다.
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

/**
 * 출처 한국어 표기 — `user`는 목록에 없다(사용자 자신이 넣은 값에 "user"를 달아 두면 소음이다).
 *
 * 어휘가 여기 사는 이유는 규칙과 같다: 캡처 폼·수정 폼·상세가 *"이 로스팅은 봉투 사진에서 읽은 값"*을
 * 같은 말로 해야 사용자가 매번 다시 판단하지 않는다. **형태는 화면마다 다르다** — 각 시안이 정본이라
 * 컴포넌트는 공유하지 않는다(ADR-54).
 */
export const SOURCE_LABELS: Record<string, string> = { photo: '사진', search: '검색' }
