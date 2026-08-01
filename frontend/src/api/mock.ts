import type { NoteFacets, NoteListResponse, NoteQuery, NoteSummary } from './contract'

/**
 * 아직 서지 않은 API의 mock (changes/0029 TΔ12).
 *
 * **mock은 임시방편이 아니라 계약 초안이다**(D-10 ③). 화면이 API보다 먼저인 슬라이스 규율에서, 여기서
 * 돌려주는 형태가 곧 `src/test/resources/contract/note-list.contract.json`이고 그것을 **TΔ5a가
 * 구현한다**. 그때 `index.ts`의 재수출 줄이 `http.ts`로 옮겨가고 이 파일은 사라진다 — 같은 계약의
 * 구현을 두 벌 남겨 두면 그것부터 갈라지기 때문이고, TΔ10~TΔ8a에서 mock 4종이 그렇게 소멸했다.
 *
 * 표본은 계약 파일의 예시와 같은 값이다(같은 노트·같은 순서). 화면을 보며 판단한 것과 자바 테스트가
 * 단언하는 것이 어긋나지 않게 하려는 것이고, **필터·정렬을 여기서 흉내 내지 않는다** — 서버가 정규화
 * 컬럼과 별칭으로 대조하는 것을(TΔ7과 같은 축) 클라이언트가 근사하면 그 근사가 계약처럼 보인다.
 */

const SAMPLE: NoteSummary[] = [
  {
    note_id: 48,
    coffee_name: '에티오피아 첼베사',
    roastery: '커피베라',
    latest_date: '2026-07-16',
    thumbnail_url: null,
  },
  {
    note_id: 21,
    coffee_name: '에티오피아 게뎁',
    roastery: '프릳츠',
    latest_date: '2026-07-02',
    thumbnail_url: null,
  },
  {
    note_id: 12,
    coffee_name: '에티오피아 게뎁',
    roastery: '모모스커피',
    latest_date: '2026-07-02',
    thumbnail_url: null,
  },
  {
    note_id: 7,
    coffee_name: '예가체프 G1',
    roastery: '프릳츠',
    latest_date: '2026-06-14',
    thumbnail_url: null,
  },
  {
    note_id: 5,
    coffee_name: '케냐 AA 키리냐가',
    roastery: null,
    latest_date: '2026-06-01',
    thumbnail_url: null,
  },
  {
    note_id: 3,
    coffee_name: '게뎁 워시드',
    roastery: null,
    latest_date: null,
    thumbnail_url: null,
  },
]

// 계약 파일과 달리 thumbnail_url이 전부 null이다 — 개발 중에는 그 경로에 실물 바이트가 없고,
// 깨진 <img>를 보여주는 것보다 사진 없는 노트의 플레이스홀더(시안의 사선 패턴)를 보는 쪽이 정확하다.
const FACETS: NoteFacets = {
  roastery: ['모모스커피', '커피베라', '프릳츠'],
  process: ['내추럴', '워시드', '허니'],
}

/** 커서 한 번만 더 도는 2페이지짜리 표본 — 무한 스크롤 배선이 실제로 한 번은 이어지는지 보려는 것이다. */
const PAGE_SIZE = 4

export async function getNotes(_query: NoteQuery, cursor: string | null): Promise<NoteListResponse> {
  const from = cursor === null ? 0 : Number(cursor)
  const page = SAMPLE.slice(from, from + PAGE_SIZE)
  const next = from + PAGE_SIZE
  return {
    notes: page,
    next_cursor: next < SAMPLE.length ? String(next) : null,
    total: SAMPLE.length,
    facets: FACETS,
  }
}
