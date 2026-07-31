package com.devwuu.mocha.repository.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * {@code recipe} 테이블 매핑 — 회차 추출 레시피 (ref: data-model.md#2.2, FR-18, V-8).
 * <p>방식별 분기 없는 flat 스키마이고 전 필드 nullable이다. 사용자 발화 전용이라 {@code source} 개념이
 * 없어 {@link SourcedValue}가 하나도 없다(ADR-22·59 승계).
 *
 * <p>PK가 {@code brew_id}인 것이 {@link BrewEntity}와의 1:1을 표현한다 — <b>FK 제약도 {@code @OneToOne}
 * 매핑도 없다</b>(ADR-75 + {@link NoteEntity} POLICY). 값은 애플리케이션이 회차 INSERT 후 받은 id로 채운다.
 *
 * <p>수치가 {@link BigDecimal}인 것은 스키마가 {@code numeric}이기 때문이다 — 도메인
 * {@link com.devwuu.mocha.domain.Recipe}는 {@code Double}이고 변환은 변환기(TΔ3c)가 소유한다.
 * V-8(양수 <b>유한</b>)은 컬럼 CHECK가 강제하며, {@code numeric}은 {@code double}과 달리 비유한값이
 * 값으로 들어올 수 있어 CHECK가 {@code < 'Infinity'}까지 본다(TΔ2 실측).
 */
@Entity
@Table(name = "recipe")
public class RecipeEntity {

    // 1:1 표현이자 PK — 생성 전략이 없다(회차 id를 애플리케이션이 그대로 넣는다).
    @Id
    @Column(name = "brew_id")
    private Long brewId;

    @Column(name = "method")
    private String method;

    @Column(name = "dose_g")
    private BigDecimal doseG;

    @Column(name = "water_ml")
    private BigDecimal waterMl;

    @Column(name = "yield_ml")
    private BigDecimal yieldMl;

    @Column(name = "time_sec")
    private BigDecimal timeSec;

    @Column(name = "temp_c")
    private BigDecimal tempC;

    @Column(name = "grind")
    private String grind;

    @Column(name = "machine")
    private String machine;

    @Column(name = "pouring")
    private String pouring;

    @Column(name = "feedback")
    private String feedback;

    protected RecipeEntity() {
        // JPA 전용.
    }

    public RecipeEntity(Long brewId, String method, BigDecimal doseG, BigDecimal waterMl, BigDecimal yieldMl,
                        BigDecimal timeSec, BigDecimal tempC, String grind, String machine, String pouring,
                        String feedback) {
        this.brewId = brewId;
        this.method = method;
        this.doseG = doseG;
        this.waterMl = waterMl;
        this.yieldMl = yieldMl;
        this.timeSec = timeSec;
        this.tempC = tempC;
        this.grind = grind;
        this.machine = machine;
        this.pouring = pouring;
        this.feedback = feedback;
    }

    public Long getBrewId() {
        return brewId;
    }

    public String getMethod() {
        return method;
    }

    public BigDecimal getDoseG() {
        return doseG;
    }

    public BigDecimal getWaterMl() {
        return waterMl;
    }

    public BigDecimal getYieldMl() {
        return yieldMl;
    }

    public BigDecimal getTimeSec() {
        return timeSec;
    }

    public BigDecimal getTempC() {
        return tempC;
    }

    public String getGrind() {
        return grind;
    }

    public String getMachine() {
        return machine;
    }

    public String getPouring() {
        return pouring;
    }

    public String getFeedback() {
        return feedback;
    }
}
