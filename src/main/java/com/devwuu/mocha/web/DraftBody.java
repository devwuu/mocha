package com.devwuu.mocha.web;

import com.devwuu.mocha.agent.turn.TurnDraft;
import com.devwuu.mocha.domain.MatchInfo;

/**
 * 클라이언트가 주고받는 <b>폼 상태 전체</b> — {@link TurnDraft}의 REST 판본
 * (ref: changes/0029 tasks.md TΔ6a, {@code contract/agent-turn.contract.json}).
 *
 * <p>같은 값이 세 방향으로 흐른다: 턴 요청의 {@code draft}(폼 → 서버), 턴 응답의 {@code draft}
 * (제안 → 폼), 저장 본문(TΔ6b). <i>"이번 턴의 제안 결과가 곧 다음 턴의 draft"</i>라는
 * {@link com.devwuu.mocha.agent.turn.TurnProposalSink}의 성질이 계약에서도 그대로 보인다.
 *
 * <p>도메인과 갈리는 지점은 {@link NoteBody} 한 겹뿐이다 — {@link MatchInfo}는 그대로 실려 나간다.
 */
public record DraftBody(NoteBody note, MatchInfo match) {

    /** 제안 없는 턴은 null로 수렴한다 — 그때 클라이언트는 폼을 지우지 않는다(계약의 nullable 지점). */
    public static DraftBody of(TurnDraft draft) {
        return draft == null ? null : new DraftBody(NoteBody.of(draft.note()), draft.match());
    }

    public TurnDraft toDraft() {
        return new TurnDraft(note.toNote(), match);
    }
}
