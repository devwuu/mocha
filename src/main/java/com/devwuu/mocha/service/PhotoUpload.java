package com.devwuu.mocha.service;

/**
 * 업로드된 사진 1장 — 아직 검증도 스테이징도 되지 않은 원본 (ref: changes/0029 tasks.md TΔ8a).
 *
 * <p>전송 계층의 {@code MultipartFile}을 service까지 들이지 않기 위한 값객체다(백엔드 CLAUDE.md §2 —
 * <i>"호출부가 SDK 타입을 직접 참조하지 않는다"</i>의 같은 이유). 컨트롤러가 파트를 읽어 이 형태로 넘긴다.
 *
 * <p>{@link com.devwuu.mocha.repository.StagedImage}와 모양이 같지만 <b>단계가 다르다</b> — 그쪽은
 * <i>이미 스테이징된</i> 사진이고 포맷 게이트를 통과한 뒤이며, 이쪽은 게이트 앞이라 무엇이 들었는지 아직
 * 모른다. 같은 타입으로 합치면 그 경계가 이름에서 사라진다.
 *
 * @param filename 업로드 원본 파일명 — 저장소가 안전 문자로 정규화한다. 없으면 null 허용.
 * @param bytes    원본 바이트.
 */
public record PhotoUpload(String filename, byte[] bytes) {
}
