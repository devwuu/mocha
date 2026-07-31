package com.devwuu.mocha.agent.prompt;

import com.devwuu.mocha.agent.conversation.FoldingChatMemory;
import com.devwuu.mocha.agent.conversation.TranscriptTurn;
import com.devwuu.mocha.agent.turn.TurnDraft;
import com.devwuu.mocha.agent.turn.TurnUserMessage;
import com.devwuu.mocha.llm.VisionExtraction;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 에이전트 턴 컨텍스트 조립 — 트랜스크립트 + draft + OCR 결과 + 다중 날짜 세그먼트 + today를
 * {@link TurnPrompt}(instructions·messages)로 만든다
 * (ref: specs/coffee-note-agent/plan.md#ADR-44, spec FR-22; changes/0018 TΔ7a, 0029 TΔ2).
 * <p>배치: 시스템 프롬프트({@link AgentSystemPrompt}) 뒤에 턴 컨텍스트(today·draft·OCR·세그먼트)를 덧붙여
 * instructions로, 트랜스크립트(ADR-46)는 user/mocha 메시지로 재구성해 이번 발화와 함께 messages로.
 * <p>changes/0029 TΔ2에서 대기(pending) 주입이 <b>draft 주입</b>으로 바뀌었다({@link TurnDraft}) — 서버가
 * 대기를 소유하던 시절의 {@code mode}·{@code target}·{@code date_conflict}는 수정 세션과 함께 소멸했고
 * (delta 0029 D-1), 남는 것은 "폼에 지금 무엇이 담겨 있는가"뿐이다. 모델의 과업은 그 위에 이번 발화를
 * 반영하는 것이다(open-questions.md#OQ-4R).
 * <p>OCR 결과는 루프 전 전처리 1콜의 산물이다(FR-19) — 실패·무정보({@link VisionExtraction#empty()})는
 * 컨텍스트에 싣지 않고 진행한다(AC-28, 흐름 불변). 다중 날짜 세그먼트도 같은 자리의 전처리 산물이다
 * (ADR-61, changes/0023 TΔ3b) — 분해 미수행·실패 턴(null)은 싣지 않는다(분리 안내 폴백과 정합).
 */
public class TurnPromptAssembler {

    // 날짜 해석 기준은 Asia/Seoul(V-3) — 컨텍스트에 명시해 상대 날짜("엊그제")의 절대화 기준을 못박는다.
    private static final String TIMEZONE_LABEL = "Asia/Seoul";

    private final ObjectMapper mapper;
    private final Clock clock;

    public TurnPromptAssembler(ObjectMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    /**
     * 턴 1회의 입력 컨텍스트 조립.
     *
     * @param userMessage 이번 사용자 발화(사진 캡션 포함) — messages의 마지막 user 메시지가 된다
     * @param transcript  현재 트랜스크립트 문맥({@code FoldingChatMemory.view()}) — 비었으면 이번 발화만
     * @param draft       작성 중인 폼 상태 — 폼이 떠 있지 않으면 null. 모델은 이 위에 이번 발화를 반영한다(TΔ2)
     * @param ocr         수신 사진 OCR 전처리 결과 — 사진 없음·실패·무정보면 null 또는 empty(AC-28)
     * @param segments    다중 날짜 자동 분해 결과(ADR-61) — 분해 미수행(단일 날짜)·세그먼터 실패 턴은 null.
     *                    라우터가 검증 게이트({@code TurnUserMessage})와 같은 값을 넘긴다(findings-TΔ0 §C-5 드리프트 방지)
     */
    public TurnPrompt assemble(String userMessage, List<TranscriptTurn> transcript,
                                     TurnDraft draft, VisionExtraction ocr,
                                     List<TurnUserMessage.Segment> segments) {
        Objects.requireNonNull(userMessage, "userMessage");
        Objects.requireNonNull(transcript, "transcript");

        List<TurnPrompt.Message> messages = new ArrayList<>();
        for (TranscriptTurn turn : transcript) {
            messages.add(TurnPrompt.Message.user(turn.userMessage()));
            messages.add(TurnPrompt.Message.mocha(turn.mochaMessage()));
        }
        messages.add(TurnPrompt.Message.user(userMessage));

        return new TurnPrompt(
                AgentSystemPrompt.INSTRUCTIONS + "\n" + turnContext(draft, ocr, segments), messages);
    }

    // 턴 컨텍스트 블록 — 프롬프트 정책이 참조하는 동적 사실(today·draft·OCR·세그먼트)만 싣는다.
    private String turnContext(TurnDraft draft, VisionExtraction ocr, List<TurnUserMessage.Segment> segments) {
        StringBuilder sb = new StringBuilder("## 이번 턴 컨텍스트\n");
        sb.append("- today: ").append(LocalDate.now(clock)).append(" (").append(TIMEZONE_LABEL)
                .append(") — 상대 날짜는 이 날짜 기준으로 해석한다.\n");
        if (draft == null) {
            sb.append("- 작성 중인 draft: 없음.\n");
        } else {
            sb.append("- 작성 중인 draft(사용자가 보고 고칠 수 있었던 현재 폼 상태다 — 새로 만들지 말고 ")
                    .append("이 draft 위에 이번 발화를 반영해 전체를 다시 제안해라. draft에 값이 있는 필드를 ")
                    .append("더 낮은 출처로 덮지 마라 — user > photo > search): \n")
                    .append("  ").append(mapper.writeValueAsString(draftContext(draft))).append("\n");
        }
        if (hasReadableInfo(ocr)) {
            sb.append("- 수신 사진 OCR 결과(source=photo 재료 — 매칭·필드 값에 써라): \n")
                    .append("  ").append(mapper.writeValueAsString(ocr)).append("\n");
        }
        if (segments != null && !segments.isEmpty()) {
            // ADR-61: 가장 이른 날짜만 활성 — 이번 턴은 활성 세그먼트만 제안하고 나머지는 저장 후 이어간다.
            sb.append("- 다중 날짜 자동 분해 세그먼트(active_date = 가장 이른 날짜 = 이번 턴에 제안할 유일한 날짜다. ")
                    .append("나머지 날짜는 저장 후 이어서 제안한다고 안내해라): \n")
                    .append("  ").append(mapper.writeValueAsString(segmentContext(segments))).append("\n");
        }
        return sb.toString();
    }

    // 세그먼트 주입 조각 — 오름차순 계약(UtteranceSegmenter)에 기대지 않고 활성(가장 이른) 날짜를 직접 계산한다.
    private static Map<String, Object> segmentContext(List<TurnUserMessage.Segment> segments) {
        LocalDate activeDate = segments.stream()
                .map(TurnUserMessage.Segment::date)
                .min(Comparator.naturalOrder())
                .orElseThrow(); // 호출부가 비어 있지 않음을 보장한다
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("active_date", activeDate);
        context.put("segments", segments);
        return context;
    }

    // draft에서 에이전트 판단에 쓰이는 조각만 추린다 — 배관 필드(구 preview_ts·created_at)는 애초에 없다.
    private static Map<String, Object> draftContext(TurnDraft draft) {
        Map<String, Object> context = new LinkedHashMap<>();
        if (draft.match() != null) {
            context.put("match", draft.match());
        }
        context.put("draft", draft.note());
        return context;
    }

    // POLICY: OCR 실패·무정보는 흐름을 바꾸지 않는다 — 빈 결과는 컨텍스트에 싣지 않고 첨부로만 처리
    //         (ref: specs/coffee-note-agent/plan.md#ADR-23 POLICY, spec AC-28).
    private static boolean hasReadableInfo(VisionExtraction ocr) {
        if (ocr == null) {
            return false;
        }
        return ocr.coffeeName() != null || ocr.roastery() != null || !ocr.beans().isEmpty()
                || ocr.roastLevel() != null || !ocr.officialNotes().isEmpty();
    }
}
