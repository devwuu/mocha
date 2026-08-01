import type { NoteCandidate, NoteCandidatesResponse } from './contract'

/**
 * 아직 서지 않은 API의 mock 구현 (changes/0029 TΔ10).
 *
 * 슬라이스 규율상 화면이 API보다 먼저다(delta.md D-10 ③). 해당 API가 설 때까지 화면은 이 mock을 상대로
 * 돈다 — **에이전트 흉내가 목적이 아니라, 계약이 화면을 실제로 굴릴 수 있는 모양인지 확인하는 것이
 * 목적이다**. 그래서 응답은 고정 픽스처이고 발화 내용을 해석하지 않는다.
 *
 * **턴 mock은 TΔ6a에서, 저장 mock은 TΔ6b에서 빠졌다** — 실물이 섰고(`http.ts`), 같은 계약의 구현을
 * 두 벌 남겨 두면 그것부터 갈라진다. 남은 후보 mock은 TΔ7이 같은 방식으로 가져간다.
 */

// 실제 호출은 DB를 지나 즉답이 아니다 — 로딩 표시가 실제로 보이는지 확인하려면 지연이 필요하다.
const MOCK_LATENCY_MS = 700

/**
 * 매칭 후보 픽스처 — **동명 다른 로스터리가 셋 들어 있는 것이 의도다**(TΔ11). 싱글 오리진에서 흔한
 * 형태이고, 시트가 그것을 구분해 보여주지 못하면 사용자가 고를 수 없다는 것이 이 화면의 검증 대상이다.
 */
const CANDIDATES: NoteCandidate[] = [
  { note_id: 12, coffee_name: '에티오피아 게뎁', roastery: '모모스커피', latest_date: '2026-07-28' },
  { note_id: 7, coffee_name: '에티오피아 게뎁 G1', roastery: '프릳츠', latest_date: '2026-06-14' },
  { note_id: 3, coffee_name: '게뎁 워시드', roastery: null, latest_date: null },
  { note_id: 9, coffee_name: '봄맞이 블렌드', roastery: '커피베라', latest_date: '2026-07-19' },
  { note_id: 5, coffee_name: '콜롬비아 엘 파라이소', roastery: '모모스커피', latest_date: '2026-05-02' },
]

export async function getNoteCandidates(query: string): Promise<NoteCandidatesResponse> {
  await delay(MOCK_LATENCY_MS / 2)
  // 부분일치는 시트가 "좁혀지는지"를 확인하기 위한 최소 동작이다 — 실제 서버는 정규화 컬럼과 별칭
  // 인덱스(changes/0028)로 대조하므로 한/영 교차·표기 흔들림까지 잡는다(TΔ7).
  const needle = query.trim().toLowerCase()
  const matched = needle === ''
    ? CANDIDATES
    : CANDIDATES.filter(
        (candidate) =>
          candidate.coffee_name.toLowerCase().includes(needle) ||
          (candidate.roastery?.toLowerCase().includes(needle) ?? false),
      )
  return { candidates: matched }
}

function delay(ms: number): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, ms))
}
