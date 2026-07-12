package com.devwuu.mocha.repository;

/**
 * 스테이징된 사진 1장의 원본 바이트 (ref: plan.md §3, changes/0010 ADR-23, FR-19).
 * <p>수신 사진 OCR([2.5])이 vision 입력으로 쓴다 — Slack {@code url_private}는 봇 토큰 인증이 필요해
 * OpenAI가 직접 못 읽으므로, 로컬 스테이징 바이트를 data URI로 인코딩해 넘긴다(findings-TΔ0 ①).
 *
 * @param name  스테이징 파일명(확장자 보존 — mime 판별에 쓴다).
 * @param bytes 원본 바이트.
 */
public record StagedImage(String name, byte[] bytes) {
}
