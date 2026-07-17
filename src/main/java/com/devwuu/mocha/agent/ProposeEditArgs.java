package com.devwuu.mocha.agent;

import com.devwuu.mocha.domain.Recipe;

import java.util.List;

/**
 * {@code propose_edit} tool 인자 — 저장 노트 수정 제안(쓰기 경로 ②)의 strict schema 계약을 그대로 담는
 * 미검증 값객체 (ref: specs/coffee-note-agent/data-model.md#3.4, plan#ADR-45).
 * <p>대상 리졸브(slug → Note)는 tool 구현의 몫이고, 값 수준 규칙(V-1·5·8·10·11, 단일 대기)은
 * {@link ProposalValidator}가 검증한다.
 *
 * @param slug  수정 대상 노트 — 미존재는 오류 반환(환각 필터).
 * @param date  수정 대상 엔트리의 날짜(YYYY-MM-DD).
 * @param patch 바꿀 필드만 담는 부분 패치 — null 필드는 유지.
 */
public record ProposeEditArgs(String slug, String date, Patch patch) {

    /**
     * 수정 패치 — 전 필드 선택(null = 유지).
     * <p>POLICY: 수정에서 coffee_name은 바뀌지 않는다 — 이 스키마에 필드 자체가 없다(구조 차단) + 거부 안내
     * (ref: specs/coffee-note-agent/data-model.md#V-9, plan#ADR-45, spec AC-38).
     *
     * @param roastery        로스터리 새 값(출처 표시) — null이면 유지. 이하 동일.
     * @param origin          원산지.
     * @param process         가공 방식.
     * @param roastLevel      로스팅 정도.
     * @param officialNotes   공식 테이스팅 노트.
     * @param myTaste         내 느낌(정규화본) — 갱신 시 {@code myTasteOriginal}과 병존(V-11).
     * @param myTasteOriginal 말한 그대로의 감상 표현.
     * @param rating          4범주 enum 라벨 — 위반은 검증 거부(V-1).
     * @param recipe          추출 레시피 — V-8 정규화는 검증 단계에서.
     * @param newDate         날짜 이동(YYYY-MM-DD) — 충돌은 서버 계산(V-10).
     */
    public record Patch(
            SourcedArg<String> roastery,
            SourcedArg<String> origin,
            SourcedArg<String> process,
            SourcedArg<String> roastLevel,
            SourcedArg<List<String>> officialNotes,
            String myTaste,
            String myTasteOriginal,
            String rating,
            Recipe recipe,
            String newDate
    ) {

        /** 아무것도 바꾸지 않는 패치 — 순수 전환 문장("그거 수정할래")의 표준형. */
        public static Patch empty() {
            return new Patch(null, null, null, null, null, null, null, null, null, null);
        }
    }
}
