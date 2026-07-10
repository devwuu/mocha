package com.devwuu.mocha.domain;

import java.time.OffsetDateTime;

/**
 * 확인 대기 노트 — {@code data/pending.json} (ref: data-model.md#2.3, ADR-3).
 * <p>단일 사용자 전제(NFR-6)로 사용자당 최대 1건.
 *
 * @param draft     Note(entries 포함)와 동일 구조의 작성 중 데이터.
 * @param match     신규/기존 판정 결과(미리보기 표시용).
 * @param previewTs 미리보기 Slack 메시지 timestamp — 수정 시 edit 대상.
 * @param createdAt TTL 판정 기준(mocha.pending.ttl).
 */
public record PendingNote(
        Note draft,
        MatchInfo match,
        String previewTs,
        OffsetDateTime createdAt
) {
}
