package com.devwuu.mocha.agent.prompt;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 시스템 프롬프트의 정책 문구 포함 단언 — 프롬프트가 단일 소유하는 결정(페르소나·말투·대화 경계·보강 정책·
 * 출처 우선순위·my_taste 정규화·대기 중 수정 우선·즉시 propose·회차 분리/병합·수치 정규화·다중 날짜
 * 순차 제안)이 빠지지 않았는지 가드한다 (ref: changes/0018 tasks.md TΔ7a, changes/0021 TΔ3b,
 * changes/0023 TΔ3d; plan.md#ADR-47/#ADR-49/#ADR-30/#ADR-59/#ADR-61, data-model.md#V-6,
 * spec FR-5/FR-15/FR-18/FR-21/FR-22).
 * <p>언어 정책(ADR-38)의 vision 프롬프트와의 동일 문구 비교는 llm 패키지의 LanguagePolicyParityTest가 맡는다.
 */
class AgentSystemPromptTest {

    private static final String PROMPT = AgentSystemPrompt.INSTRUCTIONS;

    @Test
    @DisplayName("ADR-47/AC-20: 모카 페르소나와 대화 경계 — 잡담 tool 금지·무관 주제 선긋기·피벗 전환")
    void encodesPersonaAndConversationBoundary() {
        assertThat(PROMPT).contains("모카");
        assertThat(PROMPT).contains("커피 관련 잡담은 모카 톤으로 받아주되 tool을 호출하지 않는다");
        assertThat(PROMPT).contains("잡담 턴은 제안·노트를 만들지 않는다");
        assertThat(PROMPT).contains("커피와 무관한 주제는 짧게 선을 긋는다");
        assertThat(PROMPT).contains("즉시 tool 흐름으로 전환한다");
    }

    @Test
    @DisplayName("D-15/AC-11 (TΔ27): 말투 = 반말 고정 + 어미 말버릇 없음 — 구 \"~멍\" 강제는 걷혔다")
    void encodesConsistentSpeechRegisterWithoutVerbalTic() {
        // 어미 강제를 걷는 것만으로는 «통일»이 아니다 — 축을 비워 두면 흔들림이 어미에서 종결어미로
        // 옮겨갈 뿐이라, 반말 고정이 이 개정의 나머지 절반이다(사용자 확정 2026-08-02).
        assertThat(PROMPT).contains("한국어 반말로 답한다");
        assertThat(PROMPT).contains("존댓말과 섞지 말고 어느 턴에서나 반말로 일관한다");
        // 톤(발랄함·다정함·짧음)은 남는다 — 걷은 것은 어미 규칙뿐이다(D-15).
        assertThat(PROMPT).contains("강아지처럼 발랄하고 다정한 톤이다");
        assertThat(PROMPT).contains("말끝에 붙이는 말버릇 어미는 쓰지 않는다");
        assertThat(PROMPT).contains("한두 문장이 기본이고");
        // 부재 단언이 이 개정의 본체다 — 문구를 되살리면 여기가 잡는다. 금지를 «"~멍"을 쓰지 마라»로
        // 적지 않은 것도 의도다: 부정문에 예시를 실으면 그 어미가 프롬프트에 남아 오히려 도드라진다.
        assertThat(PROMPT).doesNotContain("~멍").doesNotContain("멍");
    }

    @Test
    @DisplayName("D-15/AC-11 (TΔ27): 응답은 서식 없는 평문 — 말풍선이 마크다운을 렌더하지 않는다")
    void forbidsMarkdownEmphasis() {
        assertThat(PROMPT).contains("서식 기호를 쓰지 않는다");
        // 근거를 함께 싣는다(ADR-60 사유 정보량 원칙의 프롬프트 측 적용) — «금지»만 적으면 모델이
        // 서식이 유용해 보이는 자리에서 되살린다.
        assertThat(PROMPT).contains("채팅 말풍선이 렌더하지 않아 기호가 글자 그대로 보인다");
        assertThat(PROMPT).contains("강조는 서식이 아니라 낱말 선택으로 한다");
    }

    @Test
    @DisplayName("TΔ24c 이월 (b) 정리: 매체 표기가 Slack이 아니라 앱 채팅이다")
    void describesAppChatAsTheMedium() {
        // 0029에서 입구가 Slack → 앱으로 바뀌었는데(ADR-78) 첫 줄만 잔재로 남아 있었다. 모델이 보는
        // 유일한 매체 서술이라, 틀린 채로 두면 «채널·스레드» 류의 존재하지 않는 맥락을 가정한다.
        assertThat(PROMPT).contains("앱 채팅에서 사용자의 커피 시음 기록을 돕는다");
        assertThat(PROMPT).doesNotContain("Slack");
    }

    @Test
    @DisplayName("ADR-45/ADR-3: 커밋 경계 — 제안은 폼을 채우는 데까지, 저장은 사용자 확정만(0029 TΔ4)")
    void encodesCommitBoundary() {
        assertThat(PROMPT).contains("제안의 효과는 사용자의 작성 폼을 채우는 데까지다");
        assertThat(PROMPT).contains("저장(커밋)은 사용자의 [저장] 확정만 한다");
        assertThat(PROMPT).contains("저장이 완료됐다고 스스로 선언하지 마라");
    }

