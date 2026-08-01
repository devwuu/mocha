/**
 * 화면이 잡는 API 표면 (changes/0029 TΔ10).
 *
 * **전부 실물이다** — `http.ts`가 `POST /api/agent/turn`(TΔ6a)·`POST /api/notes`·`POST /api/agent/cancel`
 * (TΔ6b)·`GET /api/notes/candidates`(TΔ7)·`POST /api/photos`(TΔ8a)를 부른다. TΔ10이 세운 규율 — *"계약(`contract.ts`)과 화면은
 * 그대로 두고 구현만 갈아 끼운다"* — 이 끝까지 지켜졌다: mock이 하나씩 빠지는 동안 이 파일에서 바뀐 것은
 * **재수출 줄의 출처뿐**이고 `contract.ts`도 화면도 손대지 않았다. 그것이 "화면 먼저"(D-10 ③)가 재작업이
 * 아니라 계약 도출이었다는 사후 확인이다.
 *
 * mock 파일은 마지막 소비자가 사라지며 함께 지웠다 — 같은 계약의 구현을 두 벌 남겨 두면 그것부터 갈라진다.
 */
export { getNoteCandidates, postAgentCancel, postAgentTurn, postNoteCommit, postPhotos } from './http'
export type * from './contract'
