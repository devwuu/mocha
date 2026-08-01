package com.devwuu.mocha.agent.turn;

import com.devwuu.mocha.agent.ChatClient;
import com.devwuu.mocha.agent.conversation.FoldingChatMemory;
import com.devwuu.mocha.agent.conversation.TranscriptTurn;
import com.devwuu.mocha.agent.prompt.TurnPrompt;
import com.devwuu.mocha.agent.prompt.TurnPromptAssembler;
import com.devwuu.mocha.agent.tool.ToolCallbackProvider;
import com.devwuu.mocha.llm.UtteranceSegmenter;
import com.devwuu.mocha.llm.VisionExtraction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import java.util.NavigableSet;

/**
 * 에이전트 턴 실행부 — 전처리(다중 날짜 분해) → 컨텍스트 조립 → 루프 → 제안 수거 → 트랜스크립트 축적을
 * 한 메서드로 소유한다 (ref: plan.md §1 [1]·[3], #ADR-44·#ADR-61·#ADR-64;
 * changes/0029 tasks.md TΔ6a).
 *
 * <p><b>왜 여기 있는가</b>: 이 오케스트레이션은 changes/0018 이래 {@code slack/AgentConversationRouter}
 * 안에 살았고, 그 안에서 실제로 Slack인 것은 <i>수신 형식·응답 배달·사진 버퍼</i>뿐이었다. 앱이 같은 턴을
 * 돌려야 하는 순간({@code POST /api/agent/turn}) 전송 계층 밑에 깔린 채로는 부를 수 없어 여기로 뽑았다.
 * <b>TΔ16에서 Slack이 걷히며 전송 계층은 다시 하나가 됐지만 이 분리는 그대로다</b> — 뽑아 둔 덕분에
 * 라우터 삭제가 턴 로직을 한 줄도 건드리지 않았고, 그 자체가 경계가 옳았다는 사후 확인이다.
 *
 * <p>{@code agent/turn/}에 두는 이유는 ADR-64다 — 턴 전처리·컨텍스트 운반체({@link TurnUserMessage}·
 * {@link TurnDraft}·{@link TurnProposalSink})가 이미 여기 살고, 이 클래스는 그것들을 엮는 자리다.
 * 저장 유스케이스({@code service/NoteService})와 달리 <b>DB를 지나지 않는다</b> — 쓰기는 tool 안에서도
 * 일어나지 않고(TΔ4 이후 제안은 값이다), 저장은 별도 확정 API의 몫이다(TΔ6b).
 *
 * <p>POLICY: 턴 실패(모델 오류·tool 상한·검증 반복)는 <b>노트 무변화 + 원문 로그 보존</b>으로 수렴하고
 * 예외를 그대로 올린다 — <i>재요청 안내</i>는 전송 계층이 정한다(REST는 실패 응답, 문구의 소유자는 화면이다)
 * (ref: plan.md#ADR-48, spec FR-25/AC-63).
 *
 * <p>테스트 seam이기도 하다 — 컨트롤러 단위 테스트가 이 타입을 상속한 손 fake로 턴을 대체한다
 * ({@code org.mockito} 미도입 방침 유지). 그래서 {@code final}이 아니고 {@link #run}이 오버라이드 가능하다.
 */
public class TurnRunner {

    private static final Logger log = LoggerFactory.getLogger(TurnRunner.class);

    private final FoldingChatMemory transcript;
    private final ChatClient chatClient;
    private final ToolCallbackProvider toolCallbackProvider;
    private final TurnPromptAssembler promptAssembler;
    private final UtteranceSegmenter segmenter;
    // 이번 턴에 실린 사진의 OCR 전처리(ADR-23·44, TΔ8a) — 사진 없는 턴에는 발동하지 않는다.
    private final TurnPhotoOcr photoOcr;
    // 제안 수거 후 검색 보강(D-16, TΔ24b) — 채울 것이 없는 턴에는 발동하지 않는다.
    private final TurnProposalEnricher proposalEnricher;
    // 시계(Asia/Seoul — V-3, 트랜스크립트와 동일)는 config 공통 빈 주입(ADR-63).
    private final Clock clock;

