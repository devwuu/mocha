package com.devwuu.mocha.agent.tool;

import java.util.List;

/**
 * {@code propose_edit} tool 인자 — 저장 노트 수정 제안(쓰기 경로 ②)의 strict schema 계약을 그대로 담는
 * 미검증 값객체 (ref: specs/coffee-note-agent/data-model.md#3.4, plan#ADR-45·53·59).
 * <p>대상 리졸브(slug → Note)는 tool 구현의 몫이고, 값 수준 규칙(V-1·5·8·11·14·15, 단일 대기)은
 * {@code EditProposalValidator}가 검증한다 — 이동 충돌(V-10) 계산은 제안 수용 지점(tool 구현)의 몫이다.
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
     * @param roastery      로스터리 새 값(출처 표시) — null이면 유지. 이하 동일.
     * @param beans         원두 구성 새 값 — 배열 통째 교체(구 origin/process 대체, changes/0021 ADR-53, V-14).
     * @param roastLevel    로스팅 정도.
     * @param officialNotes 공식 테이스팅 노트.
     * @param brews         회차 배열 새 값 — <b>통째 교체</b>. 특정 회차만 고치는 발화도 에이전트가 대상 회차를
     *                      반영한 전체 배열을 구성한다(구 my_taste/rating/recipe 개별 필드 대체,
     *                      changes/0021 ADR-59, V-15).
     * @param newDate       날짜 이동(YYYY-MM-DD) — 충돌은 서버 계산(V-10).
     */
    public record Patch(
            SourcedArg<String> roastery,
            List<BeanArg> beans,
            SourcedArg<String> roastLevel,
            SourcedArg<List<String>> officialNotes,
            List<BrewArg> brews,
            String newDate
    ) {

        /** 아무것도 바꾸지 않는 패치 — 순수 전환 문장("그거 수정할래")의 표준형. */
        public static Patch empty() {
            return new Patch(null, null, null, null, null, null);
        }
    }
}
