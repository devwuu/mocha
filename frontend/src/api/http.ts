/**
 * 실제 서버를 부르는 API 구현 (changes/0029 TΔ6a).
 *
 * 계약(`contract.ts`)과 화면은 그대로 두고 여기가 mock을 대체한다 — `index.ts`의 재수출 한 줄만 옮기면
 * 되는 것이 TΔ10이 세운 규율이었고, 이 파일이 그 자리다. TΔ6b가 저장·취소를, **TΔ7이 마지막으로 남았던
 * 후보 검색을** 가져오며 mock은 전부 소멸했다 — 이 델타의 API 표면이 여기 다 있다.
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
} from './contract'

export async function postAgentTurn(request: AgentTurnRequest): Promise<AgentTurnResponse> {
  return postJson<AgentTurnResponse>('/api/agent/turn', request)
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
