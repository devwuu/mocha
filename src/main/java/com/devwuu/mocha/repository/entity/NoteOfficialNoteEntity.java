package com.devwuu.mocha.repository.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * {@code note_official_note} 테이블 매핑 — 로스터리 전시 테이스팅 노트 1건
 * (ref: data-model.md#2.1 official_notes, FR-7).
 * <p>값만 갖는다 — source는 배열 전체에 하나이므로 {@link NoteEntity#getOfficialNotesSource()}가 소유한다(Q-8).
 * 그래서 여기에는 {@link SourcedValue}가 없다.
 *
 * <p>{@code noteId}는 연관 매핑이 아니라 평범한 컬럼이다 — 스키마에 FK가 없고(ADR-75) 엔티티에도
 * 연관을 두지 않는다({@link NoteEntity} POLICY). 부모 조회·조립은 QueryDSL 질의가 소유한다.
 */
@Entity
@Table(name = "note_official_note")
public class NoteOfficialNoteEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "note_id", nullable = false)
    private Long noteId;

    @Column(name = "seq", nullable = false)
    private int seq;

    @Column(name = "value", nullable = false)
    private String value;

    protected NoteOfficialNoteEntity() {
        // JPA 전용.
    }

    public NoteOfficialNoteEntity(Long noteId, int seq, String value) {
        this.noteId = noteId;
        this.seq = seq;
        this.value = value;
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

    public String getValue() {
        return value;
    }
}
