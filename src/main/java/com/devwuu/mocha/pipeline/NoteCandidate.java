package com.devwuu.mocha.pipeline;

/**
 * 추출 요청에 실리는 기존 노트 매칭 후보 (ref: data-model.md#3 existing_notes).
 * <p>LLM이 수신 메시지의 커피가 기존 노트와 동일한지 판정(matched_slug)하도록 최소 식별 정보만 넘긴다.
 * 매칭의 서버측 검증·확정은 이후 NoteMatcher(T2-3)가 맡는다.
 *
 * @param slug       기존 노트 식별자.
 * @param coffeeName 표시용 커피 이름.
 * @param roastery   로스터리(있으면).
 */
public record NoteCandidate(String slug, String coffeeName, String roastery) {
}
