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
 * **TΔ13b가 `mock.ts`를 다시 세웠다** — 수정·삭제 3종만이고 구현은 TΔ5b다. S2의 수정 슬라이스가 같은
 * 규율을 한 번 더 도는 것이고(D-10 ③ *"mock은 계약 초안이다"*), 그때 아래 두 번째 블록이 `./http`로
 * 옮겨가며 파일이 다시 사라진다. 계약의 정본은 그 사이에도 `src/test/resources/contract/`의 JSON 파일이다.
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
// TΔ5b가 서면 이 세 줄이 위 블록으로 합쳐진다.
export { deleteNote, patchNoteEntry, patchNoteMeta } from './mock'
export type * from './contract'
// 계약의 유일한 런타임 값 — 평가 4범주(V-1)는 서버가 열거해 주지 않는 고정 집합이라 상수로 산다.
export { RATINGS } from './contract'
