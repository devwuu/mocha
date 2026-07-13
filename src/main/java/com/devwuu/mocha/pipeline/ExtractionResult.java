package com.devwuu.mocha.pipeline;

import com.devwuu.mocha.domain.Rating;
import com.devwuu.mocha.domain.Recipe;

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
 * @param myTaste     내가 느낀 맛. 표현·뉘앙스 보존 + 한국어 음슴체 정규화(영어는 번역), 미언급 null
 *                    (ADR-30, changes/0013).
 * @param myTasteOriginal 말한 그대로의 감상 표현(언어 불문, 요약·정규화 없음). {@code myTaste}와 병존.
 *                    LLM이 누락하면 정규화본으로 수렴(V-11, changes/0013).
 * @param rating      4범주 평가 또는 null(미언급). 4범주 외 값은 역직렬화에서 거부(V-1).
 * @param recipe      발화 속 추출 레시피(dose_g·water_ml·grind), 미언급 null. <b>사용자 발화 전용</b> —
 *                    검색·OCR 보강 대상 아님(ADR-22, FR-18). 여기 값은 LLM 원본이며 V-8 정규화는
 *                    Entry 조립 시 {@link Recipe#normalize}로 적용한다(음수·0·공백 항목 드롭).
 * @param matchedSlug existing_notes 중 매칭된 slug, 없으면 null(서버가 재검증 — T2-3).
 * @param referencesPast "저번에/그때 그" 등 기존 기록을 가리키는 참조 표현 여부. 기본 false.
 *                       매칭 실패 시 FR-14 과거 참조 분기의 신호가 된다(changes/0011).
 * @param targetDate  "어제 마신" 같은 상대 날짜 해석 결과(YYYY-MM-DD). 미해석 시 today로 기본화.
 */
public record ExtractionResult(
        String coffeeName,
        String roastery,
        String origin,
        String process,
        String roastLevel,
        String myTaste,
        String myTasteOriginal,
        Rating rating,
        Recipe recipe,
        String matchedSlug,
        Boolean referencesPast,
        LocalDate targetDate
) {

    public ExtractionResult {
        // 방어적 기본화: 스키마가 required boolean을 강제하지만 LLM/역직렬화의 null·부재를
        // "참조 아님"으로 수렴시킨다 (data-model.md#4 "기본 false", targetDate 기본화와 같은 정신).
        referencesPast = referencesPast != null && referencesPast;
        // POLICY: my_taste가 있으면 my_taste_original도 병존 — LLM이 원문을 누락하면 정규화본으로 수렴한다
        //         (ref: data-model.md#V-11, plan#ADR-30, changes/0013).
        if (myTaste != null && myTasteOriginal == null) {
            myTasteOriginal = myTaste;
        }
    }

    /** 원문 필드 도입 전(changes/0013 이전) 시그니처 호환 생성자 — 원문은 정규화본으로 수렴(V-11). */
    public ExtractionResult(String coffeeName, String roastery, String origin, String process, String roastLevel,
                            String myTaste, Rating rating, Recipe recipe, String matchedSlug,
                            Boolean referencesPast, LocalDate targetDate) {
        this(coffeeName, roastery, origin, process, roastLevel, myTaste, null, rating, recipe, matchedSlug,
                referencesPast, targetDate);
    }

    /**
     * target_date가 비었으면 today로 채운 사본을 돌려준다 (data-model.md#4 "기본 today").
     * structured output이 필드를 강제하더라도 LLM이 null을 반환할 수 있어 방어적으로 기본화한다.
     */
    public ExtractionResult withTargetDateDefault(LocalDate today) {
        if (targetDate != null) {
            return this;
        }
        return new ExtractionResult(
                coffeeName, roastery, origin, process, roastLevel, myTaste, myTasteOriginal, rating, recipe,
                matchedSlug, referencesPast, today);
    }
}
