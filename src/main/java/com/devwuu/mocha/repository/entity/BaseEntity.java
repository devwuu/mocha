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
 * 감사 컬럼 4종의 공통 상위 타입 (ref: changes/0028-rdb-storage/delta.md#감사-컬럼, Q-5).
 *
 * <p>POLICY: {@code _by}는 사용자 ID가 아니라 <b>변경 주체</b>({@code agent} / {@code user})다 —
 * A2에서 "에이전트가 채운 값 vs UI로 고친 값"을 가르는 데 쓴다. 값의 출처를 뜻하는 도메인
 * {@code Source}(user/photo/search)와는 다른 축이고, 사용자 ID 컬럼은 A3에서 별도로 추가한다.
 *
 * <p>POLICY: 감사 컬럼 4종이 <b>도메인의 {@code createdAt}/{@code updatedAt}을 겸한다</b> —
 * delta §감사 컬럼이 4종만 규정하므로 같은 의미의 타임스탬프를 두 벌 두지 않는다. 역방향 변환에서
 * {@code created_at} → {@code createdAt} · {@code modified_at} → {@code updatedAt}으로 되돌아온다
 * (ref: NoteEntityMapper).
 *
 * <p>값 주입은 Spring Data Auditing이 전담하고 변환기·저장소는 이 필드를 쓰지 않는다 — 그래서
 * setter가 없다. 주체·시각의 공급자는 {@code JpaAuditingConfig}가 소유한다.
 *
 * <p>적용 대상은 {@code note}·{@code entry} 두 테이블뿐이다(delta §감사 컬럼). 하위(brew·recipe·
 * tasting·배열 테이블)는 부모를 통해 추적하며, 회차 단위 감사 요구가 실제로 관측되면 그때 확장한다
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
