package com.devwuu.mocha.config;

import com.devwuu.mocha.agent.ChatClient;
import com.devwuu.mocha.agent.conversation.FoldingChatMemory;
import com.devwuu.mocha.agent.prompt.TurnPromptAssembler;
import com.devwuu.mocha.agent.tool.ToolCallbackProvider;
import com.devwuu.mocha.agent.tool.validation.RecordProposalValidator;
import com.devwuu.mocha.agent.turn.TurnRunner;
import com.devwuu.mocha.llm.UtteranceSegmenter;
import com.devwuu.mocha.service.NoteService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;

/**
 * 에이전트 턴 협력자 빈 배선 — 구 라우터({@code AgentConversationRouter}) 생성자 안 조립을 config로
 * 이관해, 호출부는 조립된 협력자를 주입만 받는다 (ref: plan.md#ADR-63, changes/0024 TΔ1b1·TΔ1b2).
 * <p>{@code AgentConfig}(루프 드라이버·트랜스크립트 — 설정 키 default 가드 대상)와 분리해, 도메인
 * 협력자 조립이 이 한 클래스에서 읽히게 한다.
 * <p><b>이름은 0029 TΔ16에서 {@code RouterConfig}에서 바뀌었다</b> — 라우터가 사라져서가 아니라, 이미
 * TΔ6a부터 라우터 전용이 아니었기 때문이다(그때 {@link TurnRunner}가 REST 컨트롤러와 공용이 됐고, 개명
 * 시점을 여기로 미뤄 뒀다). 이 config가 조립하는 것은 <b>전송 계층이 아니라 턴</b>이다.
 * <p>POLICY: 내부 협력자 조립·전역 인스턴스(Clock·ObjectMapper) 생성은 config/가 소유 —
 * 전송·tool 계층에서 협력자 new·전역 인스턴스 자체 생성 금지 (ref: plan.md#ADR-63).
 */
@Configuration
public class TurnConfig {

    // 제안 검증 진입점 — 0029 TΔ1 이후 record 하나뿐이다(수정은 UI 전용, delta 0029 D-1). V-16 연도 없는
    // 표기 해석 기준 시계만 주입받는 순수 협력자다.
    @Bean
    public RecordProposalValidator recordProposalValidator(Clock clock) {
        return new RecordProposalValidator(clock);
    }

    // 턴 컨텍스트 조립기(ADR-44·TΔ7a) — 트랜스크립트·draft·OCR·세그먼트를 모델 입력으로 직렬화.
    @Bean
    public TurnPromptAssembler turnPromptAssembler(ObjectMapper mapper, Clock clock) {
        return new TurnPromptAssembler(mapper, clock);
    }

    // function tool 3종 façade(ADR-44·45) — 도메인 협력자를 받아 역할별 구현(조회·제안 축)을 내부 조립한다.
    // TΔ4a에서 주입 타입이 저장소 포트에서 NoteService로 바뀌었다 — tool은 유스케이스만 알고 행을 모른다.
    // 0029 TΔ1에서 조회 tool의 렌더·송신 의존(send_entry_card)이 끊겨 산출 디렉터리 주입도 함께 빠졌고,
    // TΔ3에서 제안 성공 접힘 폐기로 트랜스크립트 주입이, TΔ4에서 pending 저장소·미리보기 송신 주입이
    // 빠졌다 — tool 계층은 대화 문맥도, 서버 대기 상태도, 전송 계층도 모른다.
    @Bean
    public ToolCallbackProvider toolCallbackProvider(
            NoteService noteService,
            ObjectMapper mapper,
            RecordProposalValidator recordProposalValidator,
            Clock clock) {
        return new ToolCallbackProvider(noteService, mapper, recordProposalValidator, clock);
    }

    // 에이전트 턴 실행부(ADR-44·61·64) — 0029 TΔ6a에서 구 Slack 라우터 안에서 뽑혀 나왔다. 전송 계층이
    // 둘이 되면서(Slack 라우터·REST 컨트롤러) 턴의 정의를 한 곳에 두어야 했고, TΔ16에서 Slack이 걷힌
    // 뒤로는 REST 컨트롤러가 유일한 호출부다. 뽑아 둔 값은 그대로다 — 턴은 여전히 전송 계층 밖에 있다.
    @Bean
    public TurnRunner turnRunner(
            FoldingChatMemory transcript,
            ChatClient chatClient,
            ToolCallbackProvider toolCallbackProvider,
            TurnPromptAssembler turnPromptAssembler,
            UtteranceSegmenter segmenter,
            Clock clock) {
        return new TurnRunner(transcript, chatClient, toolCallbackProvider, turnPromptAssembler,
                segmenter, clock);
    }

    // 0029 TΔ16: 사진 수신 배관(SlackPhotoIntake) 빈이 사라졌다 — 다운로드·버퍼 그룹핑(FR-10)·HEIC 썸네일
    // 대체가 전부 Slack 파일 메타에 기대던 것이라 REST에 존재할 수 없다. 포맷 게이트(ADR-29)·스테이징·
    // OCR 전처리는 TΔ8a가 업로드 API 자리에서 다시 세운다(delta D-11).
}
