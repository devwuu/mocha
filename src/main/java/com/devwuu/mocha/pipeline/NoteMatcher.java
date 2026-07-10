package com.devwuu.mocha.pipeline;

import com.devwuu.mocha.domain.Note;

import java.util.List;
import java.util.Optional;

/**
 * 파이프라인 [3] — 추출 결과를 기존 노트 목록과 대조해 신규/기존을 판정한다
 * (ref: plan.md §1 [3], §3 match(extraction, existingNotes); spec FR-14).
 * <p>LLM이 고른 {@code matched_slug}는 힌트일 뿐이므로 서버가 실제 존재하는 slug인지 재검증한다.
 * 존재하면 대상 날짜에 엔트리가 이미 있는지로 갱신/추가를 가른다(AC-15 표기 재료). 없거나 매칭이 없으면
 * 신규 노트로 판정한다.
 * <p>POLICY: LLM matched_slug는 서버가 재검증한다 — 존재하지 않으면 신규 폴백 (ref: spec FR-14).
 */
public class NoteMatcher {

    /**
     * @param extraction    추출 결과. {@code targetDate}는 NoteExtractor가 today로 기본화한 상태를 전제.
     * @param existingNotes 매칭 대상 기존 노트(없으면 빈 리스트).
     */
    public MatchResult match(ExtractionResult extraction, List<Note> existingNotes) {
        String matchedSlug = extraction.matchedSlug();
        if (matchedSlug == null || existingNotes == null) {
            return MatchResult.newNote(extraction.targetDate());
        }

        Optional<Note> matched = existingNotes.stream()
                .filter(note -> matchedSlug.equals(note.slug()))
                .findFirst();
        if (matched.isEmpty()) {
            // LLM이 존재하지 않는 slug를 지어냈거나 후보에서 빠진 경우 — 신규로 폴백.
            return MatchResult.newNote(extraction.targetDate());
        }

        Note note = matched.get();
        boolean entryExists = note.entries() != null && note.entries().stream()
                .anyMatch(entry -> extraction.targetDate().equals(entry.date()));
        return MatchResult.existing(note, extraction.targetDate(), entryExists);
    }
}
