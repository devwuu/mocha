package com.devwuu.mocha.agent.tool;

import com.devwuu.mocha.agent.OpenAiAgentClient;
import com.devwuu.mocha.agent.conversation.ConversationTranscript;
import com.devwuu.mocha.agent.turn.TurnUtterance;
import com.devwuu.mocha.render.NoteRenderer;
import com.devwuu.mocha.repository.NoteRepository;
import com.devwuu.mocha.repository.PendingStore;
import com.devwuu.mocha.slack.outbound.PreviewMessenger;
import com.devwuu.mocha.slack.outbound.SlackResponder;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.Clock;
import java.util.List;

/**
 * 에이전트 function tool 5종의 façade — 도메인 협력자를 주입받아 역할별 구현 클래스를 조립하고,
 * 턴 1회에 장착할 tool 목록(순서 포함)을 소유한다
 * (ref: specs/coffee-note-agent/plan.md §3 AgentToolkit, #ADR-44/#ADR-45).
 * <p>구현은 역할 축으로 나뉜다(내부 협력자는 Spring 빈이 아니다):
 * <ul>
 *   <li>{@link NoteLookupTools} — 검색·회상 축: {@code list_notes}·{@code get_note}·{@code send_entry_card} (TΔ5)</li>
 *   <li>{@link ProposalTools} — 쓰기 제안 축: {@code propose_record}·{@code propose_edit} (TΔ6)</li>
 * </ul>
 * 내장 {@code web_search}는 드라이버({@link OpenAiAgentClient})가 장착하므로 여기 없다.
 */
public class AgentToolkit {

    private final NoteLookupTools lookupTools;
    private final ProposalTools proposalTools;

    public AgentToolkit(NoteRepository noteRepository, NoteRenderer noteRenderer, SlackResponder responder,
                      Path artifactDir, ObjectMapper mapper, PendingStore pendingStore,
                      PreviewMessenger previewMessenger, ProposalValidator validator,
                      ConversationTranscript transcript, Clock clock) {
        this.lookupTools = new NoteLookupTools(noteRepository, noteRenderer, responder, artifactDir, mapper);
        this.proposalTools = new ProposalTools(noteRepository, pendingStore, previewMessenger, validator,
                transcript, mapper, clock);
    }

    /**
     * 에이전트 턴 1회에 장착할 tool 목록 — 제안 tool이 pending을 소유할 사용자와, 미리보기·카드를 배달할
     * 채널, 그리고 이번 턴의 사용자 원문({@code utterance} — 다중 날짜 게이트 V-16의 판정 입력)을 턴마다
     * 바인딩한다. 툴킷은 애플리케이션 수명 객체라 턴별 값은 이 인자로만 유입된다(TΔ2b, findings-TΔ0 §C-2).
     */
    public List<AgentTool> forTurn(String userId, String channelId, TurnUtterance utterance) {
        return List.of(
                lookupTools.listNotes(),
                lookupTools.getNote(),
                proposalTools.proposeRecord(userId, channelId, utterance),
                proposalTools.proposeEdit(userId, channelId),
                lookupTools.sendEntryCard(channelId));
    }
}
