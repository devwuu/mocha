package com.devwuu.mocha.agent;

import com.devwuu.mocha.domain.PendingNote;
import com.devwuu.mocha.llm.VisionExtraction;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 에이전트 턴 컨텍스트 조립 — 트랜스크립트 + pending draft + OCR 결과 + today를
 * {@link AgentTurnContext}(instructions·messages)로 만든다
 * (ref: specs/coffee-note-agent/plan.md#ADR-44, spec FR-22; changes/0018 TΔ7a).
 * <p>배치: 시스템 프롬프트({@link AgentSystemPrompt}) 뒤에 턴 컨텍스트(today·pending·OCR)를 덧붙여
 * instructions로, 트랜스크립트(ADR-46)는 user/assistant 메시지로 재구성해 이번 발화와 함께 messages로.
 * <p>OCR 결과는 루프 전 전처리 1콜의 산물이다(FR-19) — 실패·무정보({@link VisionExtraction#empty()})는
 * 컨텍스트에 싣지 않고 진행한다(AC-28, 흐름 불변).
 */
public class AgentContextAssembler {

    // 날짜 해석 기준은 Asia/Seoul(V-3) — 컨텍스트에 명시해 상대 날짜("엊그제")의 절대화 기준을 못박는다.
    private static final String TIMEZONE_LABEL = "Asia/Seoul";

    private final ObjectMapper mapper;
    private final Clock clock;

    public AgentContextAssembler(ObjectMapper mapper, Clock clock) {
        this.mapper = mapper;
        this.clock = clock;
    }

    /**
     * 턴 1회의 입력 컨텍스트 조립.
     *
     * @param userMessage 이번 사용자 발화(사진 캡션 포함) — messages의 마지막 user 메시지가 된다
     * @param transcript  현재 트랜스크립트 문맥({@code ConversationTranscript.view()}) — 비었으면 이번 발화만
     * @param pending     확인 대기 — 없으면 null. draft가 곧 접힘 후 문맥의 압축본이다(ADR-46)
     * @param ocr         수신 사진 OCR 전처리 결과 — 사진 없음·실패·무정보면 null 또는 empty(AC-28)
     */
    public AgentTurnContext assemble(String userMessage, List<TranscriptTurn> transcript,
                                     PendingNote pending, VisionExtraction ocr) {
        Objects.requireNonNull(userMessage, "userMessage");
        Objects.requireNonNull(transcript, "transcript");

        List<AgentMessage> messages = new ArrayList<>();
        for (TranscriptTurn turn : transcript) {
            messages.add(AgentMessage.user(turn.userMessage()));
            messages.add(AgentMessage.assistant(turn.assistantMessage()));
        }
        messages.add(AgentMessage.user(userMessage));

        return new AgentTurnContext(AgentSystemPrompt.INSTRUCTIONS + "\n" + turnContext(pending, ocr), messages);
    }

    // 턴 컨텍스트 블록 — 프롬프트 정책이 참조하는 동적 사실(today·대기 상태·OCR)만 싣는다.
    private String turnContext(PendingNote pending, VisionExtraction ocr) {
        StringBuilder sb = new StringBuilder("## 이번 턴 컨텍스트\n");
        sb.append("- today: ").append(LocalDate.now(clock)).append(" (").append(TIMEZONE_LABEL)
                .append(") — 상대 날짜는 이 날짜 기준으로 해석한다.\n");
        if (pending == null) {
            sb.append("- 확인 대기(pending): 없음.\n");
        } else {
            sb.append("- 확인 대기(pending): 아래 제안이 [저장]/[취소]를 기다리는 중이다 — 애매한 발화는 이 내용의 수정 의도를 우선 고려해라.\n")
                    .append("  ").append(mapper.writeValueAsString(pendingContext(pending))).append("\n");
        }
        if (hasReadableInfo(ocr)) {
            sb.append("- 수신 사진 OCR 결과(source=photo 재료 — 매칭·필드 값에 써라): \n")
                    .append("  ").append(mapper.writeValueAsString(ocr)).append("\n");
        }
        return sb.toString();
    }

    // pending에서 에이전트 판단에 쓰이는 조각만 추린다 — preview_ts·created_at 같은 배관 필드는 뺀다.
    private static Map<String, Object> pendingContext(PendingNote pending) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("mode", pending.mode().json());
        if (pending.target() != null) {
            context.put("target", pending.target());
        }
        if (pending.mode() == PendingNote.Mode.EDIT) {
            context.put("date_conflict", pending.dateConflict());
        }
        if (pending.match() != null) {
            context.put("match", pending.match());
        }
        context.put("draft", pending.draft());
        return context;
    }

    // POLICY: OCR 실패·무정보는 흐름을 바꾸지 않는다 — 빈 결과는 컨텍스트에 싣지 않고 첨부로만 처리
    //         (ref: specs/coffee-note-agent/plan.md#ADR-23 POLICY, spec AC-28).
    private static boolean hasReadableInfo(VisionExtraction ocr) {
        if (ocr == null) {
            return false;
        }
        return ocr.coffeeName() != null || ocr.roastery() != null || ocr.origin() != null
                || ocr.process() != null || ocr.roastLevel() != null || !ocr.officialNotes().isEmpty();
    }
}
