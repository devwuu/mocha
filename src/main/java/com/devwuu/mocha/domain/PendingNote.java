package com.devwuu.mocha.domain;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Arrays;

/**
 * 확인 대기 노트 — {@code data/pending.json} (ref: data-model.md#2.3, ADR-3).
 * <p>단일 사용자 전제(NFR-6)로 사용자당 최대 1건 — 신규 기록·수정 세션이 이 슬롯을 공유한다(FR-17).
 *
 * @param mode         신규 기록 확인 대기({@code record}) vs 저장된 노트 수정 세션({@code edit}) — FR-21, changes/0012.
 * @param draft        Note(entries 포함)와 동일 구조의 작성 중 데이터. edit 모드에서는 대상 노트+엔트리를 로드한 사본.
 * @param target       edit 모드 한정 — 원본 노트·엔트리 참조([저장] 시 갱신 대상). record 모드에서는 null.
 * @param dateConflict edit 모드 한정 — 날짜 이동이 대상 노트의 기존 엔트리와 충돌하는지(revise 시점 재계산).
 *                     미리보기 덮어쓰기 경고 표기의 근거로, 파일 영속돼 재시작 후에도 [저장]/[취소]까지 유지된다
 *                     (V-10, changes/0012 TΔ5). record 모드에서는 항상 false.
 * @param match        신규/기존 판정 결과(미리보기 표시용). record 모드 한정.
 * @param previewTs    미리보기 Slack 메시지 timestamp — 수정 시 edit 대상.
 * @param createdAt    TTL 판정 기준(mocha.pending.ttl).
 */
public record PendingNote(
        Mode mode,
        Note draft,
        EditTarget target,
        boolean dateConflict,
        MatchInfo match,
        String previewTs,
        OffsetDateTime createdAt
) {

    /** record 모드(신규 기록) 편의 생성자 — 0012 이전 호출부 형태 유지. */
    public PendingNote(Note draft, MatchInfo match, String previewTs, OffsetDateTime createdAt) {
        this(Mode.RECORD, draft, null, false, match, previewTs, createdAt);
    }

    /** edit 모드 편의 생성자 — 충돌 플래그는 revise 시점에 재계산되므로 진입 시점엔 항상 false다(TΔ4 호출부 형태 유지). */
    public PendingNote(Mode mode, Note draft, EditTarget target, MatchInfo match, String previewTs, OffsetDateTime createdAt) {
        this(mode, draft, target, false, match, previewTs, createdAt);
    }

    /** draft만 교체한 사본 — mode·target·경고 플래그 등 나머지 필드를 떨구지 않고 보존한다(revise·사진 첨부용). */
    public PendingNote withDraft(Note revisedDraft) {
        return new PendingNote(mode, revisedDraft, target, dateConflict, match, previewTs, createdAt);
    }

    /** 충돌 플래그만 교체한 사본 — revise마다 현 draft 기준으로 재계산해 싣는다(V-10, changes/0012 TΔ5). */
    public PendingNote withDateConflict(boolean conflict) {
        return new PendingNote(mode, draft, target, conflict, match, previewTs, createdAt);
    }

    /** 매칭 표기만 교체한 사본 — record 모드 revise로 시음 날짜가 바뀌면 대상 날짜를 재판정해 싣는다(ADR-39, AC-56). */
    public PendingNote withMatch(MatchInfo revisedMatch) {
        return new PendingNote(mode, draft, target, dateConflict, revisedMatch, previewTs, createdAt);
    }

    /** 세션 종류 — {@code "record"} | {@code "edit"} (FR-21, changes/0012). */
    public enum Mode {
        RECORD("record"),
        EDIT("edit");

        private final String json;

        Mode(String json) {
            this.json = json;
        }

        @JsonValue
        public String json() {
            return json;
        }

        @JsonCreator
        public static Mode from(String value) {
            return Arrays.stream(values())
                    .filter(m -> m.json.equals(value))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("unknown pending mode: " + value));
        }
    }

    /** edit 모드의 수정 대상 — 원본 노트 slug + 엔트리 date. 날짜 이동 시 옛 카드 삭제 근거(AC-39). */
    public record EditTarget(String slug, LocalDate date) {
    }
}
