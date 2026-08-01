/**
 * 화면이 잡는 API 표면 (changes/0029 TΔ10).
 *
 * **턴은 실물이다**(TΔ6a) — `POST /api/agent/turn`이 섰고 `http.ts`가 그것을 부른다. 나머지 둘은 아직
 * mock이다: 저장(`POST /api/notes`)은 TΔ6b, 후보(`GET /api/notes/candidates`)는 TΔ7이 세운다.
 * **그 task들에서 바뀌는 것은 아래 재수출 줄뿐이다** — 계약(`contract.ts`)과 화면은 그대로 두고 구현만
 * 갈아 끼운다. 그것이 "화면 먼저"를 재작업이 아니라 계약 도출로 만드는 지점이다(tasks.md Phase 2 계약 규율).
 */
export { postAgentTurn } from './http'
export { getNoteCandidates, postNoteCommit } from './mock'
export type * from './contract'
