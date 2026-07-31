package com.devwuu.mocha.repository.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * {@code entry} 테이블 매핑 — 날짜별 시음 기록 = 버전 (ref: data-model.md#2.2, FR-15).
 * <p>{@code tasted_on}은 {@code date} 타입이라 형식 위반(V-3)을 DB가 거르고, {@code UNIQUE(note_id, tasted_on)}이
 * "노트 안에서 날짜가 유일 키"(V-10)를 제약으로 강제한다 — 구현체가 매번 검사하던 규칙이 스키마로 내려간 자리다.
 *
 * <p>{@code noteId}는 연관 매핑이 아니라 평범한 컬럼이다 — 스키마에 FK가 없고(ADR-74) 엔티티에도
 * 연관을 두지 않는다({@link NoteEntity} POLICY). 부모 조회·조립은 QueryDSL 질의가 소유한다.
 *
 * <p>필드 단위 수정 메서드를 두지 않는다 — 엔트리는 같은 날 재저장 시 <b>통째 교체</b>가 정책이고
 * (ref: data-model.md#2.2 POLICY, ADR-4·59) 회차 단위 서버 병합이 없다. 날짜 이동(TΔ5c)이 갱신을 요구하면
 * 그때 추가한다(루트 CLAUDE.md §4).
 */
@Entity
@Table(name = "entry")
public class EntryEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "note_id", nullable = false)
    private Long noteId;

    // V-3: 시각은 수집하지 않는다(Q-1) — 도메인 Entry.date(LocalDate)와 같은 정밀도다.
    @Column(name = "tasted_on", nullable = false)
    private LocalDate tastedOn;

    // Q-5: 감사 컬럼 4종이 도메인 Entry.updatedAt을 겸한다(modifiedAt) — 같은 의미의 타임스탬프를 두 벌 두지
    // 않는다. 값 주입은 Spring Data Auditing이 맡는다(TΔ4).
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "created_by", nullable = false, length = 16)
    private String createdBy;

    @Column(name = "modified_at", nullable = false)
    private OffsetDateTime modifiedAt;

    @Column(name = "modified_by", nullable = false, length = 16)
    private String modifiedBy;

    // 회차 컬렉션은 여기 없다 — brew는 entry_id로 각자 서 있고, 조립과 seq 오름차순 정렬은 질의가 소유한다.

    protected EntryEntity() {
        // JPA 전용.
    }

    public EntryEntity(Long noteId, LocalDate tastedOn) {
        this.noteId = noteId;
        this.tastedOn = tastedOn;
    }

    public Long getId() {
        return id;
    }

    public Long getNoteId() {
        return noteId;
    }

    public LocalDate getTastedOn() {
        return tastedOn;
    }

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
