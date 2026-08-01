package com.devwuu.mocha.repository;

/**
 * 스테이징된 사진 1장의 원본 바이트 (ref: plan.md §3, changes/0010 ADR-23, FR-19).
 * <p>수신 사진 OCR([2.5])이 vision 입력으로 쓴다 — 모델에 URL을 주지 않고 로컬 스테이징 바이트를
 * data URI로 인코딩해 넘긴다(findings-TΔ0 ①). 애초 근거는 Slack {@code url_private}가 봇 토큰 인증을
 * 요구해 OpenAI가 직접 못 읽는다는 것이었고, 업로드가 앱으로 바뀐 뒤로는 <b>사진이 외부에서 접근
 * 가능한 URL을 갖지 않는다</b>는 것이 같은 결론의 근거다.
 *
 * @param name  스테이징 파일명(확장자 보존 — mime 판별에 쓴다).
 * @param bytes 원본 바이트.
 */
public record StagedImage(String name, byte[] bytes) {
}
