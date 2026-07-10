package com.devwuu.mocha.pipeline;

import com.devwuu.mocha.domain.MatchInfo;
import com.devwuu.mocha.domain.Note;

import java.time.LocalDate;

/**
 * 파이프라인 [3] 매칭 판정 결과 — 신규 노트인지, 기존 노트에 대상 날짜로 기록하는지 (ref: plan.md §3
 * match(extraction, existingNotes): MatchResult; spec FR-14).
 * <p>{@link MatchInfo}(pending 영속·미리보기 표시용, data-model.md#2.3)와 달리, 매칭된 {@link Note}
 * 원본과 "대상 날짜에 이미 엔트리가 있는지"({@code updatesExistingEntry})까지 들고 있어 이후 단계가
 * 갱신/추가 표기(AC-15)와 커밋 대상을 정할 수 있다.
 *
 * @param type                 NEW(새 노트) 또는 EXISTING(기존 노트 대상).
 * @param matchedNote          EXISTING일 때 매칭된 기존 노트, NEW면 null.
 * @param targetDate           기록 대상 날짜(YYYY-MM-DD, Asia/Seoul) — 항상 존재.
 * @param updatesExistingEntry EXISTING이고 targetDate 엔트리가 이미 있으면 true(갱신), 없으면 false(추가).
 *                             NEW면 항상 false.
 */
public record MatchResult(
        MatchInfo.MatchType type,
        Note matchedNote,
        LocalDate targetDate,
        boolean updatesExistingEntry
) {

    public static MatchResult newNote(LocalDate targetDate) {
        return new MatchResult(MatchInfo.MatchType.NEW, null, targetDate, false);
    }

    public static MatchResult existing(Note matchedNote, LocalDate targetDate, boolean updatesExistingEntry) {
        return new MatchResult(MatchInfo.MatchType.EXISTING, matchedNote, targetDate, updatesExistingEntry);
    }

    public boolean isNew() {
        return type == MatchInfo.MatchType.NEW;
    }

    /** pending 영속·미리보기 표시용 표현으로 축약 (ref: data-model.md#2.3, AC-15). */
    public MatchInfo toMatchInfo() {
        return isNew()
                ? MatchInfo.newNote()
                : MatchInfo.existing(matchedNote.slug(), targetDate);
    }
}
