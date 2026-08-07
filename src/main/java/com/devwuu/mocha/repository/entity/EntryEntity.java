package com.devwuu.mocha.repository.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDate;

/**
 * {@code entry} 테이블 매핑 — 날짜별 시음 기록 = 버전 (ref: data-model.md#2.2, FR-15).
 * <p>{@code tasted_on}은 {@code date} 타입이라 형식 위반(V-3)을 DB가 거르고, {@code UNIQUE(note_id, tasted_on)}이
 * "노트 안에서 날짜가 유일 키"(V-10)를 제약으로 강제한다 — 구현체가 매번 검사하던 규칙이 스키마로 내려간 자리다.
 *
 * <p>{@code noteId}는 연관 매핑이 아니라 평범한 컬럼이다 — 스키마에 FK가 없고(ADR-75) 엔티티에도
 * 연관을 두지 않는다({@link NoteEntity} POLICY). 부모 조회·조립은 QueryDSL 질의가 소유한다.
 *
 * <p>수정 메서드는 <b>{@code tasted_on} 하나</b>다. 같은 날 재저장은 여전히 <b>통째 교체</b>가 정책이지만
 * (ref: data-model.md#2.2 POLICY, ADR-4·59) 수정 세션의 <b>날짜 이동</b>(TΔ5c, V-10)은 교체가 아니라
 * 이동이다 — 같은 기록이 다른 날짜에 놓이는 것이라 행이 살아남아야 {@code created_at}이 보존되고
 * {@code modified_at}이 실제 수정 시각으로 움직인다. TΔ3b가 <i>"날짜 이동이 갱신을 요구하면 그때
 * 추가한다"</i>로 남겨 둔 자리다(루트 CLAUDE.md §4).
 */
@Entity
@Table(name = "entry")
public class EntryEntity extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "note_id", nullable = false)
    private Long noteId;

    // V-3: 시각은 수집하지 않는다(Q-1) — 도메인 Entry.date(LocalDate)와 같은 정밀도다.
    @Column(name = "tasted_on", nullable = false)
    private LocalDate tastedOn;

    // 감사 컬럼 4종(Q-5)은 BaseEntity가 소유한다 — modifiedAt이 도메인 Entry.updatedAt을 겸한다.

    // 회차 컬렉션은 여기 없다 — cup는 entry_id로 각자 서 있고, 조립과 seq 오름차순 정렬은 질의가 소유한다.

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

    /**
     * 날짜 이동 (V-10, FR-21) — 이동처가 비어 있어야 {@code UNIQUE(note_id, tasted_on)}를 지난다.
     * 비우는 책임은 호출부({@code NoteTxService})가 진다.
     */
    public void updateTastedOn(LocalDate tastedOn) {
        this.tastedOn = tastedOn;
    }
}
