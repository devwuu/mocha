package com.devwuu.mocha.slack;

import com.devwuu.mocha.domain.PendingNote;

/**
 * 확인 상태 머신의 작업 시임 — {@link ConversationRouter}가 분기를 정한 뒤 실제 파이프라인 일을 위임하는 경계
 * (ref: plan.md §1 [2]~[7], ADR-3).
 * <p>라우터는 pending 유무·action_id로 "어디로 갈지"만 결정하고(얇은 수신 계층, CLAUDE.md §2), 추출·보강·
 * 미리보기 조립·저장 같은 오케스트레이션은 이 서비스가 맡는다. 각 메서드는 아직 미구현이며 후속 task가 채운다:
 * <ul>
 *   <li>{@link #startNewNote} — 신규 파이프라인(추출→매칭→보강→미리보기 전송), T3-3.
 *   <li>{@link #revisePending} — pending 수정 반영 후 미리보기 갱신, T3-4.
 *   <li>{@link #confirmSave}/{@link #cancel} — [저장]/[취소] 커밋, T3-5.
 * </ul>
 * <p>구현: {@link DefaultConfirmationFlow}. T3-7 시점엔 네 경로가 모두 실배선돼 있다
 * (신규 파이프라인·수정 반영·저장·취소).
 */
public interface ConfirmationFlow {

    /** pending 없음 → 신규 파이프라인 진입. 추출·매칭·보강 후 확인 미리보기를 보낸다(T3-3). */
    void startNewNote(IncomingMessage message);

    /**
     * pending 있음 + 텍스트 → 수정 요청 반영(T3-4).
     * <p>POLICY: pending 중 텍스트는 전부 수정 요청 — 새 노트를 만들지 않는다 (ref: spec FR-5/AC-5, plan#ADR-3).
     *
     * @param pending 라우터가 이미 조회한 유효(TTL 내) pending. 재조회 없이 그대로 넘긴다.
     */
    void revisePending(IncomingMessage message, PendingNote pending);

    /** [저장] 버튼 → 커밋 파이프라인(upsert→clear→렌더). pending 로드·TTL 판정은 여기서 한다(T3-5, V-7). */
    void confirmSave(IncomingAction action);

    /** [취소] 버튼 → pending 폐기(T3-5). */
    void cancel(IncomingAction action);
}
