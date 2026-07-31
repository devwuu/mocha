package com.devwuu.mocha.config;

import com.devwuu.mocha.agent.prompt.TurnPromptAssembler;
import com.devwuu.mocha.agent.tool.ToolCallbackProvider;
import com.devwuu.mocha.agent.tool.validation.RecordProposalValidator;
import com.devwuu.mocha.llm.PhotoInfoExtractor;
import com.devwuu.mocha.service.NoteService;
import com.devwuu.mocha.repository.PhotoBufferStore;
import com.devwuu.mocha.repository.PhotoStore;
import com.devwuu.mocha.slack.inbound.PhotoDownloader;
import com.devwuu.mocha.slack.inbound.SlackPhotoIntake;
import com.devwuu.mocha.slack.outbound.SlackResponder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;

/**
 * 에이전트 턴 협력자 빈 배선 — 라우터({@code AgentConversationRouter}) 생성자 안 조립을 config로
 * 이관해, 라우터는 조립된 협력자를 주입만 받는다 (ref: plan.md#ADR-63, changes/0024 TΔ1b1·TΔ1b2).
 * <p>{@code AgentConfig}(루프 드라이버·트랜스크립트 — 설정 키 default 가드 대상)와 분리해, 도메인
 * 협력자 조립이 이 한 클래스에서 읽히게 한다.
 * <p>POLICY: 내부 협력자 조립·전역 인스턴스(Clock·ObjectMapper) 생성은 config/가 소유 —
 * 수신·라우터·tool 계층에서 협력자 new·전역 인스턴스 자체 생성 금지 (ref: plan.md#ADR-63).
 */
@Configuration
public class RouterConfig {

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

    // 사진 수신 배관(FR-10·ADR-29·31) — 라우터(버퍼 그룹핑·OCR)가 쓴다. TΔ4에서 pending 주입이 빠졌다:
    // "진행 중 노트가 있으면 스테이징, 없으면 버퍼" 분기의 판정 입력이 서버에서 사라졌다(아래 intake 주석).
    @Bean
    public SlackPhotoIntake slackPhotoIntake(
            SlackResponder responder,
            PhotoDownloader photoDownloader,
            PhotoStore photoStore,
            PhotoBufferStore photoBufferStore,
            PhotoInfoExtractor photoInfoExtractor,
            @Value("${mocha.photo.buffer-window:10m}") Duration bufferWindow,
            Clock clock) {
        return new SlackPhotoIntake(responder, photoDownloader, photoStore, photoBufferStore,
                photoInfoExtractor, bufferWindow, clock);
    }
}
