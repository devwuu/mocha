package com.devwuu.mocha.pipeline;

/**
 * 매칭 대조에 쓰는 최종 식별 정보 — OCR 오버레이(V-6) 반영 후의 커피명·로스터리
 * (ref: plan.md §3 match(extraction, identity, existingNotes); changes/0016 ADR-37).
 * <p>추출값(사용자 발화)이 우선하고, 비면 선행 OCR(FR-19)이 읽은 값이 채운다 — 사진-only 정체성
 * 발화(텍스트에 커피명 없음, AC-Δ3)도 이 식별 정보로 기존 노트 별칭 집합과 정규화 대조된다.
 * {@link NoteMatcher}가 LLM {@code matched_slug} 판정을 서버 결정적으로 보조하는 입력이다.
 *
 * @param coffeeName 최종 커피명(없으면 null) — 별칭 대조의 필수 앵커(비면 대조 불가).
 * @param roastery   최종 로스터리(없으면 null) — 양쪽에 있을 때 위양성 차단용 보조 조건.
 */
public record MatchIdentity(String coffeeName, String roastery) {

    public static MatchIdentity of(String coffeeName, String roastery) {
        return new MatchIdentity(coffeeName, roastery);
    }
}
