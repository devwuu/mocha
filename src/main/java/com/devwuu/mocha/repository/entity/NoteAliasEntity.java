package com.devwuu.mocha.repository.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * {@code note_alias} 테이블 매핑 — 내부 매칭·검색 전용 별칭 1건
 * (ref: data-model.md#2.1 aliases, V-13, ADR-37).
 * <p>{@code alias}는 첫 등장 표시 형태(보존 대상), {@code normalized}는 대조·중복 제거 기준이다 —
 * 정규화는 저장값이 아니라 <i>기준</i>이므로 둘을 함께 갖는다. 중복 제거 자체는 스키마의
 * {@code UNIQUE(note_id, kind, normalized)}가 강제한다.
 *
 * <p>{@code noteId}는 연관 매핑이 아니라 평범한 컬럼이다 — 스키마에 FK가 없고(ADR-75) 엔티티에도
 * 연관을 두지 않는다({@link NoteEntity} POLICY). 부모 조회·조립은 QueryDSL 질의가 소유한다.
 */
@Entity
@Table(name = "note_alias")
public class NoteAliasEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "note_id", nullable = false)
    private Long noteId;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false, length = 16)
    private AliasKind kind;

    @Column(name = "alias", nullable = false)
    private String alias;

    @Column(name = "normalized", nullable = false)
    private String normalized;

    protected NoteAliasEntity() {
        // JPA 전용.
    }

    public NoteAliasEntity(Long noteId, AliasKind kind, String alias, String normalized) {
        this.noteId = noteId;
        this.kind = kind;
        this.alias = alias;
        this.normalized = normalized;
    }

    public Long getId() {
        return id;
    }

    public Long getNoteId() {
        return noteId;
    }

    public AliasKind getKind() {
        return kind;
    }

    public String getAlias() {
        return alias;
    }

    public String getNormalized() {
        return normalized;
    }
}
