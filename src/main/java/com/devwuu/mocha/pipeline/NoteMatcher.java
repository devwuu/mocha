package com.devwuu.mocha.pipeline;

import com.devwuu.mocha.domain.Aliases;
import com.devwuu.mocha.domain.Note;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 파이프라인 [3] — 추출 결과를 기존 노트 목록과 대조해 신규/기존을 판정한다
 * (ref: plan.md §1 [3], §3 match(extraction, identity, existingNotes); spec FR-14).
 * <p>두 갈래로 판정한다(changes/0016, plan ADR-37):
 * <ol>
 *   <li><b>서버 결정적 별칭 대조</b> — OCR 오버레이 반영 최종 식별 정보({@link MatchIdentity})가 어떤
 *       노트의 별칭 집합(표시값 포함, 정규화 기준)과 일치하면 LLM {@code matched_slug}와 무관하게 그 노트로
 *       매칭한다. 표기 비일관("FroB" vs "FroB Coffee roasters")으로 갈리던 미병합을 예방한다.</li>
 *   <li><b>LLM matched_slug 재검증</b> — 별칭 대조가 없으면 종전대로 LLM이 고른 slug가 실존하는지 서버가
 *       재검증한다(환각 필터). 존재하면 갱신/추가(AC-15)를 가르고, 없으면 신규로 폴백한다.</li>
 * </ol>
 * <p>POLICY: 서버 별칭 대조는 LLM matched_slug보다 우선한다 — 대조 히트·불일치는 구분 로깅한다
 * (ref: plan.md#ADR-37, §6 매칭 보조 관측).
 */
public class NoteMatcher {

    private static final Logger log = LoggerFactory.getLogger(NoteMatcher.class);

    /**
     * @param extraction    추출 결과. {@code targetDate}는 NoteExtractor가 today로 기본화한 상태를 전제.
     * @param identity      OCR 오버레이 반영 최종 식별 정보(커피명·로스터리) — 서버 별칭 대조의 입력.
     * @param existingNotes 매칭 대상 기존 노트(없으면 빈 리스트).
     */
    public MatchResult match(ExtractionResult extraction, MatchIdentity identity, List<Note> existingNotes) {
        if (existingNotes == null) {
            return MatchResult.newNote(extraction.targetDate());
        }

        // [3-a] 서버 결정적 별칭 대조 — LLM 판정보다 우선.
        Note aliasMatch = findAliasMatch(identity, existingNotes);
        if (aliasMatch != null) {
            logAliasMatch(extraction.matchedSlug(), aliasMatch, identity);
            return existingResult(aliasMatch, extraction.targetDate());
        }

        // [3-b] 별칭 미일치 → 종전 LLM matched_slug 재검증 경로.
        return matchByLlmSlug(extraction, existingNotes);
    }

    /**
     * 식별 정보 없이(사진 미첨부·OCR 무정보) 추출값 자체를 식별 정보로 쓰는 편의 오버로드 —
     * NoteExtractor의 사진 없는 흐름 오버로드와 같은 정신.
     */
    public MatchResult match(ExtractionResult extraction, List<Note> existingNotes) {
        return match(extraction, MatchIdentity.of(extraction.coffeeName(), extraction.roastery()), existingNotes);
    }

    // 정규화 별칭 집합(표시값 포함) 대조 — 커피명이 어느 노트의 커피 집합과 일치하고, 로스터리가 양쪽에 있으면
    // 로스터리도 일치할 때 그 노트로 확정한다(동명·타 로스터리 위양성 차단). 커피명 식별이 없으면 대조하지 않는다.
    private static Note findAliasMatch(MatchIdentity identity, List<Note> existingNotes) {
        if (identity == null) {
            return null;
        }
        String coffeeKey = Aliases.normalize(identity.coffeeName());
        if (coffeeKey.isEmpty()) {
            return null;
        }
        String roasteryKey = Aliases.normalize(identity.roastery());
        for (Note note : existingNotes) {
            if (!coffeeSet(note).contains(coffeeKey)) {
                continue;
            }
            Set<String> roasterySet = roasterySet(note);
            if (!roasteryKey.isEmpty() && !roasterySet.isEmpty() && !roasterySet.contains(roasteryKey)) {
                continue; // 로스터리가 양쪽에 있는데 어긋나면 다른 커피 — 위양성 방지.
            }
            return note;
        }
        return null;
    }

    // 노트의 커피명 대조 집합: 표시값(V-13에 따라 항상 포함) + coffee_name 별칭, 정규화 키.
    private static Set<String> coffeeSet(Note note) {
        Set<String> set = new HashSet<>();
        if (note.coffeeName() != null) {
            set.add(Aliases.normalize(note.coffeeName().value()));
        }
        if (note.aliases() != null) {
            for (String alias : note.aliases().coffeeName()) {
                set.add(Aliases.normalize(alias));
            }
        }
        set.remove("");
        return set;
    }

    // 노트의 로스터리 대조 집합: 표시값 + roastery 별칭, 정규화 키.
    private static Set<String> roasterySet(Note note) {
        Set<String> set = new HashSet<>();
        if (note.roastery() != null) {
            set.add(Aliases.normalize(note.roastery().value()));
        }
        if (note.aliases() != null) {
            for (String alias : note.aliases().roastery()) {
                set.add(Aliases.normalize(alias));
            }
        }
        set.remove("");
        return set;
    }

    // 종전 로직 — LLM matched_slug가 실존하는지 재검증하고, 존재하면 기존/없으면 신규 폴백.
    private static MatchResult matchByLlmSlug(ExtractionResult extraction, List<Note> existingNotes) {
        String matchedSlug = extraction.matchedSlug();
        if (matchedSlug == null) {
            return MatchResult.newNote(extraction.targetDate());
        }
        Optional<Note> matched = existingNotes.stream()
                .filter(note -> matchedSlug.equals(note.slug()))
                .findFirst();
        if (matched.isEmpty()) {
            // LLM이 존재하지 않는 slug를 지어냈거나 후보에서 빠진 경우 — 신규로 폴백.
            return MatchResult.newNote(extraction.targetDate());
        }
        return existingResult(matched.get(), extraction.targetDate());
    }

    // 매칭된 노트에 대상 날짜 엔트리가 이미 있는지로 갱신/추가(AC-15 표기 재료)를 가른다.
    private static MatchResult existingResult(Note note, LocalDate targetDate) {
        boolean entryExists = note.entries() != null && note.entries().stream()
                .anyMatch(entry -> targetDate.equals(entry.date()));
        return MatchResult.existing(note, targetDate, entryExists);
    }

    // 매칭 보조 관측(plan §6, ADR-37): 별칭 대조 히트(LLM null인데 서버 성립)·LLM/서버 판정 불일치를 구분 로깅.
    private static void logAliasMatch(String llmSlug, Note serverMatch, MatchIdentity identity) {
        if (llmSlug == null) {
            log.info("서버 별칭 대조 히트 — LLM matched_slug=null, 서버 매칭 성립: slug={} identity=[{}/{}]",
                    serverMatch.slug(), identity.coffeeName(), identity.roastery());
        } else if (!llmSlug.equals(serverMatch.slug())) {
            log.info("LLM·서버 판정 불일치 — 서버 별칭 대조 우선: llmSlug={} serverSlug={} identity=[{}/{}]",
                    llmSlug, serverMatch.slug(), identity.coffeeName(), identity.roastery());
        }
    }
}
