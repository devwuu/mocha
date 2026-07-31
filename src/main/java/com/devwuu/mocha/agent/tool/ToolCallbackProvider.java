package com.devwuu.mocha.agent.tool;

import com.devwuu.mocha.agent.OpenAiChatClient;
import com.devwuu.mocha.agent.conversation.FoldingChatMemory;
import com.devwuu.mocha.agent.tool.validation.RecordProposalValidator;
import com.devwuu.mocha.agent.turn.TurnDraft;
import com.devwuu.mocha.agent.turn.TurnUserMessage;
import com.devwuu.mocha.repository.NoteRepository;
import com.devwuu.mocha.repository.PendingStore;
import com.devwuu.mocha.slack.outbound.PreviewMessenger;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.util.List;

/**
 * 에이전트 function tool 3종의 façade — 도메인 협력자를 주입받아 역할별 구현 클래스를 조립하고,
 * 턴 1회에 장착할 tool 목록(순서 포함)을 소유한다
 * (ref: specs/coffee-note-agent/plan.md §3 ToolCallbackProvider, #ADR-44/#ADR-45).
 * <p>구현은 역할 축으로 나뉜다(내부 협력자는 Spring 빈이 아니다):
 * <ul>
 *   <li>{@link NoteLookupTools} — 검색·회상 축: {@code list_notes}·{@code get_note} (TΔ5)</li>
 *   <li>{@link ProposalTools} — 쓰기 제안 축: {@code propose_record} (TΔ6)</li>
 * </ul>
 * 내장 {@code web_search}는 드라이버({@link OpenAiChatClient})가 장착하므로 여기 없다.
 * <p>changes/0029 TΔ1에서 5종 → 3종으로 줄었다 — {@code propose_edit}(수정은 UI 전용, D-1)과
 * {@code send_entry_card}(갤러리·상세 화면이 대체, D-5)가 폐기됐다.
 */
public class ToolCallbackProvider {

    private final NoteLookupTools lookupTools;
    private final ProposalTools proposalTools;

    public ToolCallbackProvider(NoteRepository noteRepository, ObjectMapper mapper, PendingStore pendingStore,
                                PreviewMessenger previewMessenger, RecordProposalValidator recordValidator,
                                FoldingChatMemory transcript, Clock clock) {
        this.lookupTools = new NoteLookupTools(noteRepository, mapper);
        this.proposalTools = new ProposalTools(noteRepository, pendingStore, previewMessenger, recordValidator,
                transcript, mapper, clock);
    }

    /**
     * 에이전트 턴 1회에 장착할 tool 목록 — 제안 tool이 pending을 소유할 사용자와, 미리보기를 배달할
     * 채널, 이번 턴의 사용자 원문({@code utterance} — 다중 날짜 게이트 V-16의 판정 입력), 그리고 작성 중인
     * 폼 상태({@code draft} — 출처 우선순위 대조 V-6의 판정 입력, 0029 TΔ2)를 턴마다 바인딩한다.
     * 툴킷은 애플리케이션 수명 객체라 턴별 값은 이 인자로만 유입된다(TΔ2b, findings-TΔ0 §C-2).
     */
    public List<ToolCallback> forTurn(String userId, String channelId, TurnUserMessage utterance, TurnDraft draft) {
        return List.of(
                lookupTools.listNotes(),
                lookupTools.getNote(),
                proposalTools.proposeRecord(userId, channelId, utterance, draft));
    }
}
