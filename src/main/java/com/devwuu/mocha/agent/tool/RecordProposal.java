package com.devwuu.mocha.agent.tool;

import com.devwuu.mocha.domain.Brew;
import com.devwuu.mocha.domain.MatchInfo;
import com.devwuu.mocha.domain.NoteMeta;

import java.time.LocalDate;
import java.util.List;

/**
 * {@code propose_record} 서버 검증(V-1·5·8·11·14·15, 단일 대기)을 통과한 신규 기록 제안 — 도메인 타입으로
 * 정규화된 형태. pending(mode=record) draft 조립·미리보기 전송의 입력이 된다
 * (ref: specs/coffee-note-agent/data-model.md#3.3, plan#ADR-45·59).
 *
 * @param meta       노트 단위 메타(출처 표시 필드 + sources).
 * @param targetDate 시음일 — 엔트리 date(V-3).
 * @param brews      회차 배열 — V-15 정규화(빈 회차 드롭) 후 최소 1개 보장(0개는 검증 거부).
 *                   구 엔트리 레벨 my_taste/rating/recipe 필드 대체(changes/0021 ADR-59).
 * @param match      신규/기존 판정(미리보기 표기·커밋 대상 근거, AC-15).
 */
public record RecordProposal(
        NoteMeta meta,
        LocalDate targetDate,
        List<Brew> brews,
        MatchInfo match
) {
}
