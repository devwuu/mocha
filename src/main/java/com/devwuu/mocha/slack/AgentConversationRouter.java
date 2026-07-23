package com.devwuu.mocha.slack;

import com.devwuu.mocha.agent.AgentClient;
import com.devwuu.mocha.agent.conversation.ConversationTranscript;
import com.devwuu.mocha.agent.conversation.TranscriptTurn;
import com.devwuu.mocha.agent.prompt.AgentContextAssembler;
import com.devwuu.mocha.agent.prompt.AgentTurnInput;
import com.devwuu.mocha.agent.tool.AgentToolkit;
import com.devwuu.mocha.agent.tool.ProposalValidator;
import com.devwuu.mocha.agent.tool.TastingDateDetector;
import com.devwuu.mocha.agent.tool.TurnUtterance;
import com.devwuu.mocha.domain.PendingNote;
import com.devwuu.mocha.llm.AliasGenerator;
import com.devwuu.mocha.llm.PhotoInfoExtractor;
import com.devwuu.mocha.llm.UtteranceSegmenter;
import com.devwuu.mocha.llm.VisionExtraction;
import com.devwuu.mocha.llm.VisionHint;
import com.devwuu.mocha.render.NoteRenderer;
import com.devwuu.mocha.repository.NoteRepository;
import com.devwuu.mocha.repository.PendingStore;
import com.devwuu.mocha.repository.PhotoBufferStore;
import com.devwuu.mocha.repository.PhotoStore;
import com.devwuu.mocha.slack.inbound.IncomingAction;
import com.devwuu.mocha.slack.inbound.IncomingMedia;
import com.devwuu.mocha.slack.inbound.IncomingMessage;
import com.devwuu.mocha.slack.inbound.PhotoDownloader;
import com.devwuu.mocha.slack.inbound.SlackPhotoIntake;
import com.devwuu.mocha.slack.outbound.MochaMessages;
import com.devwuu.mocha.slack.outbound.PreviewBlocks;
import com.devwuu.mocha.slack.outbound.PreviewMessenger;
import com.devwuu.mocha.slack.outbound.SlackResponder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.NavigableSet;
import java.util.Objects;

/**
 * 에이전트 턴 라우터 — 버튼 액션 외 모든 수신을 에이전트 루프로 처리하는 {@link ConversationRouter} 구현
 * (ref: specs/coffee-note-agent/plan.md §1 [1]·[3], §3 route; ADR-44·47·48; changes/0018 TΔ7b).
 * <ul>
 *   <li>텍스트(사진 캡션 포함) → OCR 전처리(FR-19, 루프 전 결정론 1콜) → 에이전트 턴(TΔ2 루프 + TΔ7a
 *       프롬프트·컨텍스트) → 최종 텍스트가 Slack 응답(ADR-44). 의도 게이트는 없다 — 에이전트 자체가
 *       대화 경계다(ADR-47, FR-24).</li>
 *   <li>버튼 → {@code action_id} 결정론 분기(ADR-3 불변) — 에이전트 미경유(AC-Δ7). 커밋·렌더·배달·버튼
 *       소진 체인은 {@link SlackCommitHandler}를 직접 부른다(TΔ8a 이관 — flow 3종 미경유, AC-Δ3).</li>
 *   <li>사진 → 버퍼·스테이징 배관(FR-10, {@link SlackPhotoIntake}) 직접 위임 — 캡션 텍스트는
 *       {@link SlackGateway}가 별도 {@link IncomingMessage}로 이어 보내 에이전트 턴이 된다.</li>
 * </ul>
 * <p>POLICY: 에이전트 턴 실패(모델 오류·tool 상한·검증 반복)는 pending·노트 무변화 + 재요청 안내 +
 * 원문 로그 보존 — 조용한 유실 금지 (ref: specs/coffee-note-agent/plan.md#ADR-48, spec AC-63).
 */
@Component
@Primary
public class AgentConversationRouter implements ConversationRouter {

