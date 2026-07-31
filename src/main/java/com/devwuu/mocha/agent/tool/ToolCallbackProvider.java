package com.devwuu.mocha.agent.tool;

import com.devwuu.mocha.agent.OpenAiChatClient;
import com.devwuu.mocha.agent.tool.validation.RecordProposalValidator;
import com.devwuu.mocha.agent.turn.TurnDraft;
import com.devwuu.mocha.agent.turn.TurnProposalSink;
import com.devwuu.mocha.agent.turn.TurnUserMessage;
import com.devwuu.mocha.repository.NoteRepository;
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

    // 0029 TΔ3: 트랜스크립트 주입이 빠졌다 — 제안 성공 접힘이 폐기되며 tool 계층이 대화 문맥을
    // 건드릴 이유가 사라졌다(접힘은 커밋·TTL만, 축적은 라우터 소유).
    // 0029 TΔ4: pending 저장소·미리보기 송신 주입이 빠졌다 — 제안이 서버 상태를 쓰지 않고 결과를 돌려주는
    // 값이 되면서, tool 계층에 남아 있던 마지막 Slack 결합(PreviewMessenger)도 함께 끊겼다(baseline §2.2).
    public ToolCallbackProvider(NoteRepository noteRepository, ObjectMapper mapper,
                                RecordProposalValidator recordValidator, Clock clock) {
        this.lookupTools = new NoteLookupTools(noteRepository, mapper);
        this.proposalTools = new ProposalTools(noteRepository, recordValidator, mapper, clock);
    }

    /**
     * 에이전트 턴 1회에 장착할 tool 목록 — 관측 주체({@code userId}), 이번 턴의 사용자 원문
     * ({@code utterance} — 다중 날짜 게이트 V-16의 판정 입력), 작성 중인 폼 상태({@code draft} — 출처
     * 우선순위 대조 V-6의 판정 입력, 0029 TΔ2), 그리고 제안 결과를 받아 갈 수거함({@code sink} — TΔ4)을
     * 턴마다 바인딩한다. 툴킷은 애플리케이션 수명 객체라 턴별 값은 이 인자로만 유입된다
     * (TΔ2b, findings-TΔ0 §C-2).
     */
    public List<ToolCallback> forTurn(String userId, TurnUserMessage utterance, TurnDraft draft,
                                      TurnProposalSink sink) {
        return List.of(
                lookupTools.listNotes(),
                lookupTools.getNote(),
                proposalTools.proposeRecord(userId, utterance, draft, sink));
    }
}
