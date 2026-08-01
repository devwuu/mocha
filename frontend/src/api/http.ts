/**
 * 실제 서버를 부르는 API 구현 (changes/0029 TΔ6a).
 *
 * 계약(`contract.ts`)과 화면은 그대로 두고 여기가 mock을 대체한다 — `index.ts`의 재수출 한 줄만 옮기면
 * 되는 것이 TΔ10이 세운 규율이었고, 이 파일이 그 자리다. S1(캡처) 다섯에 이어 **TΔ5a가 S2의 목록·상세를**
 * 가져오며 `mock.ts`가 통째로 사라졌다 — 이 델타의 읽기·쓰기 표면이 여기 다 있다.
 *
 * 오리진은 하나다(ADR-78) — 서버가 이 번들을 정적 서빙하므로 base URL도 CORS도 없다. 개발 중에는
 * Vite dev server가 `/api`를 프록시한다(TΔ23 POLICY: 프록시 대상은 `/api` 단일 접두).
 */
import type {
  AgentTurnRequest,
  AgentTurnResponse,
  NoteCandidatesResponse,
  NoteCommitRequest,
  NoteCommitResponse,
  NoteDetail,
  NoteListResponse,
  NoteQuery,
  PhotoUploadResponse,
  Rating,
} from './contract'

export async function postAgentTurn(request: AgentTurnRequest): Promise<AgentTurnResponse> {
  return postJson<AgentTurnResponse>('/api/agent/turn', request)
}

/**
 * 사진 업로드 — ＋로 고른 순간 보낸다(TΔ8a).
 *
 * 전송 버튼을 기다리지 않는 것이 의도다: 사용자가 발화를 쓰는 동안 업로드가 겹쳐 진행되고, 폰에서
 * 원본 JPEG 몇 MB를 올리는 시간이 체감에서 사라진다. 돌아온 이름은 화면이 들고 있다가 다음 턴에 싣는다.
 *
 * multipart라 `postJson`을 쓰지 않는다 — `Content-Type`을 직접 정하면 브라우저가 붙이는 boundary가
 * 빠져 서버가 파트를 못 읽는다. FormData를 그대로 넘기고 헤더는 건드리지 않는 것이 정답이다.
 */
export async function postPhotos(files: File[]): Promise<PhotoUploadResponse> {
  const form = new FormData()
  for (const file of files) {
    form.append('photos', file)
  }
  const response = await fetch('/api/photos', { method: 'POST', body: form })
  if (!response.ok) {
    throw new Error(`HTTP ${response.status}`)
  }
  return (await response.json()) as PhotoUploadResponse
}

/**
 * 매칭 후보 검색 — 변경 시트가 열릴 때와 검색어가 바뀔 때(250ms 디바운스, TΔ11).
 *
 * **정렬도 필터도 여기서 하지 않는다**: 서버가 커피명 → 로스터리 → 최근 시음일 순으로 돌려주고, 대조는
 * 정규화 컬럼과 별칭 인덱스를 지난다(TΔ7). 클라이언트가 한 번 더 거르면 별칭으로 잡힌 후보 — 사용자가
 * `예가체프 지1`로 쳐서 나온 `예가체프 G1` 노트 — 가 화면에서 도로 사라진다.
 */
export async function getNoteCandidates(query: string): Promise<NoteCandidatesResponse> {
  return getJson<NoteCandidatesResponse>(`/api/notes/candidates?q=${encodeURIComponent(query)}`)
}

/** 폼 확정 저장 — 본문은 턴 응답으로 받은 draft 그대로다(방향만 다른 같은 값). */
export async function postNoteCommit(request: NoteCommitRequest): Promise<NoteCommitResponse> {
  return postJson<NoteCommitResponse>('/api/notes', request)
}

