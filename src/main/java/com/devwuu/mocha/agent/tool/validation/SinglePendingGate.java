package com.devwuu.mocha.agent.tool.validation;

import com.devwuu.mocha.domain.Aliases;
import com.devwuu.mocha.domain.PendingNote;
import com.devwuu.mocha.domain.Sourced;

import java.time.LocalDate;

/**
 * 단일 대기 게이트 패밀리(FR-22/AC-30) — 확인 대기(pending) 중 다른 대상의 제안을 거부하고, 같은
 * 대상의 재호출만 FR-5 갱신 경로로 통과시킨다. record·edit 진입점이 거부 문안을 공유한다
 * (ref: specs/coffee-note-agent/plan.md#ADR-45·ADR-64 — 추출은 배치 변경, 판정·문안 불변).
 * <p>pending의 구조 무결성(mode별 draft·target 존재)은 저장소 로드 경계가 보장하므로(ADR-66 — 훼손은
 * get()에서 정리 후 부재로 수렴) 여기서 재검증하지 않는다: 종전 훼손 pending null 가드는 0025에서 제거됐다.
 */
final class SinglePendingGate {

    private SinglePendingGate() {
    }

    // POLICY: 확인 대기 중 새 기록 제안은 서버가 거부한다 — 단일 대기 원칙 (ref: plan.md#ADR-45, AC-30).
    //         record pending 존재 중 "같은 커피"의 재호출만 FR-5 갱신 경로로 통과시킨다. 같은 커피 판정 =
    //         정규화(소문자화·공백 제거, V-13 기준) coffee_name+roastery 동일 — 싱글 오리진은 이름이
    //         로스터리 간 겹치기 쉬워 이름만으로는 부족하다(ADR-37 정체성 기준과 동일, 사용자 확정
    //         2026-07-17). 단 한쪽 roastery가 비어 있으면 이름만 대조한다 — 모름은 다름이 아니며,
    //         대기 중 roastery 보강·정정 갱신을 막지 않는다 (ref: data-model.md#3.3).
    static void requireUpdatableOrFree(PendingNote pending, String proposedCoffeeName,
                                       String proposedRoastery) {
        if (pending == null) {
            return;
        }
        if (pending.mode() == PendingNote.Mode.RECORD
                && sameNormalized(proposedCoffeeName, draftCoffeeName(pending))
                && roasteryCompatible(proposedRoastery, pending.draft().roastery())) {
            return; // FR-5: 대기 중 수정 발화 = 같은 커피의 propose_record 재호출 → 갱신 경로.
        }
        throw new RejectedException(pendingBlocksReason(pending));
    }

    // POLICY: 단일 대기 원칙 준용(record든 edit든) — 확인 대기 중 다른 대상의 수정 세션은 거부한다.
    //         같은 대상(note_id+date)의 propose_edit 재호출만 FR-5 후속 수정으로 통과시킨다
    //         (ref: plan.md#ADR-45, 구 수정 진입 거부의 승계).
    static void requireSameEditTargetOrFree(PendingNote pending, Long noteId, LocalDate targetDate) {
        if (pending == null) {
            return;
        }
        if (pending.mode() == PendingNote.Mode.EDIT
                && pending.target() != null
                && pending.target().noteId().equals(noteId)
                && pending.target().date().equals(targetDate)) {
            return; // FR-5: 수정 세션의 후속 수정 발화 = 같은 대상의 propose_edit 재호출 → 갱신 경로.
        }
        throw new RejectedException(pendingBlocksReason(pending));
    }

    private static boolean sameNormalized(String a, String b) {
        return Aliases.normalize(a).equals(Aliases.normalize(b));
    }

    // 같은 커피 판정의 roastery 축 — 양쪽 다 있을 때만 대조하고, 한쪽이 비면 이름 대조에 맡긴다.
    private static boolean roasteryCompatible(String proposed, Sourced<String> draftRoastery) {
        String draft = draftRoastery == null ? null : draftRoastery.value();
        if (Aliases.normalize(proposed).isEmpty() || Aliases.normalize(draft).isEmpty()) {
            return true;
        }
        return sameNormalized(proposed, draft);
    }

    // draft의 커피명 표시값 — null 안전 추출은 Sourced.valueOrNull 단일 소스에 위임한다(ADR-67).
    // draft 존재는 저장소 로드 경계가 보장한다(ADR-66) — 여기서 재검증하지 않는다.
    private static String draftCoffeeName(PendingNote pending) {
        return Sourced.valueOrNull(pending.draft().coffeeName());
    }

    private static String pendingBlocksReason(PendingNote pending) {
        // pending의 구조 무결성(mode별 draft·target 존재)은 저장소 로드 경계가 보장하므로 재검증 없이 참조한다
        // (ref: plan.md#ADR-66 POLICY, data-model §2.3).
        String current = pending.mode() == PendingNote.Mode.EDIT
                ? "수정 세션(노트 " + pending.target().noteId() + ")"
                : "새 기록(" + draftCoffeeName(pending) + ")";
        return "확인 대기 중인 " + current + "이 이미 있다 — 단일 대기 원칙상 다른 제안을 받을 수 없다. "
                + "사용자에게 먼저 [저장]이나 [취소]로 마무리해 달라고 안내해라.";
    }
}
