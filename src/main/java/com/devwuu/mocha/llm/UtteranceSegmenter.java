package com.devwuu.mocha.llm;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;

/**
 * 다중 날짜 발화 세그먼트 분리 경계 — 결정론 날짜 탐지기가 절대 날짜 2개 이상을 찾은 턴에만, 에이전트 루프
 * 진입 전 1콜로 원문을 시음 날짜별 세그먼트로 분리한다
 * (ref: plan.md#ADR-61, data-model.md#4.3, spec FR-15/22; changes/0023).
 * <p>에이전트 루프 밖 보조 콜 경계로 {@code VisionClient}·{@code AliasGenerator}와 함께 유지된다
 * (REVIEW.md §2 표 등재, NFR-4). 구현: {@link OpenAiUtteranceSegmenter}
 * (전용 경량 키 {@code mocha.agent.segmenter-model} — plan §5).
 * <p>POLICY: 세그먼터는 분리만 한다 — 필드 추출·요약·번역 금지(원문 보존 분할). 콜 실패·스키마 위반은
 * 예외로 알리고, 호출부가 자동 분해 없이 "날짜별 분리 안내"로 폴백한다 — 기록이 막히지 않고, 뭉뚱그림
 * 제안은 게이트(V-16)가 막는다 (ref: plan.md#ADR-61 POLICY, §7).
 */
public interface UtteranceSegmenter {

    /**
     * 원문을 시음 날짜별 세그먼트로 분리한다 — 탐지기가 다중 날짜를 보고한 턴에만 호출된다.
     *
     * @param utterance 턴의 사용자 원문.
     * @param dates     원문에서 탐지된 절대 날짜 집합({@code TastingDateDetector} 산출).
     * @return 날짜 오름차순 세그먼트 목록 — 가장 이른 날짜 소비(ADR-61)를 바로 지원한다.
     * @throws RuntimeException 콜 실패·스키마 위반 시 — 호출부는 분리 안내로 폴백한다(빈 결과 수렴 아님).
     */
    List<Segment> segment(String utterance, Collection<LocalDate> dates);

    /** 날짜별 세그먼트 — text는 원문 중 그 날짜에 귀속되는 부분의 무편집 발췌다(data-model §4.3). */
    record Segment(LocalDate date, String text) {
    }
}
