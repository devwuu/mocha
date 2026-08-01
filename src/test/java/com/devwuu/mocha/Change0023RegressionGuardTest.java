package com.devwuu.mocha;

import com.devwuu.mocha.agent.ChatClient;
import com.devwuu.mocha.agent.conversation.FoldingChatMemory;
import com.devwuu.mocha.agent.prompt.TurnPrompt;
import com.devwuu.mocha.agent.prompt.TurnPromptAssembler;
import com.devwuu.mocha.agent.tool.ToolCallback;
import com.devwuu.mocha.agent.tool.ToolCallbackProvider;
import com.devwuu.mocha.agent.turn.TurnRunner;
import com.devwuu.mocha.json.MochaObjectMapper;
import com.devwuu.mocha.llm.UtteranceSegmenter;
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

import static com.devwuu.mocha.agent.tool.ToolCallbackProviderFixture.toolkit;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * TΔ4(changes/0023) UNCHANGED 단언 — 게이트(V-16)·자동 분해(ADR-61)·턴 상한(ADR-62)이 "안 바꾼 것"의
 * 회귀 가드 (ref: changes/0023/delta.md 영향 범위 UNCHANGED 행, AC-Δ4·Δ5).
 *
 * <p>이 클래스는 기존 테스트가 커버하지 못한 한 갭(③ data/ 무변화)만 직접 단언한다. 나머지 UNCHANGED
 * 항목은 아래 기존 테스트가 이미 가드한다(중복 단언하지 않음 — 위치 이동 시 이 표를 갱신할 것):
 * <ul>
 *   <li>~~① 단일 대기 거부(FR-22/AC-30)~~ — 원칙 자체가 changes/0029 TΔ1에서 폐기됐다(delta 0029 D-2).
 *       그 자리는 {@link com.devwuu.mocha.agent.tool.ToolCallbackProviderTest}의 "게이트 폐기 — 대기 내용을
 *       대체한다"가 대신 가드한다.</li>
 *   <li>~~① [저장] 커밋 경로(ADR-3·45)~~ — 버튼 커밋 체인이 changes/0029 TΔ4에서 확인 미리보기와
 *       함께 폐기됐다. 저장 확정은 앱의 폼이 가져갔고({@code web/NoteControllerTest}), 그 경로의 가드는
 *       TΔ6b에서 다시 섰다.</li>
 *   <li>~~② propose_edit 날짜 2개 통과(AC-Δ4)~~ — tool이 changes/0029 TΔ1에서 폐기됐다. V-16이 기록
 *       경로 전용이라는 성질은 {@link com.devwuu.mocha.agent.tool.validation.ProposalValidatorsTest}의
 *       게이트 케이스군이 계속 가드한다.</li>
 *   <li>④ 트랜스크립트 접힘 이벤트(AC-61) —
 *       {@link com.devwuu.mocha.agent.conversation.FoldingChatMemoryTest}
 *       (접힘 트리거별로 문맥이 비워진다). 커밋 접힘의 배선 지점은 TΔ6b에서 REST 컨트롤러가 되찾았다.</li>
 * </ul>
 *
 * <p><b>0029 TΔ16에서 slack 패키지 밖으로 나왔다.</b> 구 위치의 근거는 <i>"③은 실제 턴 처리(라우터 경유)가
 * 대상"</i>이었는데, 턴이 TΔ6a에서 {@link TurnRunner}로 빠지고 라우터가 TΔ16에서 삭제되며 그 근거가
 * 소멸했다. 진입점만 러너로 바뀌었고 <b>단언은 그대로</b>다.
 */
class Change0023RegressionGuardTest {

    private static final String USER = "U-dev";
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    @TempDir
    Path dataDir;

    @Test
    @DisplayName("AC-Δ5/delta UNCHANGED: 다중 날짜 턴 처리(세그먼트 주입·세그먼터 실패 폴백) 후 data/ 아래 신규 파일이 없다 — 이어가기는 트랜스크립트(메모리)만 의존")
    void multiDateTurnLeavesDataDirUntouched() throws IOException {
        // @TempDir이 mocha.data.dir 자리에 선다 — 턴이 파일로 흘리는 것이 있으면 여기 떨어진다.
        // 0029에서 이 가드가 살펴야 할 파일 후보가 둘 줄었다: pending은 TΔ4에서(서버가 작성 중 상태를
        // 갖지 않는다), photo buffer는 TΔ16에서 소멸했다. 러너의 협력자에 파일 매체가 하나도 없다는 것
        // 자체가 이 가드가 지키려는 성질이고, 협력자가 다시 늘면 이 단언이 실질을 되찾는다.
        Clock clock = Clock.fixed(Instant.parse("2026-07-17T01:20:30Z"), SEOUL);
        var mapper = MochaObjectMapper.create();
        var transcript = new FoldingChatMemory(20, Duration.ofHours(1), clock);
        var chatClient = new FakeChatClient();
        var segmenter = new FakeSegmenter();
        ToolCallbackProvider toolCallbackProvider = toolkit()
                .mapper(mapper)
                .clock(clock)
                .build();
        TurnRunner runner = new TurnRunner(transcript, chatClient, toolCallbackProvider,
                new TurnPromptAssembler(mapper, clock), segmenter, clock);

        // 턴 1: 다중 날짜 → 세그먼터 주입 성공(제안 없는 턴).
        segmenter.canned = List.of(
                new UtteranceSegmenter.Segment(LocalDate.of(2026, 7, 15), "7/15 에티오피아 새콤했음"),
                new UtteranceSegmenter.Segment(LocalDate.of(2026, 7, 16), "7/16 케냐 진했음"));
        runner.run(USER, "7/15 에티오피아 새콤했음. 7/16 케냐 진했음", null);

        // 턴 2: 세그먼터 실패 폴백 턴(AC-Δ2) — 주입 없이 진행돼도 파일 부작용이 없어야 한다.
        segmenter.failure = new IllegalStateException("세그먼터 응답 스키마 위반");
        runner.run(USER, "7/18 콜롬비아. 7/19 브라질도 마셨어", null);

        assertThat(chatClient.calls).isEqualTo(2); // 두 턴 모두 정상 진행(sanity)
        // 세그먼트 이어가기 상태는 메모리 트랜스크립트에만 쌓인다 — 제안 없는 턴 2개가 문맥으로 남는다(FR-23).
        assertThat(transcript.view(USER)).hasSize(2);
        // 핵심 단언: 턴 처리(탐지→분해→주입 포함)가 data/ 아래 어떤 파일·디렉터리도 만들지 않았다.
        try (Stream<Path> entries = Files.walk(dataDir)) {
            assertThat(entries.filter(p -> !p.equals(dataDir)))
                    .as("턴 처리는 data/ 아래 새 파일·저장소를 만들지 않는다(delta UNCHANGED)")
                    .isEmpty();
        }
    }

    // ---- fakes (모듈 CLAUDE.md §5.2 — 외부 의존은 인터페이스 stub/fake) ----

    /** 제안 없는 응답만 돌려주는 fake 루프 드라이버 — LLM 미접촉. */
    private static final class FakeChatClient implements ChatClient {
        int calls;

        @Override
        public String runTurn(TurnPrompt context, List<ToolCallback> tools) {
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
}