    /** 미리보기 [저장] 버튼 action_id — {@link PreviewBlocks}와의 결정론 계약(ADR-3·ADR-20). */
    public static final String ACTION_SAVE = "mocha_save";
    /** 미리보기 [취소] 버튼 action_id. */
    public static final String ACTION_CANCEL = "mocha_cancel";

    private static final Logger log = LoggerFactory.getLogger(AgentConversationRouter.class);

    private final PendingStore pendingStore;
    private final ConversationTranscript transcript;
    private final AgentClient agentClient;
    private final AgentToolkit agentTools;
    private final AgentContextAssembler contextAssembler;
    private final UtteranceSegmenter segmenter;
    private final SlackPhotoIntake photoIntake;
    private final SlackResponder responder;
    private final SlackCommitHandler commitHandler;
    // 시계(Asia/Seoul — V-3, pending·트랜스크립트와 동일)는 config 공통 빈 주입(ADR-63).
    private final Clock clock;

    @Autowired
    public AgentConversationRouter(
            PendingStore pendingStore,
            ConversationTranscript transcript,
            AgentClient agentClient,
            UtteranceSegmenter segmenter,
            NoteRepository noteRepository,
            NoteRenderer noteRenderer,
            SlackResponder responder,
            PreviewMessenger previewMessenger,
            PhotoDownloader photoDownloader,
            PhotoStore photoStore,
            PhotoBufferStore photoBufferStore,
            PhotoInfoExtractor photoInfoExtractor,
            AliasGenerator aliasGenerator,
            // 시계·JSON 매퍼는 config 공통 빈 주입(ADR-63) — 자체 생성 금지.
            Clock clock,
            ObjectMapper mapper,
            @Value("${mocha.artifact.dir}") String artifactDir,
            @Value("${mocha.photo.buffer-window}") Duration bufferWindow) {
        // tool façade·컨텍스트 조립기·커밋 핸들러는 프레임워크 무관 내부 협력자라 여기서 조립한다(Spring 빈이 아니다).
        this(pendingStore, transcript, agentClient,
                new AgentToolkit(noteRepository, noteRenderer, responder, Path.of(artifactDir),
                        mapper, pendingStore, previewMessenger, new ProposalValidator(clock),
                        transcript, clock),
                new AgentContextAssembler(mapper, clock),
                segmenter,
                new SlackPhotoIntake(pendingStore, responder, photoDownloader, photoStore, photoBufferStore,
                        photoInfoExtractor, bufferWindow, clock),
                responder, noteRepository, noteRenderer, aliasGenerator, clock);
    }

    // 커밋 핸들러가 사진 배관(photoIntake)을 라우터와 공유하도록 잇는 중간 생성자 — this(...) 단일 식
    // 제약 때문에 photoIntake를 매개변수로 받아 두 곳에 배선한다.
    private AgentConversationRouter(
            PendingStore pendingStore,
            ConversationTranscript transcript,
            AgentClient agentClient,
            AgentToolkit agentTools,
            AgentContextAssembler contextAssembler,
            UtteranceSegmenter segmenter,
            SlackPhotoIntake photoIntake,
            SlackResponder responder,
            NoteRepository noteRepository,
            NoteRenderer noteRenderer,
            AliasGenerator aliasGenerator,
            Clock clock) {
        this(pendingStore, transcript, agentClient, agentTools, contextAssembler, segmenter, photoIntake,
                responder,
                new SlackCommitHandler(pendingStore, noteRepository, noteRenderer, responder,
                        aliasGenerator, photoIntake),
                clock);
    }

