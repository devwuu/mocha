package com.devwuu.mocha.repository;

import com.devwuu.mocha.domain.PendingNote;

import java.util.Optional;

/**
 * 확인 대기 노트(pending) 저장소 — {@code data/pending.json} (ref: plan.md#ADR-3, data-model.md#2.3).
 * <p>재시작 생존(NFR-2, AC-7)을 위해 파일로 영속화한다. 단일 사용자 전제(NFR-6)라 사용자당 최대 1건이며,
 * {@code userId}는 인터페이스 계약(plan §3)을 따르되 저장을 분할하지 않는다 — 워크스페이스 격리가 경계다.
 * <p>구현: {@link JsonFilePendingStore}.
 */
public interface PendingStore {

    /** pending 저장(덮어쓰기). {@code createdAt}은 호출자가 채운 값을 그대로 TTL 기준으로 보존한다. */
    void put(String userId, PendingNote pending);

    /**
     * pending 조회. 없거나 TTL(mocha.pending.ttl) 초과면 빈 Optional (ref: data-model.md#V-7).
     * <p>만료된 pending은 유효한 대기로 취급하지 않는다 — [저장] 액션이 거부되어 만료 안내로 수렴(T3-5).
     */
    Optional<PendingNote> get(String userId);

    /** pending 폐기 — [저장] 커밋 후 / [취소] / TTL 만료 정리 시. */
    void clear(String userId);
}
