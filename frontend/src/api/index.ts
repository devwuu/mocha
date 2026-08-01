/**
 * 화면이 잡는 API 표면 (changes/0029 TΔ10).
 *
 * **턴·저장·취소는 실물이다**(TΔ6a·TΔ6b) — `http.ts`가 `POST /api/agent/turn`·`POST /api/notes`·
 * `POST /api/agent/cancel`을 부른다. 남은 mock은 후보(`GET /api/notes/candidates`) 하나이고 TΔ7이
 * 가져간다. **그 task에서 바뀌는 것은 아래 재수출 줄뿐이다** — 계약(`contract.ts`)과 화면은 그대로 두고
 * 구현만 갈아 끼운다. 그것이 "화면 먼저"를 재작업이 아니라 계약 도출로 만드는 지점이다
 * (tasks.md Phase 2 계약 규율).
 */
export { postAgentCancel, postAgentTurn, postNoteCommit } from './http'
export { getNoteCandidates } from './mock'
export type * from './contract'