    // 테스트에서 협력자·시간을 주입하기 위한 생성자.
    AgentConversationRouter(
            PendingStore pendingStore,
            ConversationTranscript transcript,
            AgentClient agentClient,
            AgentToolkit agentTools,
            AgentContextAssembler contextAssembler,
            UtteranceSegmenter segmenter,
            SlackPhotoIntake photoIntake,
            SlackResponder responder,
            SlackCommitHandler commitHandler,
            Clock clock) {
        this.pendingStore = pendingStore;
        this.transcript = transcript;
        this.agentClient = agentClient;
        this.agentTools = agentTools;
        this.contextAssembler = contextAssembler;
        this.segmenter = segmenter;
        this.photoIntake = photoIntake;
        this.responder = responder;
        this.commitHandler = commitHandler;
        this.clock = clock;
    }

    @Override
    public void onMessage(IncomingMessage message) {
        String userId = message.userId();
        String channelId = message.channelId();
        try {
            OffsetDateTime now = OffsetDateTime.now(clock);

            // FR-10 버퍼 그룹핑: 텍스트보다 먼저 도착해 버퍼링된 사진이 윈도우 안이면 이 턴에 묶는다.
            // 소비(clearBuffer)는 제안이 pending으로 이관된 뒤에만 — 실패 시 사진이 버퍼에 남아 재시도로 살아남는다.
            List<String> bufferNames = photoIntake.absorbFreshBuffer(userId, now).orElse(List.of());

            // OCR은 tool이 아니라 루프 전 결정론 전처리다(ADR-44, 사용자 확정) — 사진 있으면 항상 1콜,
            // 실패·무정보는 빈 결과로 컨텍스트에서 빠진다(FR-19, AC-28 — 조립기가 거른다).
            VisionExtraction ocr = photoIntake.readPhotoInfo(userId, bufferNames, new VisionHint(null, null));

            // 다중 날짜 자동 분해(ADR-61) — OCR과 동렬의 루프 전 전처리. 탐지기가 절대 날짜 2개 이상을
            // 찾은 턴에만 세그먼터 1콜, 그 외·실패 턴은 null(주입 없음 — 게이트 V-16이 뭉뚱그림을 방어).
            List<TurnUtterance.Segment> segments = segmentIfMultiDate(userId, message.text());

            PendingNote pendingBefore = pendingStore.get(userId).orElse(null);
            AgentTurnInput context = contextAssembler.assemble(
                    message.text(), transcript.view(userId), pendingBefore, ocr, segments);

            log.info("에이전트 턴 진입: user={} buffered={} pending={} segments={}",
                    userId, bufferNames.size(), pendingBefore != null,
                    segments == null ? "-" : segments.size());
            // TΔ2b 배선: 턴 원문·세그먼트를 제안 검증기까지 나른다(다중 날짜 게이트 V-16의 판정 입력, ADR-60).
            // 라우터가 1회 만들어 조립기와 같은 값을 넘긴다 — 턴 안에서 값이 일관된다(findings-TΔ0 §C-5).
            TurnUtterance utterance = new TurnUtterance(message.text(), segments);
            String reply = agentClient.runTurn(context, agentTools.forTurn(userId, channelId, utterance));

            // 모델의 최종 텍스트가 곧 Slack 응답이다(ADR-44) — 미리보기·카드는 tool 구현체가 이미 보냈다.
            responder.post(channelId, reply);
            finishTurn(userId, message.text(), reply, pendingBefore, bufferNames);
        } catch (Exception e) {
            // POLICY: 에이전트 턴 실패는 pending·노트 무변화 + 재요청 안내 + 원문 로그 보존 — 조용한 유실 금지
            //         (ref: specs/coffee-note-agent/plan.md#ADR-48, spec FR-25/AC-63). 이미 성공한 제안 tool의
            //         pending·미리보기는 유효하게 남는다(부분 성공 존중). 폴백 사유는 예외 메시지로 구분 관측(plan §6).
            log.warn("에이전트 턴 폴백(pending·노트 무변화, 원문 보존): user={} 원문={}", userId, message.text(), e);
            responder.post(channelId, MochaMessages.AGENT_TURN_FAILED);
        }
    }

