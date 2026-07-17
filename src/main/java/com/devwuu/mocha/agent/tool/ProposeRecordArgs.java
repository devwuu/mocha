package com.devwuu.mocha.agent.tool;

import com.devwuu.mocha.domain.Recipe;

import java.util.List;

/**
 * {@code propose_record} tool 인자 — 신규 기록 제안(쓰기 경로 ①)의 strict schema 계약을 그대로 담는
 * 미검증 값객체 (ref: specs/coffee-note-agent/data-model.md#3.3, plan#ADR-45).
 * <p>SDK 무관 도메인 계층 타입이다 — JSON(snake_case, {@code MochaObjectMapper})에서 역직렬화되고,
 * 값 수준 규칙(V-1·5·8·11, 단일 대기)은 {@link ProposalValidator}가 검증한다. rating·날짜·match를
 * 원시 String으로 두는 이유는 위반을 예외가 아니라 <b>사유 있는 tool 오류 결과</b>로 돌려주기 위해서다
 * (에이전트가 루프 안에서 정정 — AC-Δ5).
 *
 * @param coffeeName      커피 이름 — source ∈ {user, photo}만 허용(검색 앵커·정체성, V-5).
 * @param roastery        로스터리 — source ∈ {user, photo, search}.
 * @param origin          원산지 — 블렌드는 여러 원산지를 쉼표로 나열한 한 문자열(ADR-49).
 * @param process         가공 방식.
 * @param roastLevel      로스팅 정도.
 * @param officialNotes   로스터리 전시 테이스팅 노트 — 로스터리 출처 한정(FR-3/FR-7).
 * @param myTaste         내 느낌 — 한국어 음슴체 정규화본(ADR-30).
 * @param myTasteOriginal 말한 그대로의 감상 표현 — {@code myTaste}와 병존(V-11).
 * @param rating          4범주 enum 라벨 또는 null — 위반은 검증 거부(V-1).
 * @param recipe          발화 속 추출 레시피 — 사용자 발화 전용, V-8 정규화는 검증 단계에서.
 * @param targetDate      시음일(YYYY-MM-DD) — 상대 날짜는 에이전트가 컨텍스트의 today 기준으로 해석해 절대화.
 * @param match           신규/기존 판정 — 미리보기 표기(AC-15)·커밋 대상 근거.
 * @param sources         검색 참조 링크 — 동일성 가드 통과 출처만(AC-58, 프롬프트 정책).
 */
public record ProposeRecordArgs(
        SourcedArg<String> coffeeName,
        SourcedArg<String> roastery,
        SourcedArg<String> origin,
        SourcedArg<String> process,
        SourcedArg<String> roastLevel,
        SourcedArg<List<String>> officialNotes,
        String myTaste,
        String myTasteOriginal,
        String rating,
        Recipe recipe,
        String targetDate,
        MatchArg match,
        List<String> sources
) {

    /**
     * 신규/기존 판정 인자 — {@code { "type": "new" }} 또는
     * {@code { "type": "existing", "slug": "...", "date": "YYYY-MM-DD" }}의 미검증 원시 형태.
     * 검증 통과 시 {@link com.devwuu.mocha.domain.MatchInfo}로 변환된다.
     */
    public record MatchArg(String type, String slug, String date) {
    }
}
