package com.devwuu.mocha.agent.tool.validation;

import com.devwuu.mocha.domain.Aliases;
import com.devwuu.mocha.domain.PendingNote;
import com.devwuu.mocha.domain.Sourced;

import java.time.LocalDate;

/**
 * 단일 대기 게이트 패밀리(FR-22/AC-30) — 확인 대기(pending) 중 다른 대상의 제안을 거부하고, 같은
 * 대상의 재호출만 FR-5 갱신 경로로 통과시킨다. record·edit 진입점이 거부 문안을 공유한다
 * (ref: specs/coffee-note-agent/plan.md#ADR-45·ADR-64 — 추출은 배치 변경, 판정·문안 불변).
 * <p>예외 — 훼손 pending(draft·target·coffee_name 누락) null 가드는 추출 시점(0024 리뷰 반영)에 추가된
 * 신규 판정 동작이다: 종전 NPE→일반 tool 오류 대신 사유 있는 거부("대상 미상")로 수렴한다.
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
                && pending.draft() != null
                && sameNormalized(proposedCoffeeName, draftCoffeeName(pending))
                && roasteryCompatible(proposedRoastery, pending.draft().roastery())) {
            return; // FR-5: 대기 중 수정 발화 = 같은 커피의 propose_record 재호출 → 갱신 경로.
        }
        throw new RejectedException(pendingBlocksReason(pending));
    }

    // POLICY: 단일 대기 원칙 준용(record든 edit든) — 확인 대기 중 다른 대상의 수정 세션은 거부한다.
    //         같은 대상(slug+date)의 propose_edit 재호출만 FR-5 후속 수정으로 통과시킨다
    //         (ref: plan.md#ADR-45, 구 수정 진입 거부의 승계).
    static void requireSameEditTargetOrFree(PendingNote pending, String slug, LocalDate targetDate) {
        if (pending == null) {
            return;
        }
        if (pending.mode() == PendingNote.Mode.EDIT
                && pending.target() != null
                && pending.target().slug().equals(slug)
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

    // 훼손 pending(draft·coffee_name 누락) null 가드 — 판정·사유 조립 양쪽에서 NPE 대신 거부로 수렴시킨다
    // (ADR-45 POLICY, 0024 리뷰 반영 — edit의 target null 가드와 대칭).
    private static String draftCoffeeName(PendingNote pending) {
        if (pending.draft() == null || pending.draft().coffeeName() == null) {
            return null;
        }
        return pending.draft().coffeeName().value();
    }

    private static String pendingBlocksReason(PendingNote pending) {
        // target·draft null 가드 — 역직렬화된 pending에 target(mode=edit)이나 draft(mode=record)가 빠진
        // 비정상 상태(위 통과 판정이 이미 대비하는 상태)에서도 NPE가 아니라 사유 있는 거부로 수렴한다
        // (ADR-45 POLICY, 0024 리뷰 반영).
        String draftCoffeeName = draftCoffeeName(pending);
        String current = pending.mode() == PendingNote.Mode.EDIT
                ? "수정 세션(" + (pending.target() == null ? "대상 미상" : pending.target().slug()) + ")"
                : "새 기록(" + (draftCoffeeName == null ? "대상 미상" : draftCoffeeName) + ")";
        return "확인 대기 중인 " + current + "이 이미 있다 — 단일 대기 원칙상 다른 제안을 받을 수 없다. "
                + "사용자에게 먼저 [저장]이나 [취소]로 마무리해 달라고 안내해라.";
    }
}
