package com.devwuu.mocha.slack;

import com.devwuu.mocha.agent.AgentClient;
import com.devwuu.mocha.agent.conversation.ConversationTranscript;
import com.devwuu.mocha.agent.prompt.AgentContextAssembler;
import com.devwuu.mocha.agent.prompt.AgentTurnInput;
import com.devwuu.mocha.agent.tool.AgentTool;
import com.devwuu.mocha.agent.tool.AgentToolkit;
import com.devwuu.mocha.agent.tool.validation.EditProposalValidator;
import com.devwuu.mocha.agent.tool.validation.RecordProposalValidator;
import com.devwuu.mocha.domain.PendingNote;
import com.devwuu.mocha.json.MochaObjectMapper;
import com.devwuu.mocha.llm.PhotoInfoExtractor;
import com.devwuu.mocha.llm.UtteranceSegmenter;
import com.devwuu.mocha.llm.VisionExtraction;
import com.devwuu.mocha.llm.VisionHint;
import com.devwuu.mocha.repository.JsonFilePendingStore;
import com.devwuu.mocha.repository.JsonFilePhotoBufferStore;
import com.devwuu.mocha.repository.LocalPhotoStore;
import com.devwuu.mocha.repository.StagedImage;
import com.devwuu.mocha.slack.inbound.IncomingMessage;
import com.devwuu.mocha.slack.inbound.SlackPhotoIntake;
import com.devwuu.mocha.slack.outbound.SlackResponder;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collection;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TΔ4(changes/0023) UNCHANGED 단언 — 게이트(V-16)·자동 분해(ADR-61)·턴 상한(ADR-62)이 "안 바꾼 것"의
 * 회귀 가드 (ref: changes/0023/delta.md 영향 범위 UNCHANGED 행, AC-Δ4·Δ5).
 *
 * <p>이 클래스는 기존 테스트가 커버하지 못한 한 갭(③ data/ 무변화)만 직접 단언한다. 나머지 UNCHANGED
 * 항목은 아래 기존 테스트가 이미 가드한다(중복 단언하지 않음 — 위치 이동 시 이 표를 갱신할 것):
 * <ul>
 *   <li>① 단일 대기 거부(FR-22/AC-30) — {@link com.devwuu.mocha.agent.tool.validation.ProposalValidatorsTest}
 *       (record·edit 양 경로의 "먼저 저장/취소" 거부), {@link com.devwuu.mocha.agent.tool.AgentToolkitTest}
 *       (tool 반환의 "단일 대기" 오류 + 대기·미리보기 무변화)</li>
 *   <li>① [저장] 커밋 경로(ADR-3·45) — {@link SlackCommitHandlerTest}
 *       ("[저장] 커밋 → pending clear → 카드 배달 → 버튼 소진 순서가 종전과 동일" 회귀 가드 포함)</li>
 *   <li>② propose_edit 날짜 2개 통과(AC-Δ4, V-16 record 전용) —
 *       {@link com.devwuu.mocha.agent.tool.validation.ProposalValidatorsTest}
 *       ("AC-Δ4: 날짜 2개(대상 date + new_date 이동)의 propose_edit는 게이트에 걸리지 않고 통과한다")</li>
 *   <li>④ 트랜스크립트 접힘 이벤트(AC-61) —
 *       {@link com.devwuu.mocha.agent.conversation.ConversationTranscriptTest}
 *       ("접힘 이벤트(제안 성공·[저장]·[취소]) 각각에서 문맥이 비워진다"),
 *       {@link AgentConversationRouterTest}(커밋 접힘·제안 성공 접힘 유지)</li>
 * </ul>
 *
 * <p>slack 패키지에 두는 이유: ③은 실제 턴 처리(라우터 경유)가 대상이라
 * {@link AgentConversationRouter}의 테스트용 package-private 생성자가 필요하다.
 */
class Change0023RegressionGuardTest {

    private static final String USER = "U-dev";
    private static final String CHANNEL = "C-mocha";
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @TempDir
    Path dataDir;

