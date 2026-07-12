package com.devwuu.mocha.slack;

import com.devwuu.mocha.domain.PendingNote;

/**
 * 확인 상태 머신의 작업 시임 — {@link ConversationRouter}가 의도(게이트 5분류, ADR-24)로 분기를 정한 뒤
 * 실제 파이프라인 일을 위임하는 경계 (ref: plan.md §1 [2]~[8], ADR-3·ADR-24).
 * <p>라우터는 "어디로 갈지"(의도 판정·폴백·상태 확인)만 결정하고, 추출·보강·미리보기 조립·저장 같은
 * 오케스트레이션과 **안내 문구·전송**은 이 서비스가 소유한다 — 라우터는 responder를 직접 부르지 않는다
 * (ADR-24 구현 배치).
 * <ul>
 *   <li>{@link #startNewNote} — record + 대기 없음 → 신규 파이프라인(추출→매칭→보강→미리보기).
 *   <li>{@link #revisePending} — revise + 대기 있음 → pending 수정 반영 후 미리보기 갱신.
 *   <li>{@link #guidePendingExists}/{@link #guideNothingToRevise}/{@link #guideNotARecord} — 의도·상태
 *       불일치/미진입 안내(FR-17).
 *   <li>{@link #searchNotes}/{@link #endSearch} — 검색 세션 시작·계속·종료(FR-20, ADR-25).
 *   <li>{@link #confirmSave}/{@link #cancel} — [저장]/[취소] 버튼 커밋(ADR-3 불변 — 자연어로 오지 않는다).
 * </ul>
 * <p>구현: {@link DefaultConfirmationFlow}.
 */
public interface ConfirmationFlow {

    /** record 의도 + 대기 없음 → 신규 파이프라인 진입. 추출·매칭·보강 후 확인 미리보기를 보낸다(AC-1). */
    void startNewNote(IncomingMessage message);

    /**
     * revise 의도 + 대기 있음 → 수정 요청 반영(FR-5).
     * <p>POLICY: 수정은 기존 draft에 병합만 — 새 노트를 만들지 않는다 (ref: spec FR-5/AC-5).
     *
     * @param pending 라우터가 이미 조회한 유효(TTL 내) pending. 재조회 없이 그대로 넘긴다.
     */
    void revisePending(IncomingMessage message, PendingNote pending);

    /**
     * record 의도인데 확인 대기 기록이 이미 있음 → 대기를 건드리지 않고 "먼저 저장/취소" 안내만 한다.
     * <p>POLICY: 단일 대기 원칙 — 새 대기를 만들지 않는다 (ref: spec FR-17/AC-30, plan.md#ADR-24).
     */
    void guidePendingExists(IncomingMessage message);

    /** revise 의도인데 대기 기록이 없음 → 고칠 대상이 없다는 안내만 한다(FR-17). */
    void guideNothingToRevise(IncomingMessage message);

    /** other 판정(또는 end인데 검색 세션 없음) → 파이프라인 미진입, 짧은 안내만(FR-17, AC-20). */
    void guideNotARecord(IncomingMessage message);

    /**
     * search 의도 → 검색 세션 시작/계속(FR-20, ADR-25). 후보 선정 결과에 따라 단일 매치 카드 재전송 /
     * 복수 후보 텍스트 목록 / 무후보 재질문(상한 도달 시 종료 안내 + 세션 폐기)으로 응답한다(AC-31~33).
     * <p>POLICY: 검색 세션은 pending을 읽기만 — 쓰기 금지(격리, AC-29) (ADR-25, FR-20).
     */
    void searchNotes(IncomingMessage message);

    /** end 의도 + 검색 세션 진행 중 → 세션 폐기 + 모카 톤 종료 안내(FR-17/FR-20, AC-34). */
    void endSearch(IncomingMessage message);

    /** [저장] 버튼 → 커밋 파이프라인(upsert→clear→렌더). pending 로드·TTL 판정은 여기서 한다(V-7). */
    void confirmSave(IncomingAction action);

    /** [취소] 버튼 → pending 폐기. */
    void cancel(IncomingAction action);

    /**
     * 사진 수신 → 버퍼 그룹핑(T4-2, FR-10).
     * <p>pending이 있으면 진행 중 노트에 사진을 첨부해 미리보기를 갱신하고, 없으면 사진 버퍼에 담아 뒤이을
     * 텍스트를 기다린다. 윈도우(mocha.photo.buffer-window) 밖의 이전 버퍼는 새 흐름으로 갈린다(AC-8).
     */
    void receiveMedia(IncomingMedia media);
}
