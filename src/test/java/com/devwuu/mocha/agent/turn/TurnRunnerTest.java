package com.devwuu.mocha.agent.turn;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.devwuu.mocha.agent.AgentException;
import com.devwuu.mocha.agent.ChatClient;
import com.devwuu.mocha.agent.conversation.FoldingChatMemory;
import com.devwuu.mocha.agent.conversation.TranscriptTurn;
import com.devwuu.mocha.agent.prompt.TurnPrompt;
import com.devwuu.mocha.agent.prompt.TurnPromptAssembler;
import com.devwuu.mocha.agent.tool.ToolCallback;
import com.devwuu.mocha.agent.tool.ToolCallbackProvider;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.Source;
import com.devwuu.mocha.domain.Sourced;
import com.devwuu.mocha.json.MochaObjectMapper;
import com.devwuu.mocha.llm.SearchClient;
import com.devwuu.mocha.llm.SearchQuery;
import com.devwuu.mocha.llm.SearchResult;
import com.devwuu.mocha.llm.UtteranceSegmenter;
import com.devwuu.mocha.llm.VisionExtraction;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.function.Consumer;

import static com.devwuu.mocha.agent.tool.ToolCallbackProviderFixture.toolkit;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 턴 실행부 배선 — 전처리(다중 날짜 분해) → 컨텍스트 조립 → 루프 → 제안 수거 → 트랜스크립트 축적이
 * 실제로 이어지는가 (ref: plan.md#ADR-44·#ADR-48·#ADR-61, changes/0029 TΔ6a).
 * 외부 의존(모델·세그먼터)은 전부 fake — LLM 판단 자체는 비대상(모듈 CLAUDE.md §5.2·5.3).
 *
 * <p><b>0029 TΔ16에서 이 클래스가 생겼다.</b> 여기 있는 단언들은 종전 {@code AgentConversationRouterTest}가
 * <i>실물 러너를 세워</i> 검증하던 것이고(TΔ6a의 의도적 선택), Slack 계층이 걷히며 그 집이 사라져 러너
 * 레벨로 내려왔다. <b>단언 내용은 옮겨오며 바뀌지 않았다</b> — 바뀐 것은 진입점(라우터 {@code onMessage}
 * → {@link TurnRunner#run})과, 전송 계층 몫이라 따라오지 않은 것들뿐이다:
 * <ul>
 *   <li><b>폴백 안내 문구</b> — 러너는 예외를 그대로 올린다(ADR-48의 <i>무변화</i>만 여기 남고, 안내
 *       형태는 전송 계층이 정한다). 실패 응답 쪽 단언은 {@code web/AgentTurnControllerTest}가 소유한다.</li>
 *   <li><b>사진 버퍼·버튼 액션</b> — 배관 자체가 TΔ4·TΔ16에서 소멸했다. OCR <i>주입</i>은 남는다:
 *       러너는 읽어 둔 결과를 인자로 받으므로 사진을 어디서 받았는지와 무관하다.</li>
 * </ul>
 */
class TurnRunnerTest {

    private static final String USER = "U-dev";
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final Clock clock = Clock.fixed(Instant.parse("2026-07-17T01:20:30Z"), SEOUL); // Seoul 10:20:30
    private final FakeChatClient chatClient = new FakeChatClient();
    private final FoldingChatMemory transcript = new FoldingChatMemory(20, Duration.ofHours(1), clock);
    private final FakeSegmenter segmenter = new FakeSegmenter();
    private final FakeSearchClient searchClient = new FakeSearchClient();
    private TurnRunner runner;
    private ListAppender<ILoggingEvent> logs;

    @BeforeEach
    void setUp() {
        logs = new ListAppender<>();
        logs.start();
        ((Logger) LoggerFactory.getLogger(TurnRunner.class)).addAppender(logs);
        // 매퍼·시계만 실물이다 — 제안 시뮬레이션이 propose_record를 실제로 실행하므로 검증기가 필요하고
        // (시계에서 파생), match=new 경로는 노트 저장소를 접지 않아 null로 남겨 미접촉 신호를 유지한다.
        ToolCallbackProvider toolCallbackProvider = toolkit()
                .mapper(MochaObjectMapper.create())
                .clock(clock)
                .build();
        runner = new TurnRunner(transcript, chatClient, toolCallbackProvider,
                new TurnPromptAssembler(MochaObjectMapper.create(), clock), segmenter,
                // 사진 OCR은 이 테스트의 대상이 아니다 — 사진 있는 턴은 TurnPhotoOcrTest가 소유하고,
                // 여기서는 컨텍스트 주입만 보는 패키지-프라이빗 판본을 부른다(스테이징 파일이 필요 없다).
                null,
                // 검색 보강(TΔ24b)은 실물로 세운다 — 이 클래스가 보는 것은 «제안 수거 후에 실제로 도는가»라는
                // 배선이고, 보강 정책 자체(트리거·V-6·무결과)는 TurnProposalEnricherTest가 소유한다.
                new TurnProposalEnricher(searchClient), clock);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(TurnRunner.class)).detachAppender(logs);
    }

    @Test
    @DisplayName("AC-Δ8(0026): 성공 턴에서도 턴 진입 관측 로그에 발화 원문이 실린다 — 박제 회수 경로(ADR-69 ①)")
    void turnEntryObservationCarriesRawUtterance() {
        chatClient.reply = "맛있었겠다 멍! 🐾";

        TurnResult result = runner.run(USER, "어제 마신 예가체프 새콤했어", null);

        assertThat(result.reply()).isEqualTo("맛있었겠다 멍! 🐾"); // 폴백 아닌 성공 턴
        assertThat(logs.list).extracting(ILoggingEvent::getFormattedMessage)
                .anyMatch(m -> m.startsWith("에이전트 턴 진입") && m.contains("어제 마신 예가체프 새콤했어"));
    }

    @Test
    @DisplayName("FR-22/ADR-44: 턴 1회 = tool 3종 장착, 최종 텍스트 반환, 트랜스크립트 축적")
    void runExecutesAgentTurn() {
        chatClient.reply = "커피 얘기 좋아요 멍! 🐾";

        TurnResult result = runner.run(USER, "요즘 커피 뭐가 맛있어?", null);

        assertThat(chatClient.calls).isEqualTo(1);
        assertThat(result.reply()).isEqualTo("커피 얘기 좋아요 멍! 🐾");
        assertThat(result.proposal()).isNull();
        // 이번 발화가 messages의 마지막 user 메시지로 실린다(TΔ7a 조립 계약).
        List<TurnPrompt.Message> messages = chatClient.lastContext.messages();
        assertThat(messages.get(messages.size() - 1)).isEqualTo(TurnPrompt.Message.user("요즘 커피 뭐가 맛있어?"));
        assertThat(chatClient.lastTools).extracting(ToolCallback::name).containsExactly(
                "list_notes", "get_note", "propose_record");   // 0029 TΔ1에서 5종 → 3종
        // 제안 없는 턴은 트랜스크립트에 쌓인다(FR-23) — 다음 턴의 문맥이 된다.
        assertThat(transcript.view(USER))
                .containsExactly(new TranscriptTurn("요즘 커피 뭐가 맛있어?", "커피 얘기 좋아요 멍! 🐾"));
    }

    @Test
    @DisplayName("AC-63/ADR-48: 턴 실패는 예외로 올라간다 + 트랜스크립트 무변화 — 안내 문구는 전송 계층 몫")
    void agentFailurePropagatesWithoutStateChange() {
        transcript.append(USER, new TranscriptTurn("이 커피 뭐더라", "찾아볼게요 멍"));
        chatClient.failure = new AgentException("tool 호출 상한(8) 도달 — 턴 중단");

        assertThatThrownBy(() -> runner.run(USER, "어제 마신 예가체프 새콤했어", null))
                .isInstanceOf(AgentException.class);

        assertThat(transcript.view(USER)).hasSize(1);         // 실패 턴은 문맥에 쌓지 않는다
    }

    @Test
    @DisplayName("FR-19/ADR-44: 읽어 둔 사진 OCR 결과가 턴 컨텍스트에 실린다 — 사진 출처와 무관한 배선")
    void ocrResultIsInjectedIntoContext() {
        VisionExtraction ocr = new VisionExtraction("Kenya AA", "FroB", List.of(), null, List.of());

        runner.run(USER, "이거 마셨어", null, ocr);

        assertThat(chatClient.lastContext.instructions()).contains("Kenya AA"); // OCR 결과 주입(TΔ7a)
    }

    @Test
    @DisplayName("0029 TΔ3: 제안 성공 턴도 트랜스크립트에 쌓인다(구 규칙 ① 접힘 폐기) + 제안이 결과로 나온다")
    void proposalTurnAccumulatesTranscriptAndReturnsProposal() {
        transcript.append(USER, new TranscriptTurn("이 커피 뭐더라", "찾아볼게요 멍"));
        // 제안 tool을 실제로 실행해 수거함을 채운다 — 러너가 만든 sink는 tool 콜백 안에만 있으므로,
        // "제안이 성공했다"를 밖에서 흉내 낼 자리가 TΔ4 이후 없다(그 자체가 서버 상태 소멸의 결과다).
        chatClient.onRun = tools -> tools.stream()
                .filter(tool -> tool.name().equals("propose_record")).findFirst().orElseThrow()
                .executor().execute(PROPOSE_RECORD_ARGS);

        TurnResult result = runner.run(USER, "어제 마신 걸로 기록해줘", null);

        assertThat(result.proposal()).isNotNull();
        // 제안 전 문맥 + 이번 턴이 함께 남는다 — [저장]까지 대화가 살아 있어야 이어가기(ADR-61)가 성립한다.
        assertThat(transcript.view(USER)).containsExactly(
                new TranscriptTurn("이 커피 뭐더라", "찾아볼게요 멍"),
                new TranscriptTurn("어제 마신 걸로 기록해줘", chatClient.reply));
    }

    @Test
    @DisplayName("AC-9/0029 TΔ24b: 제안 수거 «후» 검색 보강이 돌고, 채워진 값이 응답 draft에 실려 나간다")
    void proposalIsEnrichedAfterCollection() {
        searchClient.canned = new SearchResult(
                null, List.of(new SearchResult.Bean("에티오피아 예가체프", "워시드")), "미디엄",
                List.of("자몽", "홍차"), List.of("https://coffeevera.example/g1"));
        chatClient.onRun = tools -> tools.stream()
                .filter(tool -> tool.name().equals("propose_record")).findFirst().orElseThrow()
                .executor().execute(PROPOSE_RECORD_ARGS);

        TurnResult result = runner.run(USER, "커피베라 예가체프 G1 마셨어", null);

        // 앵커는 제안의 커피명·로스터리다 — 검색은 draft가 선 뒤에야 무엇을 찾을지 안다.
        assertThat(searchClient.calls).isEqualTo(1);
        assertThat(searchClient.lastQuery)
                .isEqualTo(new SearchQuery("커피베라 예가체프 G1", "커피베라"));
        Note enriched = result.proposal().note();
        assertThat(Sourced.valuesOrEmpty(enriched.officialNotes())).containsExactly("자몽", "홍차");
        assertThat(enriched.officialNotes().source()).isEqualTo(Source.SEARCH);
        assertThat(enriched.beans()).hasSize(1);
        assertThat(enriched.beans().get(0).description().value()).isEqualTo("에티오피아 예가체프");
        assertThat(enriched.roastLevel().value()).isEqualTo("미디엄");
        assertThat(enriched.sources()).containsExactly("https://coffeevera.example/g1");
        // 사용자가 말한 값(coffee_name·roastery)은 검색이 덮지 않는다(V-6).
        assertThat(enriched.coffeeName().source()).isEqualTo(Source.USER);
        assertThat(enriched.roastery().value()).isEqualTo("커피베라");
    }

    @Test
    @DisplayName("0029 TΔ24b: 제안 없는 턴(잡담·조회)은 검색을 부르지 않는다 — 보강 트리거는 제안 draft다")
    void turnWithoutProposalSkipsSearch() {
        runner.run(USER, "요즘 커피 뭐가 맛있어?", null);

        assertThat(searchClient.calls).isZero();
    }

    @Test
    @DisplayName("ADR-61/TΔ3b: 다중 날짜 발화 = 탐지→세그먼터 1콜→세그먼트 컨텍스트 주입(가장 이른 날짜 활성)")
    void multiDateUtteranceInjectsSegments() {
        segmenter.canned = List.of(
                new UtteranceSegmenter.Segment(LocalDate.of(2026, 7, 15), "7/15 에티오피아 새콤했음"),
                new UtteranceSegmenter.Segment(LocalDate.of(2026, 7, 16), "7/16 케냐 진했음"));

        runner.run(USER, "7/15 에티오피아 새콤했음. 7/16 케냐 진했음", null);

        // 탐지 날짜 집합이 세그먼터에 그대로 전달된다(ADR-60·61 탐지기 공유).
        assertThat(segmenter.calls).isEqualTo(1);
        assertThat(segmenter.lastUtterance).isEqualTo("7/15 에티오피아 새콤했음. 7/16 케냐 진했음");
        assertThat(segmenter.lastDates).containsExactly(LocalDate.of(2026, 7, 15), LocalDate.of(2026, 7, 16));
        // 세그먼트가 턴 컨텍스트에 실리고(가장 이른 날짜 활성) 턴은 정상 진행된다.
        assertThat(chatClient.calls).isEqualTo(1);
        assertThat(chatClient.lastContext.instructions())
                .contains("다중 날짜 자동 분해 세그먼트")
                .contains("\"active_date\":\"2026-07-15\"")
                .contains("7/16 케냐 진했음");
    }

    @Test
    @DisplayName("AC-Δ2/ADR-61: 세그먼터 실패 = 주입 없이 턴 정상 진행 — 턴 폴백 아님(뭉뚱그림은 게이트 V-16이 방어)")
    void segmenterFailureProceedsWithoutInjection() {
        segmenter.failure = new IllegalStateException("세그먼터 응답 스키마 위반");

        TurnResult result = runner.run(USER, "7/15 에티오피아 새콤했음. 7/16 케냐 진했음", null);

        assertThat(segmenter.calls).isEqualTo(1);
        assertThat(chatClient.calls).isEqualTo(1);                 // 기록 흐름이 막히지 않는다
        assertThat(result.reply()).isEqualTo(chatClient.reply);    // 턴 실패로 번지지 않는다
        // 주입 블록 bullet 마커로 부재 단언 — 프롬프트 정책 문구의 "세그먼트"(TΔ3d)와 구분한다.
        assertThat(chatClient.lastContext.instructions()).doesNotContain("- 다중 날짜 자동 분해 세그먼트(");
    }

    @Test
    @DisplayName("ADR-61: 단일 날짜(탐지 2개 미만) 발화는 무개입 — 세그먼터 미호출, 컨텍스트 무변화")
    void singleDateUtteranceSkipsSegmenter() {
        runner.run(USER, "7/16에 마신 케냐 진했어", null);

        assertThat(segmenter.calls).isZero();
        // 주입 블록 bullet 마커로 부재 단언 — 프롬프트 정책 문구의 "세그먼트"(TΔ3d)와 구분한다.
        assertThat(chatClient.lastContext.instructions()).doesNotContain("- 다중 날짜 자동 분해 세그먼트(");
    }

    // ---- 헬퍼 ----

    /** 제안 성공 턴 시뮬레이션용 propose_record 인자 — 검증(V-1·5·8·11·14·15·16)을 통과하는 최소 형태. */
    private static final String PROPOSE_RECORD_ARGS = """
            {
              "coffee_name": {"value": "커피베라 예가체프 G1", "source": "user"},
              "roastery": {"value": "커피베라", "source": "user"},
              "beans": [], "roast_level": null, "official_notes": null,
              "brews": [{"recipe": null, "review": {
                "my_taste": "새콤하고 좋았음", "my_taste_original": "새콤하고 좋았다", "rating": "맛있다"}}],
              "target_date": "2026-07-16",
              "match": {"type": "new", "note_id": null, "date": null},
              "sources": []
            }
            """;

    // ---- fakes (모듈 CLAUDE.md §5.2 — 외부 의존은 인터페이스 stub/fake) ----

    /** 턴 입력을 캡처하고 지정된 응답·실패를 돌려주는 fake 루프 드라이버 — LLM 미접촉. */
    private static final class FakeChatClient implements ChatClient {
        String reply = "네 멍!";
        RuntimeException failure;
        Consumer<List<ToolCallback>> onRun;
        int calls;
        TurnPrompt lastContext;
        List<ToolCallback> lastTools;

        @Override
        public String runTurn(TurnPrompt context, List<ToolCallback> tools) {
            calls++;
            lastContext = context;
            lastTools = new ArrayList<>(tools);
            if (onRun != null) {
                onRun.accept(tools);
            }
            if (failure != null) {
                throw failure;
            }
            return reply;
        }
    }

    /** 지정된 검색 결과를 돌려주는 fake 검색 경계 — 웹 미접촉, 호출 여부·앵커를 캡처한다(TΔ24b 배선 단언용). */
    private static final class FakeSearchClient implements SearchClient {
        SearchResult canned = SearchResult.empty();
        int calls;
        SearchQuery lastQuery;

        @Override
        public SearchResult search(SearchQuery query) {
            calls++;
            lastQuery = query;
            return canned;
        }
    }

    /** 분리 결과·실패를 지정하는 fake 세그먼터 — LLM 미접촉, 호출 입력을 캡처한다(ADR-61 배선 단언용). */
    private static final class FakeSegmenter implements UtteranceSegmenter {
        List<Segment> canned = List.of();
        RuntimeException failure;
        int calls;
        String lastUtterance;
        Collection<LocalDate> lastDates;

        @Override
        public List<Segment> segment(String utterance, Collection<LocalDate> dates) {
            calls++;
            lastUtterance = utterance;
            lastDates = dates;
            if (failure != null) {
                throw failure;
            }
            return canned;
        }
    }
}
