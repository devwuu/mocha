package com.devwuu.mocha.agent.tool;

import java.time.LocalDate;
import java.util.List;

/**
 * {@code list_notes} tool 응답의 노트당 1항목 — 전체 노트 메타(읽기)
 * (ref: specs/coffee-note-agent/data-model.md#3.1).
 * <p>구 매칭·검색 후보 페이로드의 승계 — {@code aliases} 포함으로 표기 비일관(한/영 교차·부분 표기)을
 * 흡수한다(plan ADR-37, AC-53). 에이전트의 동일성 판단(FR-14)·검색(FR-20) 공용 재료.
 *
 * @param slug          노트 식별자.
 * @param coffeeName    커피 이름 표시값.
 * @param roastery      로스터리 표시값 — 없으면 null.
 * @param aliases       내부 별칭(커피명·로스터리 통합) — 사용자 미표시 값이지만 매칭 재료로 페이로드에 싣는다.
 * @param origin        원산지 — 없으면 null.
 * @param officialNotes 공식 테이스팅 노트 — 없으면 빈 목록.
 * @param lastTasted    최근 시음일 — 상대 날짜 단서("엊그제 마신 거") 해석 재료.
 */
public record NoteSummary(
        String slug,
        String coffeeName,
        String roastery,
        List<String> aliases,
        String origin,
        List<String> officialNotes,
        LocalDate lastTasted
) {
}
