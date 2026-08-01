/**
 * 화면이 잡는 API 표면 (changes/0029 TΔ10).
 *
 * S1(캡처)의 다섯은 **전부 실물이다** — `http.ts`가 `POST /api/agent/turn`(TΔ6a)·`POST /api/notes`·
 * `POST /api/agent/cancel`(TΔ6b)·`GET /api/notes/candidates`(TΔ7)·`POST /api/photos`(TΔ8a)를 부른다.
 * TΔ10이 세운 규율 — *"계약(`contract.ts`)과 화면은 그대로 두고 구현만 갈아 끼운다"* — 이 그 슬라이스에서
 * 끝까지 지켜졌다: mock이 하나씩 빠지는 동안 이 파일에서 바뀐 것은 **재수출 줄의 출처뿐**이고
 * `contract.ts`도 화면도 손대지 않았다. "화면 먼저"(D-10 ③)가 재작업이 아니라 계약 도출이었다는
 * 사후 확인이다.
 *
 * S2(갤러리·상세)가 같은 규율을 다시 시작한다 — `getNotes`(TΔ12)·`getNoteDetail`(TΔ13a)이 `mock.ts`에서
 * 온다. **TΔ5a가 `GET /api/notes`·`GET /api/notes/{id}`를 구현하면 아래 import 줄 하나가 `./http`로
 * 옮겨가고 `mock.ts`는 지워진다.** 그때까지도 계약의 정본은 mock이 아니라
 * `src/test/resources/contract/note-list.contract.json`·`note-detail.contract.json`이다.
 */
export { getNoteCandidates, postAgentCancel, postAgentTurn, postNoteCommit, postPhotos } from './http'
export { getNoteDetail, getNotes } from './mock'
export type * from './contract'
// 계약의 유일한 런타임 값 — 평가 4범주(V-1)는 서버가 열거해 주지 않는 고정 집합이라 상수로 산다.
export { RATINGS } from './contract'
