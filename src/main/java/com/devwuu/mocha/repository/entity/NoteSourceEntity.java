package com.devwuu.mocha.repository.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * {@code note_source} 테이블 매핑 — 검색 참조 링크 1건
 * (ref: data-model.md#2.1 sources, FR-12 — 동일성 가드를 통과한 출처만 쌓인다).
 *
 * <p>테이블명이 {@code note_source}지 도메인 {@link com.devwuu.mocha.domain.Source}(출처 종류 enum)와는
 * 무관하다 — 이쪽은 참조 URL이다.
 * <p>{@code noteId}는 연관 매핑이 아니라 평범한 컬럼이다 — 스키마에 FK가 없고(ADR-74) 엔티티에도
 * 연관을 두지 않는다({@link NoteEntity} POLICY). 부모 조회·조립은 QueryDSL 질의가 소유한다.
 */
@Entity
@Table(name = "note_source")
public class NoteSourceEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "note_id", nullable = false)
    private Long noteId;

    @Column(name = "seq", nullable = false)
    private int seq;

    @Column(name = "url", nullable = false)
    private String url;

    protected NoteSourceEntity() {
        // JPA 전용.
    }

    public NoteSourceEntity(Long noteId, int seq, String url) {
        this.noteId = noteId;
        this.seq = seq;
        this.url = url;
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

    public String getUrl() {
        return url;
    }
}
