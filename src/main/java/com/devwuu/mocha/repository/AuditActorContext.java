package com.devwuu.mocha.repository;

/**
 * 현재 실행 흐름의 {@link AuditActor}를 나르는 컨텍스트 — 감사 컬럼의 주체 공급원이다
 * (ref: changes/0028-rdb-storage/tasks.md TΔ4).
 *
 * <p>POLICY: A1에서 값은 사실상 {@link AuditActor#AGENT} 단일이지만(모든 쓰기가 에이전트 커밋 경유),
 * <b>구현은 주체를 실제로 판별하게 둔다</b> — A2에서 UI 수정 경로가 {@link AuditActor#USER}를 채운다.
 * 상수를 반환하는 {@code AuditorAware}로 두면 A2가 감사 축 전체를 다시 설계해야 한다.
 *
 * <p>판별 매체로 ThreadLocal을 쓰는 것은 이 프로젝트가 단일 프로세스이고 쓰기가 요청 스레드 안에서
 * 동기적으로 끝나기 때문이다(백엔드 CLAUDE.md §2 "단발성 우선"). 기본값이 {@code AGENT}라 A1 경로는
 * 아무것도 설정하지 않아도 맞는 값이 들어가고, 전환이 필요한 쪽만 {@link #runAs}로 감싼다.
 *
 * <p><b>제약: 쓰기가 요청 스레드를 벗어나면 이 컨텍스트는 따라가지 않는다.</b> {@code @Async}·
 * {@code CompletableFuture}·병렬 스트림·스케줄러/배치처럼 다른 스레드에서 저장이 일어나면 주체가
 * 조용히 기본값({@code AGENT})으로 떨어진다 — 예외가 아니라 <b>잘못된 값이 소리 없이 기록되는</b>
 * 형태라 사후에 알아채기 어렵다. 그런 경로를 만들 때는 전파 수단(실행자 데코레이터로 값을 넘기거나,
 * 주체를 인자로 명시하거나)을 <b>함께</b> 정한다. 인스턴스를 늘리는 수평 확장은 이 제약과 무관하다 —
 * 주체는 인스턴스 간 공유가 필요한 상태가 아니라 요청 스코프 값이다.
 *
 * <p>이 제약은 ThreadLocal을 골라서 생긴 것이 아니다 — {@code SecurityContextHolder}도 같은 매체라
 * A2에서 인증을 붙여 그쪽을 읽게 바꿔도 위험 축은 그대로다. 구조적으로 푸는 것은 {@code ScopedValue}
 * 계열인데 Java 21에서는 preview라 지금 도입하지 않는다(루트 CLAUDE.md §4).
 */
public final class AuditActorContext {

    private static final ThreadLocal<AuditActor> CURRENT = ThreadLocal.withInitial(() -> AuditActor.AGENT);

    private AuditActorContext() {
    }

    public static AuditActor current() {
        return CURRENT.get();
    }

    /**
     * {@code action} 실행 동안만 주체를 {@code actor}로 바꾼다.
     * <p>이전 값을 복원하는 것은 스레드가 재사용되기 때문이다 — {@code remove()}로 끝내면 중첩 호출에서
     * 바깥 주체가 기본값으로 무너진다.
     */
    public static void runAs(AuditActor actor, Runnable action) {
        AuditActor previous = CURRENT.get();
        CURRENT.set(actor);
        try {
            action.run();
        } finally {
            CURRENT.set(previous);
        }
    }
}
