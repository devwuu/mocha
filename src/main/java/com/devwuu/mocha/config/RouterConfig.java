package com.devwuu.mocha.config;

import com.devwuu.mocha.agent.conversation.ConversationTranscript;
import com.devwuu.mocha.agent.prompt.AgentContextAssembler;
import com.devwuu.mocha.agent.tool.AgentToolkit;
import com.devwuu.mocha.agent.tool.validation.ProposalValidator;
import com.devwuu.mocha.llm.AliasGenerator;
import com.devwuu.mocha.llm.PhotoInfoExtractor;
import com.devwuu.mocha.render.NoteRenderer;
import com.devwuu.mocha.repository.NoteRepository;
import com.devwuu.mocha.repository.PendingStore;
import com.devwuu.mocha.repository.PhotoBufferStore;
import com.devwuu.mocha.repository.PhotoStore;
import com.devwuu.mocha.slack.SlackCommitHandler;
import com.devwuu.mocha.slack.inbound.PhotoDownloader;
import com.devwuu.mocha.slack.inbound.SlackPhotoIntake;
import com.devwuu.mocha.slack.outbound.PreviewMessenger;
import com.devwuu.mocha.slack.outbound.SlackResponder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
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

    // 제안 검증기(V-1~16) — 시간 규칙(V-3 미래 날짜 거부)만 주입받는 순수 협력자.
    @Bean
    public ProposalValidator proposalValidator(Clock clock) {
        return new ProposalValidator(clock);
    }

    // 턴 컨텍스트 조립기(ADR-44·TΔ7a) — 트랜스크립트·pending·OCR·세그먼트를 모델 입력으로 직렬화.
    @Bean
    public AgentContextAssembler agentContextAssembler(ObjectMapper mapper, Clock clock) {
        return new AgentContextAssembler(mapper, clock);
    }

    // function tool 5종 façade(ADR-44·45) — 도메인 협력자를 받아 역할별 구현(조회·제안 축)을 내부 조립한다.
    @Bean
    public AgentToolkit agentToolkit(
            NoteRepository noteRepository,
            NoteRenderer noteRenderer,
            SlackResponder responder,
            @Value("${mocha.artifact.dir:./artifact}") String artifactDir,
            ObjectMapper mapper,
            PendingStore pendingStore,
            PreviewMessenger previewMessenger,
            ProposalValidator proposalValidator,
            ConversationTranscript transcript,
            Clock clock) {
        return new AgentToolkit(noteRepository, noteRenderer, responder, Path.of(artifactDir),
                mapper, pendingStore, previewMessenger, proposalValidator, transcript, clock);
    }

    // 사진 수신 배관(FR-10·ADR-29·31) — 라우터(버퍼 그룹핑·OCR)와 커밋 핸들러(스테이징 이관·정리)가 공유한다.
    @Bean
    public SlackPhotoIntake slackPhotoIntake(
            PendingStore pendingStore,
            SlackResponder responder,
            PhotoDownloader photoDownloader,
            PhotoStore photoStore,
            PhotoBufferStore photoBufferStore,
            PhotoInfoExtractor photoInfoExtractor,
            @Value("${mocha.photo.buffer-window:10m}") Duration bufferWindow,
            Clock clock) {
        return new SlackPhotoIntake(pendingStore, responder, photoDownloader, photoStore, photoBufferStore,
                photoInfoExtractor, bufferWindow, clock);
    }

    // [저장]/[취소] 버튼 커밋 핸들러(ADR-3·20·45) — 커밋·렌더·배달·버튼 소진 체인의 독립된 홈.
    @Bean
    public SlackCommitHandler slackCommitHandler(
            PendingStore pendingStore,
            NoteRepository noteRepository,
            NoteRenderer noteRenderer,
            SlackResponder responder,
            AliasGenerator aliasGenerator,
            SlackPhotoIntake photoIntake) {
        return new SlackCommitHandler(pendingStore, noteRepository, noteRenderer, responder,
                aliasGenerator, photoIntake);
    }
}