    @Test
    @DisplayName("FR-21/AC-Δ1: 명확한 대상은 되묻지 않고 즉시 propose — 폼+[저장]이 곧 확인")
    void encodesImmediateProposeForClearTargets() {
        assertThat(PROMPT).contains("추가 확인 질문 없이 즉시 제안(propose)한다");
        assertThat(PROMPT).contains("작성 폼+[저장]이 곧 확인이다");
    }

    @Test
    @DisplayName("TΔ2: draft 중 발화는 수정 의도 우선 + 조회 격리 + 커피 이름 변경 거부(V-9)")
    void encodesDraftRevisionPriority() {
        assertThat(PROMPT).contains("수정 의도를 우선 고려한다");
        // 0029 TΔ1: 카드 전송 tool이 폐기돼 "검색·카드"가 "조회"로 줄었다.
        // 0029 TΔ2: 대상이 서버 pending → 클라이언트 draft로 바뀌었다 — 격리 규칙 자체는 유지.
        assertThat(PROMPT).contains("draft 상태에서의 조회 요청은 draft를 바꾸지 않는다");
        assertThat(PROMPT).contains("커피 이름 변경 요청은 오타 정정을 포함해 예외 없이 거부");
    }

    @Test
    @DisplayName("TΔ2/AC-1: 추가 발화는 draft 위에 반영 + draft 값은 하위 출처로 덮지 않는다(V-6)")
    void encodesDraftOverlayAndPriority() {
        assertThat(PROMPT).contains("draft를 새로 만들지 말고 **그 위에 반영**한다");
        // 유실 방지의 핵심 — 이번 발화가 건드리지 않은 필드도 그대로 다시 실어야 한다.
        assertThat(PROMPT).contains("이번 발화가 건드리지 않은 필드는 draft 값을 그대로 다시 실어 보낸다");
        assertThat(PROMPT).contains("draft에 이미 값이 있는 필드는 더 낮은 출처로 덮지 않는다");
    }

    @Test
    @DisplayName("V-6/FR-12: source 자기 보고와 우선순위 user > photo > search, 레시피는 발화 전용")
    void encodesSourcePriority() {
        assertThat(PROMPT).contains("user > photo > search");
        assertThat(PROMPT).contains("사진 값은 user 값을, 검색 값은 user·photo 값을 덮지 않는다");
        // changes/0021 ADR-59: 레시피가 회차 10필드로 확장 — 발화 전용 규칙은 전 필드로 승계.
        assertThat(PROMPT).contains("회차의 레시피(recipe 전 필드)는 사용자 발화 전용이다");
    }

    @Test
    @DisplayName("ADR-59/AC-74·75: 회차 규칙 — 시도 분리·감상↔피드백 분리·마시는 방식은 같은 회차 감상")
    void encodesBrewSplitRules() {
        assertThat(PROMPT).contains("한 번 내려서 마신 단위가 회차 1개다");
        assertThat(PROMPT).contains("시도마다 회차 요소를 만든다");
        assertThat(PROMPT).contains("커피 맛의 감상·평가는 그 회차의 tasting에");
        assertThat(PROMPT).contains("그 회차의 recipe.feedback에 담는다");
        assertThat(PROMPT).contains("feedback이 아니라 그 회차의 tasting이다");
        assertThat(PROMPT).contains("마시는 방식 차이는 레시피가 아니다");
        assertThat(PROMPT).contains("같은 회차의 감상 텍스트 하나에 담는다");
    }

    @Test
    @DisplayName("ADR-59/AC-79: 회차 병합 — append 기본, 명시 지칭 시만 그 회차 tasting에 병합·rating은 명시 시만 갱신")
    void encodesBrewMergeRules() {
        assertThat(PROMPT).contains("새 시도·새 감상은 새 회차로 append한다");
        assertThat(PROMPT).contains("그 회차의 tasting에 병합한다");
        assertThat(PROMPT).contains("my_taste_original은 원문을 이어붙여 보존");
        assertThat(PROMPT).contains("rating은 새 평가가 명시될 때만 갱신한다");
        assertThat(PROMPT).contains("대상 회차를 반영한 전체 배열로 구성해 보낸다");
    }

    @Test
    @DisplayName("FR-18/AC-66·76: 수치 정규화 — grind 형식·대략 표기는 숫자만·총 시간 초 환산")
    void encodesRecipeNumericNormalization() {
        assertThat(PROMPT).contains("대략 표기는 숫자만 취한다");
        assertThat(PROMPT).contains("\"2분 40초\" → 160");
        assertThat(PROMPT).contains("\"210클릭 (매버릭 2.0)\"");
        assertThat(PROMPT).contains("그라인더 언급이 없으면 분쇄값만 담는다");
    }

