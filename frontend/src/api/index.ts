/**
 * 화면이 잡는 API 표면 (changes/0029 TΔ10).
 *
 * **전부 실물이다** — `http.ts`가 `POST /api/agent/turn`(TΔ6a)·`POST /api/notes`·
 * `POST /api/agent/cancel`(TΔ6b)·`GET /api/notes/candidates`(TΔ7)·`POST /api/photos`(TΔ8a)·
 * `GET /api/notes`·`GET /api/notes/{id}`(TΔ5a)·**`PATCH`·`DELETE /api/notes/{id}`(TΔ5b-3)** 를 부른다.
 *
 * TΔ10이 세운 규율 — *"계약(`contract.ts`)과 화면은 그대로 두고 구현만 갈아 끼운다"* — 이 세 슬라이스에서
 * 끝까지 지켜졌다: mock이 하나씩 빠지는 동안 이 파일에서 바뀐 것은 **재수출 줄의 출처뿐**이고
 * `contract.ts`도 화면도 손대지 않았다. "화면 먼저"(D-10 ③)가 재작업이 아니라 계약 도출이었다는
 * 사후 확인이고, **TΔ5b-3에서 `mock.ts`가 다시 사라지며 그 규율이 이 델타에서 닫혔다** — 화면의
 * [저장]·[삭제]가 서버에 남기 시작하는 지점이다(AC-5·AC-6).
 *
 * 계약의 정본은 그 사이에도 mock이 아니라 `src/test/resources/contract/`의 JSON 파일이었다.
 */
export {
  deleteNote,
  getNoteCandidates,
  getNoteDetail,
  getNotes,
  patchNoteEntry,
  patchNoteMeta,
  postAgentCancel,
  postAgentTurn,
  postNoteCommit,
  postPhotos,
  toSearchParams,
} from './http'
export type * from './contract'
// 계약의 유일한 런타임 값 — 평가 4범주(V-1)는 서버가 열거해 주지 않는 고정 집합이라 상수로 산다.
export { RATINGS } from './contract'
