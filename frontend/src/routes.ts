/**
 * 앱 경로 (changes/0029 TΔ12).
 *
 * `App.tsx`가 아니라 별도 파일인 것은 순환 import를 만들지 않기 위해서다 — `App`이 화면을 import하는데
 * 화면도 경로를 알아야 하고, 그 경로가 `App`에 살면 고리가 생긴다. 상수 하나짜리 파일이지만 고리 위의
 * `const`가 TDZ에 걸리는지를 따지는 것보다 싸다.
 *
 * TΔ13a가 `/notes/{id}`(상세)를 여기에 보탠다.
 */
export const CHAT = '/'
export const GALLERY = '/notes'