    @Test
    @DisplayName("FR-15·22/AC-77·AC-Δ2: 다중 날짜 발화는 순차 제안 — 이른 날짜만 제안 + 나머지 저장 후 이어서 안내, 근거 포함")
    void encodesMultiDateSequentialProposal() {
        // changes/0023 ADR-61: 구 "분리 안내"에서 개정 — 문구는 명령이 아니라 근거(폼은 엔트리 1건 →
        // 가장 이른 날짜부터)를 담는다(ADR-60 사유 정보량 원칙의 프롬프트 측 적용). 0029 TΔ4에서 근거가
        // "pending 단일 대기"에서 "작성 폼 1건"으로 바뀌었다 — 규칙은 같고 소유자가 옮겨졌다.
        assertThat(PROMPT).contains("작성 폼은 한 번에 한 건(엔트리 1건)만 담을 수 있으니");
        assertThat(PROMPT).contains("active_date(가장 이른 날짜) 세그먼트");
        assertThat(PROMPT).contains("나머지 날짜는 저장 후 이어서 제안하겠다는 안내를 담는다");
        assertThat(PROMPT).contains("다른 날짜의 시도를 한 날짜의 회차로 합치지도 마라");
        assertThat(PROMPT).contains("남은 날짜 중 가장 이른 것부터 같은 방식으로 제안한다");
    }

    @Test
    @DisplayName("AC-Δ2/ADR-61: 세그먼트 부재(분해 실패) 턴만 분리 안내 폴백 — 구 문구는 폴백으로 축소")
    void encodesSegmenterFailureFallback() {
        assertThat(PROMPT).contains("세그먼트 컨텍스트가 없으면(분해 실패) 제안 tool을 호출하지 않는다");
        assertThat(PROMPT).contains("한 날짜씩 나눠 보내달라고 안내한다(폴백)");
        // AC-Δ1 재확인(우회 거부)은 프롬프트가 아니라 서버 게이트 몫 — ProposalValidatorsTest.MultiDateGateV16이 가드.
    }

    @Test
    @DisplayName("ADR-30/V-11: my_taste 음슴체 정규화 + 표현 보존 + my_taste_original 병존")
    void encodesMyTasteNormalization() {
        assertThat(PROMPT).contains("문장 끝 어미만 한국어 음슴체로 바꿔 담는다");
        assertThat(PROMPT).contains("맛 묘사·수식어·뉘앙스를 하나도 빼지 말고 그대로 보존한다");
        assertThat(PROMPT).contains("my_taste_original에 항상 함께 담는다");
    }

    @Test
    @DisplayName("ADR-53/AC-64: 블렌드는 원두별 beans 요소 — 구 'origin 쉼표 나열'은 폐기됐다")
    void encodesBeansComposition() {
        assertThat(PROMPT).contains("블렌드는 구성 원두마다 요소를 만들어");
        assertThat(PROMPT).contains("원산지를 한 문자열에 쉼표로 나열하지 않는다");
    }

    @Test
    @DisplayName("D-16 ③ (TΔ24c): 프롬프트가 검색을 지시하지 않는다 — search 출처의 소유자는 서버 보강 단계다")
    void doesNotInstructModelToSearch() {
        // 종전 이 자리는 «보강 정책»(공식 출처 우선·동일성 가드·official_notes 한정)을 단언했다. 보강 주체가
        // 모델 재량 tool에서 결정론 단계로 되돌아가며(D-16 ①·②) 그 정책의 소유자가 검색 지침으로 옮겨졌고,
        // OpenAiSearchClientTest가 그쪽을 가드한다 — 두 벌로 두면 한쪽만 고쳐도 그린이 된다.
        assertThat(PROMPT).doesNotContain("web_search");
        assertThat(PROMPT).contains("너는 웹을 검색하지 않는다");
        assertThat(PROMPT).contains("search는 서버의 보강 단계가 붙이는 출처");
        // 되싣기 규칙 — draft의 search 값을 모델이 떨어뜨리면 보강 결과가 다음 턴에 유실된다(V-6 짝).
        assertThat(PROMPT).contains("draft에 이미 실려 온 search 값은 그 값과 출처를 그대로 다시 실어 보낸다");
        // 추측 금지는 여기 남는다 — 검색이 사라져도 «모델이 지어내지 않는다»는 이 프롬프트의 규칙이다.
        assertThat(PROMPT).contains("추측 금지");
        assertThat(PROMPT).contains("지어내 채우지 마라");
    }

    @Test
    @DisplayName("FR-14: 매칭 tool 흐름 — list_notes 출발·today 기준 상대 날짜")
    void encodesToolWorkflow() {
        assertThat(PROMPT).contains("list_notes");
        assertThat(PROMPT).contains("get_note");
        assertThat(PROMPT).contains("today 기준으로 절대 날짜");
        // 0029 TΔ1: 폐기된 tool을 다시 부르게 두지 않는다 — 수정·카드 전송은 UI 몫이다(D-1·D-5).
        assertThat(PROMPT).doesNotContain("propose_edit").doesNotContain("send_entry_card");
    }
}
