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
 * <p>PK가 {@code cup_id}인 것이 {@link CupEntity}와의 1:1을 표현한다 — <b>FK 제약도 {@code @OneToOne}
 * 매핑도 없다</b>(ADR-75 + {@link NoteEntity} POLICY). 값은 애플리케이션이 회차 INSERT 후 받은 id로 채운다.
 *
 * <p>수치가 {@link BigDecimal}인 것은 스키마가 {@code numeric}이기 때문이다 — 도메인
 * {@link com.devwuu.mocha.domain.Recipe}는 {@code Double}이고 변환은 변환기(TΔ3c)가 소유한다.
 * V-8(양수 <b>유한</b>)은 컬럼 CHECK가 강제하며, {@code numeric}은 {@code double}과 달리 비유한값이
 * 값으로 들어올 수 있어 CHECK가 {@code < 'Infinity'}까지 본다(TΔ2 실측).
 *
 * <p><b>changes/0030 필드 재편</b>(V7, ADR-86): 구 {@code machine} 폐기 · 구 {@code pouring} → {@code detail} ·
 * 구 TEXT {@code grind}를 {@code numeric grind} + {@code grinder}로 분리. 분쇄값이 수치 6종에 편입돼
 * {@code numeric}이 <b>7종</b>이 됐고, 그래서 위 CHECK 원형이 {@code recipe_grind_check}에도 그대로 걸린다.
 */
@Entity
@Table(name = "recipe")
public class RecipeEntity {

    // 1:1 표현이자 PK — 생성 전략이 없다(회차 id를 애플리케이션이 그대로 넣는다).
    @Id
    @Column(name = "cup_id")
    private Long cupId;

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

    // changes/0030: 구 TEXT("210클릭 (매버릭 2.0)")에서 numeric으로 재생성 — 수치 6종에 편입됐다(V7, ADR-86).
    @Column(name = "grind")
    private BigDecimal grind;

    @Column(name = "grinder")
    private String grinder;

    // changes/0030: 구 pouring의 RENAME. 폐기된 machine의 기구·머신 정보도 여기로 모인다(ADR-86 POLICY).
    @Column(name = "detail")
    private String detail;

    @Column(name = "feedback")
    private String feedback;

    protected RecipeEntity() {
        // JPA 전용.
    }

    public RecipeEntity(Long cupId, String method, BigDecimal doseG, BigDecimal waterMl, BigDecimal yieldMl,
                        BigDecimal timeSec, BigDecimal tempC, BigDecimal grind, String grinder, String detail,
                        String feedback) {
        this.cupId = cupId;
        this.method = method;
        this.doseG = doseG;
        this.waterMl = waterMl;
        this.yieldMl = yieldMl;
        this.timeSec = timeSec;
        this.tempC = tempC;
        this.grind = grind;
        this.grinder = grinder;
        this.detail = detail;
        this.feedback = feedback;
    }

    public Long getCupId() {
        return cupId;
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

    public BigDecimal getGrind() {
        return grind;
    }

    public String getGrinder() {
        return grinder;
    }

    public String getDetail() {
        return detail;
    }

    public String getFeedback() {
        return feedback;
    }
}