    // 다중 날짜 턴의 세그먼트 분리(ADR-61) — 단일 날짜(탐지 2개 미만) 턴은 세그먼터를 부르지 않는다(무개입).
    private List<TurnUtterance.Segment> segmentIfMultiDate(String userId, String text) {
        NavigableSet<LocalDate> dates = TastingDateDetector.detect(text, LocalDate.now(clock));
        if (dates.size() < 2) {
            return null;
        }
        try {
            return segmenter.segment(text, dates).stream()
                    .map(s -> new TurnUtterance.Segment(s.date(), s.text()))
                    .toList();
        } catch (RuntimeException e) {
            // POLICY: 세그먼터 실패는 자동 분해 없이 진행 — 기록이 막히지 않고(턴 폴백 아님), 뭉뚱그림 제안은
            //         게이트(V-16)가 거부해 구 UX(분리 안내)로 수렴한다 (ref: plan.md#ADR-61 POLICY, AC-Δ2).
            log.warn("세그먼터 실패 — 주입 없이 진행(분리 안내 폴백, 게이트 방어): user={} dates={}", userId, dates, e);
            return null;
        }
    }

    // 턴 완결 후 상태 정리 — 제안 성공 여부는 pending 변화로 판정한다(쓰기 경로는 제안 tool뿐이라 결정론, AC-59).
    private void finishTurn(String userId, String userText, String reply,
                            PendingNote pendingBefore, List<String> bufferNames) {
        PendingNote pendingAfter = pendingStore.get(userId).orElse(null);
        boolean proposalAccepted = !Objects.equals(pendingBefore, pendingAfter);
        if (proposalAccepted) {
            // 사진은 pending으로 이관됐다 — 버퍼만 비운다(스테이징 원본은 [저장] 시 commit이 옮긴다, FR-10).
            if (!bufferNames.isEmpty()) {
                photoIntake.clearBuffer(userId);
            }
            // POLICY: 제안 성공 턴은 트랜스크립트에 재축적하지 않는다 — 접힘(제안 tool이 수행) 후 문맥은
            //         구조화된 pending draft가 대신한다 (ref: specs/coffee-note-agent/plan.md#ADR-46, spec FR-23).
            return;
        }
        transcript.append(userId, new TranscriptTurn(userText, reply));
    }

    @Override
    public void onAction(IncomingAction action) {
        // POLICY: 저장/취소 커밋은 Block Kit 버튼(action_id) 결정론 분기만 — 자연어·에이전트 미경유
        //         (ref: specs/coffee-note-agent/plan.md#ADR-3 불변, #ADR-45, delta AC-Δ7).
        // 커밋·렌더·배달·버튼 소진 체인은 독립 핸들러가 소유한다(TΔ8a 이관 — flow 3종 미경유, AC-Δ3).
        String actionId = action.actionId();
        if (ACTION_SAVE.equals(actionId)) {
            commitHandler.confirmSave(action);
            // 커밋 접힘(ADR-46 규칙 ②) — 확정된 작업의 문맥은 버린다. 배선 지점: 버튼 액션 핸들러(FoldTrigger 계약).
            transcript.clear(action.userId(), ConversationTranscript.FoldTrigger.SAVE_COMMIT);
        } else if (ACTION_CANCEL.equals(actionId)) {
            commitHandler.cancel(action);
            transcript.clear(action.userId(), ConversationTranscript.FoldTrigger.CANCEL_COMMIT);
        } else {
            // 계약에 없는 action_id — 조용히 무시하되 원인 추적용으로 남긴다(구 라우터 규칙 승계).
            log.warn("알 수 없는 액션 무시: actionId={} user={}", actionId, action.userId());
        }
    }

    @Override
    public void onMedia(IncomingMedia media) {
        // 사진 버퍼·스테이징·포맷 검증 배관은 불변(ADR-29, delta UNCHANGED) — flow 미경유로 직접 위임한다(TΔ8a).
        // 캡션 텍스트는 게이트웨이가 별도 IncomingMessage로 이어 보내 에이전트 턴(onMessage)이 된다.
        photoIntake.receive(media);
    }
}
