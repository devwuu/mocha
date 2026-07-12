package com.devwuu.mocha.pipeline;

import com.devwuu.mocha.domain.SearchSession;

import java.time.LocalDate;
import java.util.List;

/**
 * 검색 세션 1턴의 판정 결과 — {@link NoteSearchService#handle}가 돌려주고, 배선 단
 * (ConfirmationFlow)이 세션 저장/폐기와 응답 전송으로 옮긴다 (ref: spec FR-20, plan.md §3·#ADR-25).
 *
 * @param type            응답 분기(단일 매치·복수 후보·무후보 재질문·재질문 상한 도달·수정 전환 2단).
 * @param session         이 턴을 반영한 세션 상태 — 호출부가 저장한다. {@link Type#LIMIT_REACHED}면 null(세션 폐기 신호).
 * @param hits            표시 재료 — SINGLE_MATCH면 1건, MULTIPLE_CANDIDATES면 관련도순 N건,
 *                        수정 전환 분기(EDIT_*)면 대상 노트 1건, 그 외 빈 목록.
 * @param editDate        {@link Type#EDIT_TARGET_CONFIRMED} 한정 — 확정된 수정 대상 엔트리 date(FR-21, changes/0012).
 * @param editDateChoices {@link Type#EDIT_DATE_CHOICES} 한정 — 제시할 엔트리 날짜 목록(오름차순).
 *                        "두 번째" 선택 해석과 같은 순서로 표시해야 한다(AC-42).
 */
public record SearchOutcome(
        Type type, SearchSession session, List<SearchHit> hits, LocalDate editDate, List<LocalDate> editDateChoices) {

    /** 수정 전환 재료가 없는 순수 검색 분기 — 0012 이전 호출부 형태 유지. */
    public SearchOutcome(Type type, SearchSession session, List<SearchHit> hits) {
        this(type, session, hits, null, List.of());
    }

    /** 응답 분기 — spec FR-20의 3결과 + 재질문 상한 도달(세션 종료) + 수정 전환 2단(FR-21, changes/0012). */
    public enum Type {
        /** 단일 매치 → 기존 카드 JPG 재전송(AC-31). */
        SINGLE_MATCH,
        /** 복수 후보 → 텍스트 목록 제시, 텍스트로 선택(AC-32). */
        MULTIPLE_CANDIDATES,
        /** 무후보 → 구체 단서 재질문(AC-33). */
        NO_MATCH,
        /** 무후보인데 재질문 상한({@code mocha.search-session.max-requery}) 도달 → 안내 + 세션 종료(AC-33). */
        LIMIT_REACHED,
        /** 수정 의도 + 대상 노트 확정인데 엔트리 복수 → 날짜 목록 제시, 텍스트로 선택(FR-21/AC-42). */
        EDIT_DATE_CHOICES,
        /** 수정 대상 노트+엔트리 확정 → 배선이 수정 세션(mode=edit pending)으로 전환한다(FR-21/AC-37). */
        EDIT_TARGET_CONFIRMED
    }
}