/**
 * [취소] 통지 — 본문도 응답도 없다(204).
 *
 * 폼은 클라이언트 상태라 버리면 끝이지만 대화 문맥 접힘은 서버 상태다(TΔ6b). 알리지 않으면 버린 작업의
 * 문맥이 TTL까지 살아남아 다음 커피의 첫 발화에 섞인다.
 */
export async function postAgentCancel(): Promise<void> {
  const response = await fetch('/api/agent/cancel', { method: 'POST' })
  if (!response.ok) {
    throw new Error(`HTTP ${response.status}`)
  }
}

/**
 * 갤러리 목록 — 검색어 + 필터 4축 + 커서 페이징 (TΔ12 화면 · TΔ5a 서버).
 *
 * **필터도 정렬도 여기서 하지 않는다**(후보 검색과 같은 규율): 서버가 최근 시음일 내림차순으로 돌려주고
 * 검색어 대조는 정규화 컬럼과 별칭을 지난다. 클라이언트가 한 번 더 거르면 별칭으로 잡힌 노트가 화면에서
 * 도로 사라진다.
 *
 * `cursor`는 서버가 준 값 그대로다 — 만들지도 해석하지도 않는다(불투명 계약).
 */
export async function getNotes(query: NoteQuery, cursor: string | null): Promise<NoteListResponse> {
  return getJson<NoteListResponse>(`/api/notes?${toSearchParams(query, cursor).toString()}`)
}

/**
 * 노트 상세 — 전문 + 날짜별 사진 (TΔ13a 화면 · TΔ5a 서버).
 *
 * 없는 id는 404이고 `getJson`이 Error로 올린다. 화면은 404와 그 밖의 실패를 문구로 가르지 않는다 —
 * 둘 다 *"노트를 불러오지 못했어요"*이고, 가를 필요가 관측되면 그때가 판단 지점이다(`DetailScreen` POLICY).
 */
export async function getNoteDetail(noteId: number): Promise<NoteDetail> {
  return getJson<NoteDetail>(`/api/notes/${noteId}`)
}

/**
 * 쿼리 상태 → `GET /api/notes`의 파라미터.
 *
 * **빈 값은 키째 뺀다.** `?q=&origin=`처럼 빈 문자열을 실어 보내면 서버가 *"빈 문자열로 필터"*와
 * *"필터 없음"*을 구분해야 하고, 그 구분은 어느 쪽에도 쓸모가 없다. 다중 축은 **같은 키를 반복**한다 —
 * 쉼표로 이으면 값 안의 쉼표(`커피 리브레, 성수`)와 구분자가 충돌한다.
 *
 * 갤러리 화면이 이것을 `useEffect`의 의존 키로도 쓴다(`toSearchParams(query, null).toString()`): 객체
 * 동일성이 아니라 **실제로 서버에 물어볼 내용**이 바뀌었을 때만 다시 조회한다. 그래서 이 함수가 화면이
 * 아니라 여기 산다 — 요청을 조립하는 규칙이 두 벌이면 "같은 질의"의 정의가 둘로 갈린다(TΔ5a에서 이동).
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

/**
 * 실패는 삼키지 않고 Error로 올린다 — 화면이 그 자리에 시스템 알림을 남긴다(ChatScreen의 catch).
 *
 * 서버는 턴 실패에 **성공 형태의 본문을 돌려주지 않는다**(TΔ6a): 안내 문구를 `reply`에 실으면 실패가
 * 정상 응답으로 보이기 때문이다. 그래서 여기서 상태 코드가 곧 성패이고, 안내 문구의 소유자는 화면이다.
 */
async function getJson<T>(path: string): Promise<T> {
  const response = await fetch(path)
  if (!response.ok) {
    throw new Error(`HTTP ${response.status}`)
  }
  return (await response.json()) as T
}

async function postJson<T>(path: string, body: unknown): Promise<T> {
  const response = await fetch(path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(body),
  })
  if (!response.ok) {
    throw new Error(`HTTP ${response.status}`)
  }
  return (await response.json()) as T
}
