import type { NoteQuery, Rating } from '../api'

/**
 * 갤러리 필터 상태의 순수 조작 (changes/0029 TΔ12).
 *
 * 화면(`GalleryScreen`)에서 분리한 이유는 `draftEdits.ts`와 같다 — 상태 전이가 React 밖에서 읽히면
 * "무엇이 쿼리를 바꾸는가"가 한눈에 보이고, 여기서 만든 파라미터 문자열이 곧 TΔ5a가 구현할 계약이다.
 */

export const EMPTY_QUERY: NoteQuery = {
  q: '',
  roastery: [],
  process: [],
  origin: '',
  rating: [],
}

/** 다중 선택 축 — 값을 넣고 빼는 토글. 같은 축 안은 OR이므로 순서는 의미가 없다. */
export function toggle<T extends string>(selected: T[], value: T): T[] {
  return selected.includes(value) ? selected.filter((item) => item !== value) : [...selected, value]
}

/** 필터가 하나라도 걸려 있는가 — 빈 목록을 "기록이 없다"와 "걸러져서 없다" 중 무엇으로 안내할지 가른다. */
export function isFiltered(query: NoteQuery): boolean {
  return (
    query.q !== '' ||
    query.origin !== '' ||
    query.roastery.length > 0 ||
    query.process.length > 0 ||
    query.rating.length > 0
  )
}

/**
 * 쿼리 상태 → `GET /api/notes`의 파라미터.
 *
 * **빈 값은 키째 뺀다.** `?q=&origin=`처럼 빈 문자열을 실어 보내면 서버가 *"빈 문자열로 필터"*와
 * *"필터 없음"*을 구분해야 하고, 그 구분은 어느 쪽에도 쓸모가 없다. 다중 축은 **같은 키를 반복**한다 —
 * 쉼표로 이으면 값 안의 쉼표(`커피 리브레, 성수`)와 구분자가 충돌한다.
 *
 * 이 문자열이 `useEffect`의 의존 키이기도 하다: 객체 동일성이 아니라 **실제로 서버에 물어볼 내용**이
 * 바뀌었을 때만 다시 조회한다.
 */
export function toSearchParams(query: NoteQuery, cursor: string | null): URLSearchParams {
  const params = new URLSearchParams()
  appendIfPresent(params, 'q', query.q)
  appendEach(params, 'roastery', query.roastery)
  appendEach(params, 'process', query.process)
  appendIfPresent(params, 'origin', query.origin)
  appendEach(params, 'rating', query.rating)
  // 커서는 서버가 준 값 그대로다 — 만들지도 해석하지도 않는다(불투명 계약).
  if (cursor !== null) {
    params.set('cursor', cursor)
  }
  return params
}

function appendIfPresent(params: URLSearchParams, key: string, value: string) {
  const trimmed = value.trim()
  if (trimmed !== '') {
    params.set(key, trimmed)
  }
}

function appendEach(params: URLSearchParams, key: string, values: readonly (string | Rating)[]) {
  for (const value of values) {
    params.append(key, value)
  }
}