    @Test
    @DisplayName("AC-Δ5/delta UNCHANGED: 다중 날짜 턴 처리(세그먼트 주입·세그먼터 실패 폴백) 후 data/ 아래 신규 파일이 없다 — 이어가기는 트랜스크립트(메모리)만 의존")
    void multiDateTurnLeavesDataDirUntouched() throws IOException {
        // 실제 파일 store 3종을 @TempDir data dir에 배선 — fake가 아니라 실 파일 I/O 경계로 단언한다
        // (delta UNCHANGED "저장/메모리 상태 구조: 새 파일·저장소 없음", NFR-2 예외 목록 불변).
        Clock clock = Clock.fixed(Instant.parse("2026-07-17T01:20:30Z"), SEOUL);
        var mapper = MochaObjectMapper.create();
        var pendingStore = new JsonFilePendingStore(dataDir, mapper, Duration.ofHours(24), clock);
        var photoBufferStore = new JsonFilePhotoBufferStore(dataDir, mapper);
        var photoStore = new LocalPhotoStore(dataDir);
        var transcript = new ConversationTranscript(20, Duration.ofHours(1), clock);
        var responder = new RecordingResponder();
        var agentClient = new FakeAgentClient();
        var segmenter = new FakeSegmenter();
        SlackPhotoIntake photoIntake = new SlackPhotoIntake(pendingStore, responder,
                url -> new byte[0], photoStore, photoBufferStore, new StubPhotoInfoExtractor(),
                Duration.ofMinutes(3), clock);
        AgentToolkit agentTools = new AgentToolkit(null, null, responder, Path.of("unused-artifact"),
                mapper, pendingStore, null, new RecordProposalValidator(clock),
                new EditProposalValidator(), transcript, clock);
        // 버튼 미수신 경로만 돌리므로 커밋 핸들러는 접촉되지 않는다 — 접촉되면 null 협력자로 즉시 실패한다.
        AgentConversationRouter router = new AgentConversationRouter(pendingStore, transcript, agentClient,
                agentTools, new AgentContextAssembler(mapper, clock), segmenter, photoIntake,
                responder, new SlackCommitHandler(null, null, null, null, null, null), clock);

        // 턴 1: 다중 날짜 → 세그먼터 주입 성공(제안 없는 턴).
        segmenter.canned = List.of(
                new UtteranceSegmenter.Segment(LocalDate.of(2026, 7, 15), "7/15 에티오피아 새콤했음"),
                new UtteranceSegmenter.Segment(LocalDate.of(2026, 7, 16), "7/16 케냐 진했음"));
        router.onMessage(new IncomingMessage(USER, CHANNEL,
                "7/15 에티오피아 새콤했음. 7/16 케냐 진했음", "1720000000.000123"));

        // 턴 2: 세그먼터 실패 폴백 턴(AC-Δ2) — 주입 없이 진행돼도 파일 부작용이 없어야 한다.
        segmenter.failure = new IllegalStateException("세그먼터 응답 스키마 위반");
        router.onMessage(new IncomingMessage(USER, CHANNEL,
                "7/18 콜롬비아. 7/19 브라질도 마셨어", "1720000000.000456"));

        assertThat(agentClient.calls).isEqualTo(2); // 두 턴 모두 정상 진행(sanity)
        // 세그먼트 이어가기 상태는 메모리 트랜스크립트에만 쌓인다 — 제안 없는 턴 2개가 문맥으로 남는다(FR-23).
        assertThat(transcript.view(USER)).hasSize(2);
        // 핵심 단언: 턴 처리(탐지→분해→주입 포함)가 data/ 아래 어떤 파일·디렉터리도 만들지 않았다.
        try (Stream<Path> entries = Files.walk(dataDir)) {
            assertThat(entries.filter(p -> !p.equals(dataDir)))
                    .as("턴 처리는 data/ 아래 새 파일·저장소를 만들지 않는다(delta UNCHANGED)")
                    .isEmpty();
        }
    }

    // ---- fakes (모듈 CLAUDE.md §5.2 — 외부 의존은 인터페이스 stub/fake, store만 실 파일) ----

    /** 제안 없는 응답만 돌려주는 fake 루프 드라이버 — LLM 미접촉. */
    private static final class FakeAgentClient implements AgentClient {
        int calls;

        @Override
        public String runTurn(AgentTurnInput context, List<AgentTool> tools) {
            calls++;
            return "네 멍!";
        }
    }

    /** 분리 결과·실패를 지정하는 fake 세그먼터 — LLM 미접촉. */
    private static final class FakeSegmenter implements UtteranceSegmenter {
        List<Segment> canned = List.of();
        RuntimeException failure;

        @Override
        public List<Segment> segment(String utterance, Collection<LocalDate> dates) {
            if (failure != null) {
                throw failure;
            }
            return canned;
        }
    }

    /** 평문 전송만 허용하는 responder — Slack 미접촉. */
    private static final class RecordingResponder implements SlackResponder {
        @Override
        public void post(String channelId, String text) {
            // 응답 내용은 이 가드의 비대상 — 라우터 배선 자체는 AgentConversationRouterTest가 본다.
        }

        @Override
        public void postImage(String channelId, Path imagePath, String caption) {
            throw new UnsupportedOperationException("이 가드는 이미지 배달을 다루지 않는다");
        }

        @Override
        public void finalizePreview(String channelId, PendingNote pending, String statusText) {
            throw new UnsupportedOperationException("버튼 소진은 이 가드의 비대상");
        }
    }

    /** 빈 OCR 결과 stub — vision 미접촉(사진 없는 턴만 돌린다). */
    private static final class StubPhotoInfoExtractor extends PhotoInfoExtractor {
        StubPhotoInfoExtractor() {
            super(null, 4);
        }

        @Override
        public VisionExtraction extract(List<StagedImage> images, VisionHint hint) {
            return VisionExtraction.empty();
        }
    }
}
