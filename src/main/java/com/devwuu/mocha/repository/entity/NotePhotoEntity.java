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
 * <p>참조 축이 {@code tastingDayId}가 아니라 {@code (noteId, tastedOn)}인 이유는 시음일이 저장 때마다 통째로
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

    /**
     * 날짜 이동에 따라가는 세 값을 한 번에 옮긴다 — {@code tasted_on}·{@code seq}·{@code path}
     * (ref: changes/0029 tasks.md TΔ5b-2, delta.md#D-12 ③).
     *
     * <p><b>셋이 한 메서드인 것이 계약이다</b>: 참조 축이 {@code (note_id, tasted_on)}이고 그 안의 순서가
     * {@code seq}이며({@code UNIQUE (note_id, tasted_on, seq)}) 경로에는 날짜 세그먼트가 박혀 있다 — 하나만
     * 옮기면 셋이 서로 다른 날짜를 가리킨다. 나눠 두면 호출부가 하나를 빠뜨릴 수 있는 자리다.
     *
     * <p>{@code path}를 <b>계산하지 않고 받는</b> 것은 병합에서 이름이 바뀌기 때문이다({@code -N} 유일화) —
     * 실제로 어디로 옮겨졌는지는 파일을 옮긴 {@code PhotoStore}만 안다. 행은 계산값이 아니라 실제로 쓴
     * 경로를 든다({@code V3__0029_note_photo.sql} POLICY).
     */
    public void moveTo(LocalDate tastedOn, int seq, String path) {
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
