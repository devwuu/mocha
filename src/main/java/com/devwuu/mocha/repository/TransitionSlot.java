package com.devwuu.mocha.repository;

import java.util.Optional;

/**
 * 전환 대기 공통 슬롯 — 직전 파이프라인 산출을 짧게 붙잡아 다음 의도로 잇는 단일 메커니즘
 * (ref: plan.md §3, #ADR-26; data-model.md#2.5; spec FR-14).
 * <p>POLICY: 전환 게이트는 공통 TransitionSlot 재사용 — 사용처별 상태 신설 금지 (ref: plan.md#ADR-26).
 * <p>POLICY: 전환 슬롯은 메모리 전용 — {@code data/} 아래 어떤 파일도 만들지 않고 재시작 시 소멸한다
 * (ref: spec NFR-2 예외, AC-35; plan.md#ADR-25 POLICY).
 * <p>단일 슬롯(사용자 개념 없음 — 단일 사용자 NFR-6)이라 사용처가 여럿이어도 상태는 하나다.
 * payload 타입은 사용처마다 다르므로 소비 측이 {@code instanceof} 패턴 매칭으로 판별한다
 * (첫 사용처: 과거 참조 매치 실패 분기의 추출 결과 보관 — changes/0011 TΔ6).
 */
public interface TransitionSlot {

    /** 직전 산출을 보관한다 — 단일 슬롯이라 기존 보관분은 교체된다. */
    void hold(Object payload);

    /**
     * 보관분을 소비한다(꺼내면서 비움). TTL(10분, 코드 상수) 만료분은 빈 Optional로 수렴한다
     * (만료 판정은 다음 수신 시점 — pending·검색 세션과 동일 패턴).
     */
    Optional<Object> take();
}
