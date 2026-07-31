package com.devwuu.mocha.repository.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * {@code brew} 테이블 매핑 — 회차 1개(한 번 내려서 마신 단위) (ref: data-model.md#2.2, ADR-59).
 *
 * <p>AC-Δ4: <b>회차 번호를 {@code seq} 컬럼이 소유한다</b> — 구 JSON의 "배열 순서 = 회차 번호"라는 암묵
 * 순서 의존이 여기서 사라진다. 대신 <b>정렬은 질의가 건다</b>({@code seq} 오름차순): 엔티티에 연관 매핑이
 * 없으므로({@link NoteEntity} POLICY) {@code @OrderBy}를 쓸 자리가 없고, 조회 순서가 삽입 순서에 의존하지
 * 않아야 한다는 요구는 질의 수준에서 닫힌다.
 *
 * <p>레시피·감상은 이 테이블에 없다 — {@code recipe}·{@code tasting}이 {@code brew_id}를 PK로 가지는 것으로
 * 1:1 짝이 표현된다({@link RecipeEntity}·{@link TastingEntity}). 도메인 {@link com.devwuu.mocha.domain.Brew}가
 * 두 값을 직접 품는 것과 달리, 영속 형태에서는 회차가 <b>식별자만 가진 행</b>이 된다.
 *
 * <p>{@code entryId}는 연관 매핑이 아니라 평범한 컬럼이다(ADR-75 — FK 없음).
 */
@Entity
@Table(name = "brew")
public class BrewEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "entry_id", nullable = false)
    private Long entryId;

    @Column(name = "seq", nullable = false)
    private int seq;

    protected BrewEntity() {
        // JPA 전용.
    }

    public BrewEntity(Long entryId, int seq) {
        this.entryId = entryId;
        this.seq = seq;
    }

    public Long getId() {
        return id;
    }

    public Long getEntryId() {
        return entryId;
    }

    public int getSeq() {
        return seq;
    }
}
