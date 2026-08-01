/**
 * 화면이 잡는 API 표면 (changes/0029 TΔ10).
 *
 * **전부 실물이다** — `http.ts`가 `POST /api/agent/turn`(TΔ6a)·`POST /api/notes`·
 * `POST /api/agent/cancel`(TΔ6b)·`GET /api/notes/candidates`(TΔ7)·`POST /api/photos`(TΔ8a)·
 * **`GET /api/notes`·`GET /api/notes/{id}`(TΔ5a)** 를 부른다.
 *
 * TΔ10이 세운 규율 — *"계약(`contract.ts`)과 화면은 그대로 두고 구현만 갈아 끼운다"* — 이 두 슬라이스에서
 * 끝까지 지켜졌다: mock이 하나씩 빠지는 동안 이 파일에서 바뀐 것은 **재수출 줄의 출처뿐**이고
 * `contract.ts`도 화면도 손대지 않았다. "화면 먼저"(D-10 ③)가 재작업이 아니라 계약 도출이었다는
 * 사후 확인이고, S2에서도 같은 결론이다 — TΔ12·TΔ13a가 그린 화면이 한 줄도 바뀌지 않은 채 실 DB를 본다.
 *
 * `mock.ts`는 TΔ5a에서 삭제됐다. 같은 계약의 구현을 두 벌 남겨 두면 그것부터 갈라지기 때문이고,
 * 계약의 정본은 이제도 앞으로도 `src/test/resources/contract/`의 JSON 파일이다.
 */
export {
  getNoteCandidates,
  getNoteDetail,
  getNotes,
  postAgentCancel,
  postAgentTurn,
  postNoteCommit,
  postPhotos,
  toSearchParams,
} from './http'
export type * from './contract'
// 계약의 유일한 런타임 값 — 평가 4범주(V-1)는 서버가 열거해 주지 않는 고정 집합이라 상수로 산다.
export { RATINGS } from './contract'
