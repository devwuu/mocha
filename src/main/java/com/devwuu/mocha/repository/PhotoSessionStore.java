package com.devwuu.mocha.repository;

import com.devwuu.mocha.domain.PhotoSession;

import java.util.Optional;

/**
 * 사진 세션 저장소 — {@code data/photo-session.json} (ref: spec FR-10, tasks T4-2).
 * <p>텍스트보다 먼저(또는 분리) 도착한 사진의 스테이징 상태를 파일로 영속화한다. {@link PendingStore}와 달리
 * TTL로 만료 처리하지 않는다 — 그룹핑 윈도우(mocha.photo.session-window) 판정은 오케스트레이션(ConfirmationFlow)이
 * {@link PhotoSession#lastMediaAt}로 직접 하고, 저장소는 있는 그대로 읽고 쓴다.
 * <p>단일 사용자 전제(NFR-6)라 사용자당 최대 1건이며, 단일 파일로 관리한다. 구현: {@link JsonFilePhotoSessionStore}.
 */
public interface PhotoSessionStore {

    /** 세션 저장(덮어쓰기). */
    void put(String userId, PhotoSession session);

    /** 세션 조회. 없으면 빈 Optional. */
    Optional<PhotoSession> get(String userId);

    /** 세션 폐기 — 텍스트로 흡수됨 / [취소] / 윈도우 밖 abandon 정리 시. */
    void clear(String userId);
}
