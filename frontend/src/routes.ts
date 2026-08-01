/**
 * 앱 경로 (changes/0029 TΔ12).
 *
 * `App.tsx`가 아니라 별도 파일인 것은 순환 import를 만들지 않기 위해서다 — `App`이 화면을 import하는데
 * 화면도 경로를 알아야 하고, 그 경로가 `App`에 살면 고리가 생긴다. 상수 하나짜리 파일이지만 고리 위의
 * `const`가 TDZ에 걸리는지를 따지는 것보다 싸다.
 *
 * TΔ13a가 `/notes/{id}`(상세)를 보탰다 — 경로가 값을 담게 되면서 만드는 쪽(`notePath`)과 읽는 쪽
 * (`matchNote`)이 한 자리에 산다. 정규식이 화면에 흩어지면 링크와 라우팅이 서로 다른 모양을 믿게 된다.
 */
export const CHAT = '/'
export const GALLERY = '/notes'

/** 상세 경로 — 갤러리 카드의 `href`이자 `App`이 고르는 키다. */
export function notePath(noteId: number): string {
  return `${GALLERY}/${noteId}`
}

/**
 * `/notes/{id}`면 그 id, 아니면 null.
 *
 * 숫자만 받는다 — `/notes/abc`는 상세가 아니라 모르는 경로이고, 모르는 경로는 대화로 떨어진다(`App`).
 * 서버에 있지도 않은 id로 요청을 보내 404를 받아 화면에 실패를 그리는 것보다, 애초에 상세로 보지 않는
 * 쪽이 정확하다.
 */
export function matchNote(path: string): number | null {
  const matched = /^\/notes\/(\d+)$/.exec(path)
  return matched === null ? null : Number(matched[1])
}

/**
 * 수정 경로 — 상세의 [수정] 버튼이 가는 곳이고, 저장 완료 안내가 *"커피 정보를 고치려면"*으로 거는 곳이다
 * (changes/0029 TΔ13b).
 *
 * 상세와 **경로를 나눈 것**이 결정이다(사용자 확정 2026-08-01): 읽는 화면과 고치는 화면이 한 컴포넌트에서
 * 모드로 갈리면 TΔ13a가 세운 *"상세는 읽는 화면이다"*가 흐려지고, 뒤로 가기가 모드를 되돌리지 못한다.
 */
export function editPath(noteId: number): string {
  return `${GALLERY}/${noteId}/edit`
}

/** `/notes/{id}/edit`면 그 id, 아니면 null. `matchNote`와 같은 이유로 숫자만 받는다. */
export function matchNoteEdit(path: string): number | null {
  const matched = /^\/notes\/(\d+)\/edit$/.exec(path)
  return matched === null ? null : Number(matched[1])
}
