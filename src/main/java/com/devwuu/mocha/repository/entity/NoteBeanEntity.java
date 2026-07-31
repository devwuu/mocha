package com.devwuu.mocha.repository.entity;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * {@code note_bean} 테이블 매핑 — 원두 1종 (ref: data-model.md#2.1 beans, V-14, ADR-53).
 * <p>출처 우선순위(V-6)가 요소 서브필드 단위로 적용되므로 description·process가 각각 {@link SourcedValue}다.
 *
 * <p>{@code noteId}는 연관 매핑이 아니라 평범한 컬럼이다 — 스키마에 FK가 없고(ADR-74) 엔티티에도
 * 연관을 두지 않는다({@link NoteEntity} POLICY). 부모 조회·조립은 QueryDSL 질의가 소유한다.
 */
@Entity
@Table(name = "note_bean")
public class NoteBeanEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "note_id", nullable = false)
    private Long noteId;

    @Column(name = "seq", nullable = false)
    private int seq;

    // V-14 정규화가 description.value의 비어 있지 않음을 보장한다 → NOT NULL.
    @Embedded
    @AttributeOverride(name = "value", column = @Column(name = "description", nullable = false))
    @AttributeOverride(name = "source", column = @Column(name = "description_source", length = 16))
    private SourcedValue description;

    @Embedded
    @AttributeOverride(name = "value", column = @Column(name = "process"))
    @AttributeOverride(name = "source", column = @Column(name = "process_source", length = 16))
    private SourcedValue process;

    protected NoteBeanEntity() {
        // JPA 전용.
    }

    public NoteBeanEntity(Long noteId, int seq, SourcedValue description, SourcedValue process) {
        this.noteId = noteId;
        this.seq = seq;
        this.description = description;
        this.process = process;
    }

    public Long getId() {
        return id;
    }

    public Long getNoteId() {
        return noteId;
    }

    public int getSeq() {
        return seq;
    }

    public SourcedValue getDescription() {
        return description;
    }

    public SourcedValue getProcess() {
        return process;
    }
}
