package com.devwuu.mocha.repository.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;

/**
 * {@code note_photo} 테이블 매핑 — 노트의 어느 날 시음에 딸린 사진 1장
 * (ref: changes/0029 tasks.md TΔ8b, delta.md#D-5, plan.md#ADR-79).
 *
 * <p><b>사진 바이트가 아니라 경로다.</b> 원본은 {@code data/photos/} 아래 파일로 남고 이 행은 그것을
 * 노트에 잇는 색인이다 — 갤러리가 "이 노트의 사진"을 질의로 답할 수 있게 하는 것이 존재 이유다.
 *
 * <p>참조 축이 {@code entryId}가 아니라 {@code (noteId, tastedOn)}인 이유는 엔트리가 저장 때마다 통째로
 * 교체되기 때문이다(ADR-4·59) — 자세한 근거는 마이그레이션 {@code V3__0029_note_photo.sql}의 POLICY가
 * 소유한다.
 *
 * <p>{@code noteId}는 연관 매핑이 아니라 평범한 컬럼이다 — 스키마에 FK가 없고(ADR-75) 엔티티에도 연관을
 * 두지 않는다({@link NoteEntity} POLICY). 삭제 전파는 {@code NoteEntityRepositoryCustom#deleteNote}가 진다.
 */
@Entity
@Table(name = "note_photo")
public class NotePhotoEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "note_id", nullable = false)
    private Long noteId;

    @Column(name = "tasted_on", nullable = false)
    private LocalDate tastedOn;

    @Column(name = "seq", nullable = false)
    private int seq;

    @Column(name = "path", nullable = false)
    private String path;

    protected NotePhotoEntity() {
        // JPA 전용.
    }

    public NotePhotoEntity(Long noteId, LocalDate tastedOn, int seq, String path) {
        this.noteId = noteId;
        this.tastedOn = tastedOn;
        this.seq = seq;
        this.path = path;
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

    public int getSeq() {
        return seq;
    }

    public String getPath() {
        return path;
    }
}
