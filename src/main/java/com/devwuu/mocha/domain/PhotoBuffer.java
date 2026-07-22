package com.devwuu.mocha.domain;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 사진 버퍼 — 텍스트가 아직 도착하지 않은 상태에서 스테이징된 사진을 묶어 두는 버퍼 (ref: spec FR-10, tasks T4-2).
 * <p>사진과 텍스트는 분리 수신될 수 있고, 사진이 먼저 오면 담을 pending(=draft)이 아직 없다. 그래서 그 사이의
 * 스테이징 상태를 {@code data/photo-buffer.json}에 영속화한다(재시작 생존 정신, NFR-2). 뒤이어 텍스트가
 * {@code mocha.photo.buffer-window}(기본 10분) 안에 도착하면 이 버퍼의 사진이 그 노트로 흡수되고, 윈도우 밖이면
 * 새 흐름으로 갈린다(AC-8).
 *
 * @param lastMediaAt  마지막 미디어 수신 시각 — 그룹핑 윈도우 판정 기준(Asia/Seoul).
 * @param stagedNames  {@link com.devwuu.mocha.repository.PhotoStore}에 스테이징된 파일명(수신 순). 저장 확정 시 slug/date로 이동된다.
 */
public record PhotoBuffer(
        OffsetDateTime lastMediaAt,
        List<String> stagedNames
) {
    // 다른 도메인 record(Entry·Note 등)와 같은 방어 복사 — 호출부의 가변 리스트 재사용에 안전하게(REVIEW.md §7).
    public PhotoBuffer {
        stagedNames = stagedNames == null ? List.of() : List.copyOf(stagedNames);
    }
}
