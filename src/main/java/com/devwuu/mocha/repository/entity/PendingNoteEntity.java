package com.devwuu.mocha.repository.entity;

import com.devwuu.mocha.domain.MatchInfo;
import com.devwuu.mocha.domain.PendingNote;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDate;
import java.time.OffsetDateTime;

/**
 * {@code pending_note} 테이블 매핑 — 확인 대기 노트 (ref: data-model.md#2.3, ADR-3·73).
 * <p>사용자당 최대 1건이라 {@code user_id}가 PK다(NFR-6 — A1에서 값은 단일, 멀티테넌시는 A3).
 *
 * <p>ADR-73 예외: <b>{@code draft}만 정규화하지 않고 JSONB로 둔다</b> — TTL로 소멸하는 임시 상태이고 쿼리
 * 대상이 아니며, 정규화하면 draft 전용 테이블 셋을 또 만들어야 한다. 여기서는 직렬화된 문자열을 들고 있고
 * Jackson 왕복은 저장소 구현체가 소유한다(TΔ8) — 도메인 {@link PendingNote#draft()}는 {@code Note}다.
 * 그래서 스키마가 강제하는 무결성은 컬럼 수준(mode·created_at·draft 부재)까지고, JSONB 내부 결손
 * ({@code draft.coffee_name} 공백 등)은 애플리케이션이 계속 판정한다(ADR-66).
 *
 * <p>{@code targetNoteId}·{@code matchNoteId}가 {@code Long}인 것은 스키마가 {@code bigint}이기 때문이다.
 * 도메인 {@link PendingNote.EditTarget}·{@link MatchInfo}도 TΔ6a에서 {@code Long}으로 전환돼 이제
 * 양쪽 표현이 같다 — 엔티티가 스키마 쪽에 맞춰 서 있던 동안의 간극은 소멸했다.
 */
@Entity
@Table(name = "pending_note")
public class PendingNoteEntity {

    @Id
    @Column(name = "user_id", length = 64)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 16)
    private PendingNote.Mode mode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "draft", nullable = false)
    private String draft;

    // edit 모드 한정 수정 대상(record 모드는 null) — 날짜 이동 시 옛 카드 삭제 근거(AC-39).
    @Column(name = "target_note_id")
    private Long targetNoteId;

    @Column(name = "target_date")
    private LocalDate targetDate;

    // V-10: draft 날짜 이동이 대상 노트의 기존 엔트리와 충돌하는지 — 미리보기 경고 근거. record 모드는 항상 false.
    @Column(name = "date_conflict", nullable = false)
    private boolean dateConflict;

    // record 모드 한정 신규/기존 판정(미리보기 표시용, AC-15).
    @Enumerated(EnumType.STRING)
    @Column(name = "match_type", length = 16)
    private MatchInfo.MatchType matchType;

    @Column(name = "match_note_id")
    private Long matchNoteId;

    @Column(name = "match_date")
    private LocalDate matchDate;

    @Column(name = "preview_ts", length = 32)
    private String previewTs;

    // TTL 판정 기준(mocha.pending.ttl) — 결손 시 만료 계산이 NPE로 새므로 NOT NULL(ADR-66).
    // pending은 감사 대상이 아니라 이 한 컬럼뿐이다 — 감사 컬럼 4종은 note·entry에만 둔다(Q-5).
    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    protected PendingNoteEntity() {
        // JPA 전용.
    }

    public PendingNoteEntity(String userId, PendingNote.Mode mode, String draft, Long targetNoteId,
                             LocalDate targetDate, boolean dateConflict, MatchInfo.MatchType matchType,
                             Long matchNoteId, LocalDate matchDate, String previewTs, OffsetDateTime createdAt) {
        this.userId = userId;
        this.mode = mode;
        this.draft = draft;
        this.targetNoteId = targetNoteId;
        this.targetDate = targetDate;
        this.dateConflict = dateConflict;
        this.matchType = matchType;
        this.matchNoteId = matchNoteId;
        this.matchDate = matchDate;
        this.previewTs = previewTs;
        this.createdAt = createdAt;
    }

    public String getUserId() {
        return userId;
    }

    public PendingNote.Mode getMode() {
        return mode;
    }

    public String getDraft() {
        return draft;
    }

    public Long getTargetNoteId() {
        return targetNoteId;
    }

    public LocalDate getTargetDate() {
        return targetDate;
    }

    public boolean isDateConflict() {
        return dateConflict;
    }

    public MatchInfo.MatchType getMatchType() {
        return matchType;
    }

    public Long getMatchNoteId() {
        return matchNoteId;
    }

    public LocalDate getMatchDate() {
        return matchDate;
    }

    public String getPreviewTs() {
        return previewTs;
    }

    public OffsetDateTime getCreatedAt() {
        return createdAt;
    }
}
