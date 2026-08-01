import type { NoteQuery } from '../api'

/**
 * 갤러리 필터 상태의 순수 조작 (changes/0029 TΔ12).
 *
 * 화면(`GalleryScreen`)에서 분리한 이유는 `draftEdits.ts`와 같다 — 상태 전이가 React 밖에서 읽히면
 * "무엇이 쿼리를 바꾸는가"가 한눈에 보인다.
 *
 * **요청 조립(`toSearchParams`)은 TΔ5a에서 `api/http.ts`로 옮겼다** — 그것은 필터 *상태*의 조작이 아니라
 * `GET /api/notes`의 *계약*이고, 실제 요청을 만드는 곳과 갈라 두면 "같은 질의"의 정의가 두 벌이 된다.
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

