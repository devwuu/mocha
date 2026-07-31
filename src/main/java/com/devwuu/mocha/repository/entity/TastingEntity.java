package com.devwuu.mocha.repository.entity;

import com.devwuu.mocha.domain.Rating;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * {@code tasting} 테이블 매핑 — 회차 맛 감상 (ref: data-model.md#2.2, V-1·V-11·V-15).
 *
 * <p>PK가 {@code brew_id}인 것이 {@link BrewEntity}와의 1:1을 표현한다 — <b>FK 제약도 {@code @OneToOne}
 * 매핑도 없다</b>(ADR-75 + {@link NoteEntity} POLICY).
 *
 * <p>두 감상 컬럼이 NOT NULL인 것은 정규화가 이미 걸러 주기 때문이다: V-15가 빈 감상 tasting을 드롭하므로
 * 행이 존재하면 {@code myTaste}가 있고, V-11이 {@code myTasteOriginal}을 함께 존재하게 만든다(누락 시
 * 정규화본을 양쪽에). 도메인 {@link com.devwuu.mocha.domain.Tasting}의 컴팩트 생성자가 그 자리다.
 *
 * <p>POLICY: {@code rating}은 enum 상수명(PERFECT/GOOD/OKAY_NOT_MINE/BAD)으로 저장한다 —
 * {@link Rating#label()}의 한국어 표기는 JSON·표시 계약 전용이고, DB에 넣으면 문구 변경이 데이터
 * 마이그레이션이 된다(TΔ2). 4범주 강제(V-1)는 컬럼 CHECK가 소유한다.
 */
@Entity
@Table(name = "tasting")
public class TastingEntity {

    // 1:1 표현이자 PK — 생성 전략이 없다(회차 id를 애플리케이션이 그대로 넣는다).
    @Id
    @Column(name = "brew_id")
    private Long brewId;

    @Column(name = "my_taste", nullable = false)
    private String myTaste;

    @Column(name = "my_taste_original", nullable = false)
    private String myTasteOriginal;

    @Enumerated(EnumType.STRING)
    @Column(name = "rating", length = 32)
    private Rating rating;

    protected TastingEntity() {
        // JPA 전용.
    }

    public TastingEntity(Long brewId, String myTaste, String myTasteOriginal, Rating rating) {
        this.brewId = brewId;
        this.myTaste = myTaste;
        this.myTasteOriginal = myTasteOriginal;
        this.rating = rating;
    }

    public Long getBrewId() {
        return brewId;
    }

    public String getMyTaste() {
        return myTaste;
    }

    public String getMyTasteOriginal() {
        return myTasteOriginal;
    }

    public Rating getRating() {
        return rating;
    }
}