    // 협력자 조립은 config가 소유(ADR-63, TurnConfig) — 이 클래스는 주입만 받는다.
    public TurnRunner(
            FoldingChatMemory transcript,
            ChatClient chatClient,
            ToolCallbackProvider toolCallbackProvider,
            TurnPromptAssembler promptAssembler,
            UtteranceSegmenter segmenter,
            TurnPhotoOcr photoOcr,
            TurnProposalEnricher proposalEnricher,
            Clock clock) {
        this.transcript = transcript;
        this.chatClient = chatClient;
        this.toolCallbackProvider = toolCallbackProvider;
        this.promptAssembler = promptAssembler;
        this.segmenter = segmenter;
        this.photoOcr = photoOcr;
        this.proposalEnricher = proposalEnricher;
        this.clock = clock;
    }

    /** 사진 없는 턴 — 수신 사진 OCR(FR-19)이 빈 결과라 컨텍스트에서 빠진다. */
    public TurnResult run(String userId, String utterance, TurnDraft draft) {
        return run(userId, utterance, draft, VisionExtraction.empty());
    }

    /**
     * 에이전트 턴 1회 실행.
     *
     * <p><b>사진 OCR이 이 안에 있다</b>(TΔ8a): 구 판본은 전송 계층(Slack 라우터)이 읽어 결과를 넘겼고
     * 그래서 이 메서드가 {@code VisionExtraction}을 인자로 받았다. 앱에서는 턴 요청이 <i>사진 이름</i>을
     * 싣고 오므로(D-11) 읽기부터가 턴의 일이다 — OCR은 애초에 tool이 아니라 <b>루프 전 결정론
     * 전처리</b>(ADR-44)이고, 다중 날짜 분해와 같은 자리다.
     *
     * @param userId     관측·트랜스크립트·tool의 주체 키.
     * @param utterance  이번 턴의 사용자 발화 원문. 사진만 첨부한 턴은 빈 문자열이다(D-11).
     * @param draft      현재 폼 상태(0029 TΔ2). 폼이 없으면 null — 첫 턴이 그렇다.
     * @param photoNames 이번 턴에 첨부된 스테이징 사진 파일명(업로드 응답이 준 값). 없으면 빈 목록.
     * @throws com.devwuu.mocha.agent.AgentException 턴 미완결(모델 호출 실패·상한 도달 — ADR-48 폴백 대상)
     */
    public TurnResult run(String userId, String utterance, TurnDraft draft, List<String> photoNames) {
        if (photoNames == null || photoNames.isEmpty()) {
            return run(userId, utterance, draft, VisionExtraction.empty());
        }
        // OCR은 tool이 아니라 루프 전 결정론 전처리다(ADR-23·44) — 사진 있으면 1콜, 실패·무정보는 빈 결과로
        // 컨텍스트에서 빠진다(FR-19, AC-28 — 조립기가 거른다).
        return run(userId, utterance, draft, photoOcr.read(userId, photoNames));
    }

