/**
 * 화면이 잡는 API 표면 (changes/0029 TΔ10).
 *
 * 지금은 mock으로 배선돼 있다 — 서버에 `POST /api/agent/turn`·`POST /api/notes`(TΔ6)와
 * `GET /api/notes/candidates`(TΔ7)가 아직 없기 때문이다. **그 task들에서 바뀌는 것은 아래 재수출
 * 한 줄뿐이다**: 계약(`contract.ts`)과 화면은 그대로 두고 구현만 fetch로 갈아 끼운다. 그것이
 * "화면 먼저"가 재작업이 아니라 계약 도출이 되게 하는 지점이다(tasks.md Phase 2 계약 규율).
 */
export { getNoteCandidates, postAgentTurn, postNoteCommit } from './mock'
export type * from './contract'
