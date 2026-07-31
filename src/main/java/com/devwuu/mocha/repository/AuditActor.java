package com.devwuu.mocha.repository;

/**
 * 감사 컬럼 {@code created_by}/{@code modified_by}에 들어가는 <b>변경 주체</b>
 * (ref: changes/0028-rdb-storage/delta.md#감사-컬럼, Q-5).
 *
 * <p>POLICY: 사용자 ID가 아니다 — A2에서 "에이전트가 채운 값 vs UI로 고친 값"을 가르는 축이고,
 * 사용자 ID 컬럼은 A3에서 별도로 추가한다. 값의 출처를 뜻하는 {@link com.devwuu.mocha.domain.Source}
 * (user/photo/search)와도 다른 축이다.
 *
 * <p>DB에 넣는 표기는 {@link #columnValue()}가 소유한다 — 상수명({@code AGENT})이 아니라 소문자
 * 표기({@code agent})가 스키마 규약이므로 {@code name()}을 그대로 쓰지 않는다.
 */
public enum AuditActor {

    /** 에이전트 커밋 경유 쓰기. A1의 모든 쓰기가 여기에 해당한다. */
    AGENT("agent"),

    /** 사람이 UI로 직접 고친 쓰기. A2에서 실제 값이 생긴다. */
    USER("user");

    private final String columnValue;

    AuditActor(String columnValue) {
        this.columnValue = columnValue;
    }

    public String columnValue() {
        return columnValue;
    }
}
