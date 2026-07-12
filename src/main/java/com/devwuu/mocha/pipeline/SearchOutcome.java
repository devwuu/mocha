package com.devwuu.mocha.pipeline;

import com.devwuu.mocha.domain.SearchSession;

import java.util.List;

/**
 * 검색 세션 1턴의 판정 결과 — {@link NoteSearchService#handle}가 돌려주고, 배선 단
 * (ConfirmationFlow)이 세션 저장/폐기와 응답 전송으로 옮긴다 (ref: spec FR-20, plan.md §3·#ADR-25).
 *
 * @param type    응답 분기(단일 매치·복수 후보·무후보 재질문·재질문 상한 도달).
 * @param session 이 턴을 반영한 세션 상태 — 호출부가 저장한다. {@link Type#LIMIT_REACHED}면 null(세션 폐기 신호).
 * @param hits    표시 재료 — SINGLE_MATCH면 1건, MULTIPLE_CANDIDATES면 관련도순 N건, 그 외 빈 목록.
 */
public record SearchOutcome(Type type, SearchSession session, List<SearchHit> hits) {

    /** 응답 분기 — spec FR-20의 3결과 + 재질문 상한 도달(세션 종료). */
    public enum Type {
        /** 단일 매치 → 기존 카드 JPG 재전송(AC-31). */
        SINGLE_MATCH,
        /** 복수 후보 → 텍스트 목록 제시, 텍스트로 선택(AC-32). */
        MULTIPLE_CANDIDATES,
        /** 무후보 → 구체 단서 재질문(AC-33). */
        NO_MATCH,
        /** 무후보인데 재질문 상한({@code mocha.search-session.max-requery}) 도달 → 안내 + 세션 종료(AC-33). */
        LIMIT_REACHED
    }
}
