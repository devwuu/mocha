package com.devwuu.mocha.agent;

import com.devwuu.mocha.domain.MatchInfo;
import com.devwuu.mocha.domain.NoteMeta;
import com.devwuu.mocha.domain.Rating;
import com.devwuu.mocha.domain.Recipe;

import java.time.LocalDate;

/**
 * {@code propose_record} 서버 검증(V-1·5·8·11, 단일 대기)을 통과한 신규 기록 제안 — 도메인 타입으로
 * 정규화된 형태. pending(mode=record) draft 조립·미리보기 전송(TΔ6)의 입력이 된다
 * (ref: specs/coffee-note-agent/data-model.md#3.3, plan#ADR-45).
 *
 * @param meta            노트 단위 메타(출처 표시 필드 + sources).
 * @param targetDate      시음일 — 엔트리 date(V-3).
 * @param myTaste         내 느낌(정규화본).
 * @param myTasteOriginal 말한 그대로의 감상 — {@code myTaste}가 있으면 항상 병존(V-11).
 * @param rating          4범주 평가 또는 null(V-1).
 * @param recipe          V-8 정규화된 레시피 — 3항목 전무면 null.
 * @param match           신규/기존 판정(미리보기 표기·커밋 대상 근거, AC-15).
 */
public record RecordProposal(
        NoteMeta meta,
        LocalDate targetDate,
        String myTaste,
        String myTasteOriginal,
        Rating rating,
        Recipe recipe,
        MatchInfo match
) {
}
