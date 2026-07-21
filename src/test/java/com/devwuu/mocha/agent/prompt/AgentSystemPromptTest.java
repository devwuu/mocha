package com.devwuu.mocha.agent.prompt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시스템 프롬프트의 정책 문구 포함 단언 — 프롬프트가 단일 소유하는 결정(페르소나·대화 경계·보강 정책·
 * 출처 우선순위·my_taste 정규화·대기 중 수정 우선·즉시 propose)이 빠지지 않았는지 가드한다
 * (ref: changes/0018 tasks.md TΔ7a; plan.md#ADR-47/#ADR-49/#ADR-30, data-model.md#V-6, spec FR-5/FR-21).
 * <p>언어 정책(ADR-38)의 vision 프롬프트와의 동일 문구 비교는 llm 패키지의 LanguagePolicyParityTest가 맡는다.
 */
class AgentSystemPromptTest {

    private static final String PROMPT = AgentSystemPrompt.INSTRUCTIONS;

    @Test
    @DisplayName("ADR-47/AC-20: 모카 페르소나와 대화 경계 — 잡담 tool 금지·무관 주제 선긋기·피벗 전환")
    void encodesPersonaAndConversationBoundary() {
        assertThat(PROMPT).contains("모카");
        assertThat(PROMPT).contains("~멍");
        assertThat(PROMPT).contains("커피 관련 잡담은 모카 톤으로 받아주되 tool을 호출하지 않는다");
        assertThat(PROMPT).contains("잡담 턴은 확인 대기(pending)·노트를 만들지 않는다");
        assertThat(PROMPT).contains("커피와 무관한 주제는 짧게 선을 긋는다");
        assertThat(PROMPT).contains("즉시 tool 흐름으로 전환한다");
    }

    @Test
    @DisplayName("ADR-45/ADR-3: 커밋 경계 — 제안은 pending까지, 저장은 [저장] 버튼만")
    void encodesCommitBoundary() {
        assertThat(PROMPT).contains("제안의 효과는 확인 대기(pending)+미리보기까지다");
        assertThat(PROMPT).contains("저장(커밋)은 사용자의 [저장] 버튼만 한다");
        assertThat(PROMPT).contains("저장이 완료됐다고 스스로 선언하지 마라");
    }

    @Test
    @DisplayName("FR-21/AC-Δ1: 명확한 대상은 되묻지 않고 즉시 propose — 미리보기+버튼이 곧 확인")
    void encodesImmediateProposeForClearTargets() {
        assertThat(PROMPT).contains("추가 확인 질문 없이 즉시 제안(propose)한다");
        assertThat(PROMPT).contains("미리보기+[저장] 버튼이 곧 확인이다");
    }

    @Test
    @DisplayName("FR-5: 확인 대기 중 발화는 수정 의도 우선 + 검색 격리 + 커피 이름 변경 거부(V-9)")
    void encodesPendingRevisionPriority() {
        assertThat(PROMPT).contains("수정 의도를 우선 고려한다");
        assertThat(PROMPT).contains("대기 중 검색·카드 요청은 대기 내용을 바꾸지 않는다");
        assertThat(PROMPT).contains("커피 이름 변경 요청은 오타 정정을 포함해 예외 없이 거부");
    }

    @Test
    @DisplayName("V-6/FR-12: source 자기 보고와 우선순위 user > photo > search, 레시피는 발화 전용")
    void encodesSourcePriority() {
        assertThat(PROMPT).contains("user > photo > search");
        assertThat(PROMPT).contains("사진 값은 user 값을, 검색 값은 user·photo 값을 덮지 않는다");
        assertThat(PROMPT).contains("레시피(dose_g·water_ml·grind)는 사용자 발화 전용이다");
    }

    @Test
    @DisplayName("ADR-30/V-11: my_taste 음슴체 정규화 + 표현 보존 + my_taste_original 병존")
    void encodesMyTasteNormalization() {
        assertThat(PROMPT).contains("문장 끝 어미만 한국어 음슴체로 바꿔 담는다");
        assertThat(PROMPT).contains("맛 묘사·수식어·뉘앙스를 하나도 빼지 말고 그대로 보존한다");
        assertThat(PROMPT).contains("my_taste_original에 항상 함께 담는다");
    }

    @Test
    @DisplayName("ADR-49·53/AC-16·58·64: 보강 정책 — 공식 우선·블렌드 원두별 beans·official_notes 한정·동일성 가드·추측 금지")
    void encodesEnrichmentPolicy() {
        assertThat(PROMPT).contains("로스터리 공식 페이지를 우선");
        assertThat(PROMPT).contains("대상 원두의 것인지 스스로 확인한 뒤 아니면 재검색한다");
        // ADR-53: 블렌드는 원두별 beans 요소 — 구 "origin 쉼표 나열"은 폐기됐다.
        assertThat(PROMPT).contains("블렌드는 구성 원두마다 요소를 만들어");
        assertThat(PROMPT).contains("원산지를 한 문자열에 쉼표로 나열하지 않는다");
        assertThat(PROMPT).contains("품종은 필수 보강 대상이 아니다");
        assertThat(PROMPT).contains("official_notes는 로스터리 출처 한정이다");
        assertThat(PROMPT).contains("로스터리와 원두명이 함께 확인된 출처만 쓴다");
        assertThat(PROMPT).contains("추측 금지");
        assertThat(PROMPT).contains("사용자 입력만으로 제안을 진행한다");
    }

    @Test
    @DisplayName("FR-20/FR-14: 검색·매칭 tool 흐름 — list_notes 출발·카드가 답·today 기준 상대 날짜")
    void encodesToolWorkflow() {
        assertThat(PROMPT).contains("list_notes");
        assertThat(PROMPT).contains("get_note");
        assertThat(PROMPT).contains("send_entry_card");
        assertThat(PROMPT).contains("today 기준으로 절대 날짜");
    }
}
