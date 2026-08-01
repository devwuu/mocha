import type { NoteDetail, NoteFacets, NoteListResponse, NoteQuery, NoteSummary } from './contract'

/**
 * 아직 서지 않은 API의 mock (changes/0029 TΔ12 목록 · TΔ13a 상세).
 *
 * **mock은 임시방편이 아니라 계약 초안이다**(D-10 ③). 화면이 API보다 먼저인 슬라이스 규율에서, 여기서
 * 돌려주는 형태가 곧 `src/test/resources/contract/note-list.contract.json`·`note-detail.contract.json`
 * 이고 그것을 **TΔ5a가 구현한다**(목록·상세 둘 다 그 task다). 그때 `index.ts`의 재수출 줄이 `http.ts`로
 * 옮겨가고 이 파일은 사라진다 — 같은 계약의
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

/**
 * 상세 표본 3종 — `note-detail.contract.json`의 예시와 같은 값이다(TΔ13a).
 *
 * 목록 표본 6건 중 셋만 여기 있고 **나머지 id는 404로 떨어진다.** 없는 노트를 지어내지 않는 것이 의도다 —
 * 화면이 실패 경로(못 불러온 상태)를 실물로 보여줄 수 있어야 하고, 그 경로는 TΔ5a가 실물 404를 돌려주기
 * 시작해도 그대로 남는다.
 *
 * 셋이 덮는 것: **전문**(사진 2·회차 2·레시피만/감상만·원두 2·출처 3종) · **최소형**(사진 없음, 메타 대부분
 * null, 평가 미언급) · **엔트리 없는 노트**(저장 직후 삭제 등). 계약 파일이 nullable 지점을 예시로 박는
 * 것과 같은 이유이고, 여기서는 그 지점들이 실제로 렌더되는지가 확인 대상이다.
 */
const DETAILS: NoteDetail[] = [
  {
    note_id: 21,
    coffee_name: { value: '에티오피아 게뎁', source: 'photo' },
    roastery: { value: '프릳츠', source: 'photo' },
    beans: [
      {
        description: { value: '에티오피아 게뎁 헤어룸', source: 'search' },
        process: { value: '워시드', source: 'search' },
      },
      { description: { value: '콜롬비아 우일라 카투라', source: 'user' }, process: null },
    ],
    roast_level: { value: '라이트', source: 'photo' },
    official_notes: { value: ['자몽', '베르가못', '홍차'], source: 'search' },
    sources: ['https://fritz.co.kr/products/ethiopia-gedeb'],
    entries: [
      {
        date: '2026-06-28',
        brews: [
          {
            recipe: {
              method: '핸드드립',
              dose_g: 15,
              water_ml: 240,
              yield_ml: null,
              time_sec: 160,
              temp_c: 92,
              grind: '210클릭',
              machine: '매버릭 2.0',
              pouring: '뜸 40ml 30초 → 100ml → 100ml',
              feedback: '다음엔 한 단계 굵게',
            },
            tasting: {
              my_taste: '새콤하고 좋았다. 식으니까 자몽 같은 신맛이 올라온다.',
              rating: '맛있다',
            },
          },
        ],
        // 계약 예시와 같이 사진이 없는 날이다 — 같은 노트 안에서 사진 있는 날과 없는 날이 섞인다.
        photos: [],
      },
      {
        date: '2026-07-02',
        brews: [
          {
            recipe: {
              method: '핸드드립',
              dose_g: 15,
              water_ml: 240,
              yield_ml: null,
              time_sec: null,
              temp_c: 90,
              grind: '220클릭',
              machine: '매버릭 2.0',
              pouring: null,
              feedback: null,
            },
            tasting: null,
          },
          {
            recipe: null,
            tasting: { my_taste: '온도 낮추니 떫은 맛이 사라졌다.', rating: '완전 내스타일' },
          },
        ],
        /*
         * 계약 예시는 `/api/photos/…` 두 장이지만 개발 중에는 그 경로에 실물 바이트가 없다. 목록 표본은
         * 같은 사정에서 null을 골랐는데(사진 없는 칸이 시안의 사선 패턴으로 채워져 그 자체가 정상 상태다)
         * 상세의 히어로는 **비면 확인할 것이 사라진다** — 그래서 번들에 실제로 있는 이미지를 세워
         * 히어로·사진 스트립이 그려지는지를 개발 중에도 본다. URL 규칙의 정본은 계약 파일이다.
         */
        photos: [{ url: '/mascot-face.png' }, { url: '/mascot-face.png' }],
      },
    ],
  },
  {
    note_id: 12,
    coffee_name: { value: '에티오피아 게뎁', source: 'user' },
    roastery: { value: '모모스커피', source: 'user' },
    beans: [],
    roast_level: null,
    official_notes: null,
    sources: [],
    entries: [
      {
        date: '2026-07-02',
        brews: [{ recipe: null, tasting: { my_taste: '그냥 무난했다.', rating: null } }],
        photos: [],
      },
    ],
  },
  {
    note_id: 3,
    coffee_name: { value: '게뎁 워시드', source: 'user' },
    roastery: null,
    beans: [],
    roast_level: null,
    official_notes: null,
    sources: [],
    entries: [],
  },
]

export async function getNoteDetail(noteId: number): Promise<NoteDetail> {
  const found = DETAILS.find((detail) => detail.note_id === noteId)
  if (found === undefined) {
    throw new Error('HTTP 404')
  }
  return found
}