    /**
     * OCR 결과를 이미 들고 있는 경로 — 사진 읽기를 지나지 않는다.
     *
     * <p>테스트 seam으로 남긴다: 컨텍스트 주입(FR-19)만 검증하는 자리가 스테이징 파일을 실제로 만들
     * 필요는 없다. 프로덕션 호출부는 위 {@code photoNames} 판본 하나다.
     */
    TurnResult run(String userId, String utterance, TurnDraft draft, VisionExtraction ocr) {
        // 다중 날짜 자동 분해(ADR-61) — OCR과 동렬의 루프 전 전처리. 탐지기가 절대 날짜 2개 이상을
        // 찾은 턴에만 세그먼터 1콜, 그 외·실패 턴은 null(주입 없음 — 게이트 V-16이 뭉뚱그림을 방어).
        List<TurnUserMessage.Segment> segments = segmentIfMultiDate(userId, utterance);

        TurnPrompt context = promptAssembler.assemble(
                utterance, transcript.view(userId), draft, ocr, segments);

        // POLICY: 턴 진입 관측에 사용자 발화 원문을 함께 남긴다 — 종전에는 폴백(ADR-48) 1지점에만 원문이
        //         남아 outcome=완료로 끝난 행동 실패의 원문이 회수되지 않았고, 그 결과 박제 루프(ADR-69 ①)의
        //         회수 경로가 성립하지 않았다. 원문은 개인 데이터이므로 logs/ 비커밋 규칙이 그대로 적용된다
        //         (ref: specs/coffee-note-agent/plan.md §6 ADR-44 관측·#ADR-69, NFR-7/ADR-21, 루트 CLAUDE.md §5).
        // 구 라우터 판본의 buffered=<개수>가 ocr=<유무>로 바뀌었다 — 버퍼는 Slack 배관이었고 TΔ16에서
        // 소멸했다. 이 자리가 아는 것은 "사진에서 읽은 것이 있는가"까지이고, 사진 수는 읽은 쪽
        // ({@code TurnPhotoOcr})이 남긴다(TΔ8a — 그것이 몇 장을 실제로 vision에 실었는지 아는 유일한 자리다).
        log.info("에이전트 턴 진입: user={} draft={} ocr={} segments={} 원문={}",
                userId, draft != null, !VisionExtraction.empty().equals(ocr),
                segments == null ? "-" : segments.size(), utterance);

        // TΔ2b 배선: 턴 원문·세그먼트를 제안 검증기까지 나른다(다중 날짜 게이트 V-16의 판정 입력, ADR-60).
        // 1회 만들어 조립기와 같은 값을 넘긴다 — 턴 안에서 값이 일관된다(findings-TΔ0 §C-5).
        TurnUserMessage message = new TurnUserMessage(utterance, segments);
        // TΔ4: 제안 결과 수거함 — 턴마다 새로 만든다. 서버가 대기 상태를 갖지 않게 된 뒤로 "무엇이
        // 제안됐나"를 볼 수 있는 유일한 자리다.
        TurnProposalSink proposals = new TurnProposalSink();

        String reply = chatClient.runTurn(context,
                toolCallbackProvider.forTurn(userId, message, draft, proposals));

        // POLICY: 성공 턴은 제안 여부와 무관하게 트랜스크립트에 쌓는다 — 0029 TΔ3에서 제안 성공 접힘(구
        //         ADR-46 규칙 ①)이 폐기됐다. 남는 것은 커밋 접힘과 TTL뿐이라, 제안된 draft의 대화 문맥이
        //         [저장]/[취소]까지 살아 있고 다중 날짜 이어가기(ADR-61)의 남은 날짜도 함께 남는다
        //         (ref: specs/coffee-note-agent/plan.md#ADR-46, spec FR-23, changes/0029 baseline.md §4.3).
        //         실패 턴이 쌓이지 않는 것은 예외가 여기 도달하기 전에 올라가기 때문이다(ADR-48).
        transcript.append(userId, new TranscriptTurn(utterance, reply));

        // 검색 보강은 루프 «밖»의 결정론 단계다(0029 TΔ24b, delta D-16) — OCR·세그먼터와 같은 부류이고,
        // 다만 전처리가 아니라 후처리라 제안이 선 뒤에 돈다. 여기여야 보강 값이 응답의 draft에 실려 폼에
        // 채워진 채로 도착한다(FR-12). 제안 없는 턴(잡담·조회·검증 거부)은 그대로 통과한다.
        return new TurnResult(reply, proposalEnricher.enrich(proposals.proposal().orElse(null)));
    }

    // 다중 날짜 턴의 세그먼트 분리(ADR-61) — 단일 날짜(탐지 2개 미만) 턴은 세그먼터를 부르지 않는다(무개입).
    private List<TurnUserMessage.Segment> segmentIfMultiDate(String userId, String text) {
        NavigableSet<LocalDate> dates = TastingDateDetector.detect(text, LocalDate.now(clock));
        if (dates.size() < 2) {
            return null;
        }
        try {
            return segmenter.segment(text, dates).stream()
                    .map(s -> new TurnUserMessage.Segment(s.date(), s.text()))
                    .toList();
        } catch (RuntimeException e) {
            // POLICY: 세그먼터 실패는 자동 분해 없이 진행 — 기록이 막히지 않고(턴 폴백 아님), 뭉뚱그림 제안은
            //         게이트(V-16)가 거부해 구 UX(분리 안내)로 수렴한다 (ref: plan.md#ADR-61 POLICY, AC-Δ2).
            log.warn("세그먼터 실패 — 주입 없이 진행(분리 안내 폴백, 게이트 방어): user={} dates={}", userId, dates, e);
            return null;
        }
    }
}
