package com.devwuu.mocha.pipeline;

import com.devwuu.mocha.domain.Rating;

import java.time.LocalDate;

/**
 * LLM 구조화 추출 응답 (ref: data-model.md#4). structured output 스키마가 강제하는 계약을
 * 그대로 담는 값객체 — 아직 출처 마킹·검색 보강 전의 "사용자가 말한 것"만 들어 있다.
 * <p>POLICY: 언급되지 않은 필드는 null이며 LLM이 추측으로 채우지 않는다 (ref: spec FR-2, plan#ADR-6).
 * 출처(source) 판정·slug 생성·검색 보강은 서버 이후 단계(NoteMatcher/NoteEnricher)의 몫이다
 * (data-model.md#4 주석).
 *
 * @param coffeeName  커피 이름(미언급 null).
 * @param roastery    로스터리(미언급 null).
 * @param origin      원산지 — 사용자 언급분만. 검색 보강은 서버 단계(미언급 null).
 * @param process     가공 방식 — 동일(미언급 null).
 * @param roastLevel  로스팅 정도 — 동일(미언급 null).
 * @param myTaste     내가 느낀 맛. 감상 원문 보존 위주 요약(미언급 null).
 * @param rating      4범주 평가 또는 null(미언급). 4범주 외 값은 역직렬화에서 거부(V-1).
 * @param matchedSlug existing_notes 중 매칭된 slug, 없으면 null(서버가 재검증 — T2-3).
 * @param targetDate  "어제 마신" 같은 상대 날짜 해석 결과(YYYY-MM-DD). 미해석 시 today로 기본화.
 */
public record ExtractionResult(
        String coffeeName,
        String roastery,
        String origin,
        String process,
        String roastLevel,
        String myTaste,
        Rating rating,
        String matchedSlug,
        LocalDate targetDate
) {

    /**
     * target_date가 비었으면 today로 채운 사본을 돌려준다 (data-model.md#4 "기본 today").
     * structured output이 필드를 강제하더라도 LLM이 null을 반환할 수 있어 방어적으로 기본화한다.
     */
    public ExtractionResult withTargetDateDefault(LocalDate today) {
        if (targetDate != null) {
            return this;
        }
        return new ExtractionResult(
                coffeeName, roastery, origin, process, roastLevel, myTaste, rating, matchedSlug, today);
    }
}
