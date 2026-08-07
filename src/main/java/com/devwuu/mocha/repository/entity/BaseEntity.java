package com.devwuu.mocha.repository.entity;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.OffsetDateTime;

/**
 * 감사 컬럼 4종의 공통 상위 타입
 * (ref: changes/0028-rdb-storage/delta.md#감사-컬럼, changes/0029-app-interface/delta.md#D-13).
 *
 * <p>POLICY: {@code _by}는 <b>이 행을 쓴 사용자</b>다 — 1인용이라 지금은 {@code SingleUser.ID} 한 값이고
 * A3에서 인증 주체가 그 자리를 대체한다. <b>구 정의(«변경 주체» {@code agent}/{@code user})는 A2에서
 * 폐기됐다</b>(D-13): 그 축이 답하려던 물음을 값의 출처인 {@code Source}(user/photo/search)가 필드
 * 단위로 이미 답하고, 모든 쓰기가 사용자 확정을 거쳐(ADR-3) 행 단위 판정이 성립하지 않았다.
 * <b>기존 행의 {@code agent} 값은 그대로 둔다</b>(사용자 확정 2026-08-01) — 축이 바뀌기 전에 쓰인
 * 행이라는 이력으로 읽는다.
 *
 * <p>POLICY: 감사 컬럼 4종이 <b>도메인의 {@code createdAt}/{@code updatedAt}을 겸한다</b> —
 * delta §감사 컬럼이 4종만 규정하므로 같은 의미의 타임스탬프를 두 벌 두지 않는다. 역방향 변환에서
 * {@code created_at} → {@code createdAt} · {@code modified_at} → {@code updatedAt}으로 되돌아온다
 * (ref: NoteEntityMapper).
 *
 * <p>값 주입은 Spring Data Auditing이 전담하고 변환기·저장소는 이 필드를 쓰지 않는다 — 그래서
 * setter가 없다. 주체·시각의 공급자는 {@code JpaAuditingConfig}가 소유한다.
 *
 * <p>적용 대상은 {@code note}·{@code tasting_day} 두 테이블뿐이다(delta §감사 컬럼). 하위(cup·recipe·
 * review·배열 테이블)는 부모를 통해 추적하며, 회차 단위 감사 요구가 실제로 관측되면 그때 확장한다
 * (루트 CLAUDE.md §4).
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity {

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @CreatedBy
    @Column(name = "created_by", nullable = false, updatable = false, length = 16)
    private String createdBy;

    @LastModifiedDate
    @Column(name = "modified_at", nullable = false)
    private OffsetDateTime modifiedAt;

    @LastModifiedBy
    @Column(name = "modified_by", nullable = false, length = 16)
    private String modifiedBy;

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public OffsetDateTime getModifiedAt() {
        return modifiedAt;
    }

    public String getModifiedBy() {
        return modifiedBy;
    }
}
