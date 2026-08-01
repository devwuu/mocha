/**
 * 실제 서버를 부르는 API 구현 (changes/0029 TΔ6a).
 *
 * 계약(`contract.ts`)과 화면은 그대로 두고 여기가 mock을 대체한다 — `index.ts`의 재수출 한 줄만 옮기면
 * 되는 것이 TΔ10이 세운 규율이었고, 이 파일이 그 자리다. 아직 서 있지 않은 엔드포인트
 * (`POST /api/notes` TΔ6b · `GET /api/notes/candidates` TΔ7)는 계속 mock이다.
 *
 * 오리진은 하나다(ADR-78) — 서버가 이 번들을 정적 서빙하므로 base URL도 CORS도 없다. 개발 중에는
 * Vite dev server가 `/api`를 프록시한다(TΔ23 POLICY: 프록시 대상은 `/api` 단일 접두).
 */
import type { AgentTurnRequest, AgentTurnResponse } from './contract'

export async function postAgentTurn(request: AgentTurnRequest): Promise<AgentTurnResponse> {
  return postJson<AgentTurnResponse>('/api/agent/turn', request)
}

/**
 * 실패는 삼키지 않고 Error로 올린다 — 화면이 그 자리에 시스템 알림을 남긴다(ChatScreen의 catch).
 *
 * 서버는 턴 실패에 **성공 형태의 본문을 돌려주지 않는다**(TΔ6a): 안내 문구를 `reply`에 실으면 실패가
 * 정상 응답으로 보이기 때문이다. 그래서 여기서 상태 코드가 곧 성패이고, 안내 문구의 소유자는 화면이다.
 */
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
