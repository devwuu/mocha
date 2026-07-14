package com.devwuu.mocha.pipeline;

import com.devwuu.mocha.domain.Aliases;
import com.devwuu.mocha.domain.Entry;
import com.devwuu.mocha.domain.MatchInfo;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.Rating;
import com.devwuu.mocha.domain.Sourced;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T2-3: NoteMatcher — LLM matched_slug의 서버측 재검증과 갱신/추가 판별을 결정론적으로 검증(FR-14, AC-15).
 * 외부 호출 없이 순수 정책 로직만 다룬다(CLAUDE.md §5.2).
 */
class NoteMatcherTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 10);
    private static final NoteMatcher matcher = new NoteMatcher();

    /** targetDate 외 필드는 매칭 판정과 무관하므로 최소값으로 채운 추출 결과. */
    private static ExtractionResult extraction(String matchedSlug, LocalDate targetDate) {
        return new ExtractionResult(
                "예가체프", "커피베라", null, null, null, "좋았다",
                Rating.GOOD, null, matchedSlug, false, targetDate);
    }

    private static Note noteWithEntries(String slug, LocalDate... entryDates) {
        List<Entry> entries = java.util.Arrays.stream(entryDates)
                .map(d -> new Entry(d, "맛", Rating.GOOD, null, OffsetDateTime.now()))
                .toList();
        return new Note(
                slug, Sourced.user("커피베라 예가체프 G1"),
                Sourced.user("커피베라"), null, null, null, null,
                List.of(), entries, OffsetDateTime.now(), OffsetDateTime.now());
    }

    /** 별칭 대조용 노트 — 커피명·로스터리 표시값과 aliases를 함께 지정한다. */
    private static Note noteWithAliases(String slug, String coffeeName, String roastery, Aliases aliases) {
        return new Note(
                slug, Sourced.user(coffeeName), Sourced.user(roastery), null, null, null, null,
                aliases, List.of(),
                List.of(new Entry(TODAY, "맛", Rating.GOOD, null, OffsetDateTime.now())),
                OffsetDateTime.now(), OffsetDateTime.now());
    }

    @Test
    @DisplayName("FR-14: 존재하지 않는 slug를 반환하면 신규로 폴백한다")
    void nonExistentSlugFallsBackToNew() {
        List<Note> existing = List.of(noteWithEntries("coffeevera-yirgacheffe-g1", TODAY));

        MatchResult result = matcher.match(extraction("does-not-exist", TODAY), existing);

        assertThat(result.isNew()).isTrue();
        assertThat(result.type()).isEqualTo(MatchInfo.MatchType.NEW);
        assertThat(result.matchedNote()).isNull();
        assertThat(result.targetDate()).isEqualTo(TODAY);
        assertThat(result.updatesExistingEntry()).isFalse();
    }

    @Test
    @DisplayName("FR-14: matched_slug가 null이면 신규로 판정한다")
    void nullSlugIsNew() {
        MatchResult result = matcher.match(
                extraction(null, TODAY),
                List.of(noteWithEntries("coffeevera-yirgacheffe-g1", TODAY)));

        assertThat(result.isNew()).isTrue();
    }

    @Test
    @DisplayName("AC-15: 기존 노트 매칭 + 대상 날짜 엔트리 있음 → 갱신(updatesExistingEntry=true)")
    void existingWithSameDateEntryIsUpdate() {
        Note note = noteWithEntries("coffeevera-yirgacheffe-g1", LocalDate.of(2026, 7, 9), TODAY);

        MatchResult result = matcher.match(
                extraction("coffeevera-yirgacheffe-g1", TODAY), List.of(note));

        assertThat(result.isNew()).isFalse();
        assertThat(result.type()).isEqualTo(MatchInfo.MatchType.EXISTING);
        assertThat(result.matchedNote()).isSameAs(note);
        assertThat(result.targetDate()).isEqualTo(TODAY);
        assertThat(result.updatesExistingEntry()).isTrue();
    }

    @Test
    @DisplayName("AC-15: 기존 노트 매칭 + 대상 날짜 엔트리 없음 → 추가(updatesExistingEntry=false)")
    void existingWithoutSameDateEntryIsAppend() {
        // 노트에는 7/9 엔트리만 있고, 오늘(7/10)로 기록 → 새 엔트리 추가.
        Note note = noteWithEntries("coffeevera-yirgacheffe-g1", LocalDate.of(2026, 7, 9));

        MatchResult result = matcher.match(
                extraction("coffeevera-yirgacheffe-g1", TODAY), List.of(note));

        assertThat(result.isNew()).isFalse();
        assertThat(result.updatesExistingEntry()).isFalse();
        assertThat(result.matchedNote()).isSameAs(note);
    }

    @Test
    @DisplayName("toMatchInfo: 판정 결과가 미리보기·pending 표현으로 축약된다 (data-model §2.3)")
    void convertsToMatchInfo() {
        Note note = noteWithEntries("coffeevera-yirgacheffe-g1", TODAY);

        MatchInfo existing = matcher.match(
                extraction("coffeevera-yirgacheffe-g1", TODAY), List.of(note)).toMatchInfo();
        assertThat(existing.type()).isEqualTo(MatchInfo.MatchType.EXISTING);
        assertThat(existing.slug()).isEqualTo("coffeevera-yirgacheffe-g1");
        assertThat(existing.date()).isEqualTo(TODAY);

        MatchInfo fresh = matcher.match(extraction(null, TODAY), List.of()).toMatchInfo();
        assertThat(fresh.type()).isEqualTo(MatchInfo.MatchType.NEW);
        assertThat(fresh.slug()).isNull();
        assertThat(fresh.date()).isNull();
    }

    // --- TΔ5: 서버 결정적 별칭 대조 (ADR-37) ---

    @Test
    @DisplayName("AC-Δ4: LLM matched_slug=null이어도 식별 정보가 별칭 집합과 일치하면 그 노트로 매칭한다")
    void aliasMatchWithoutLlmSlug() {
        // 노트 표시값 "FroB Coffee roasters", 별칭에 "FroB". 식별 정보는 별칭 "FroB"로 들어온다.
        Note note = noteWithAliases("chelbesa-frob", "에티오피아 첼베사", "FroB Coffee roasters",
                new Aliases(List.of("Ethiopia Chelbesa"), List.of("FroB")));

        MatchResult result = matcher.match(
                extraction(null, TODAY),                       // LLM은 확신 못 해 null
                new MatchIdentity("Ethiopia Chelbesa", "FroB"), // 별칭으로 대조
                List.of(note));

        assertThat(result.isNew()).isFalse();
        assertThat(result.matchedNote()).isSameAs(note);
        assertThat(result.targetDate()).isEqualTo(TODAY);
    }

    @Test
    @DisplayName("AC-Δ4: 커피명 표시값(별칭 미수록)도 대조 집합에 포함된다 (V-13)")
    void aliasMatchUsesDisplayValueEvenWithoutAliases() {
        Note note = noteWithAliases("chelbesa-frob", "에티오피아 첼베사", "프롭", Aliases.empty());

        // 공백·대소문자만 다른 표기라도 정규화 대조로 일치한다.
        MatchResult result = matcher.match(
                extraction(null, TODAY),
                new MatchIdentity("에티오피아 첼베사", "프롭"),
                List.of(note));

        assertThat(result.matchedNote()).isSameAs(note);
    }

    @Test
    @DisplayName("AC-Δ4: 별칭 불일치면 종전 LLM matched_slug 재검증 경로로 매칭한다")
    void fallsBackToLlmSlugWhenNoAliasMatch() {
        Note note = noteWithAliases("chelbesa-frob", "에티오피아 첼베사", "프롭", Aliases.empty());

        MatchResult result = matcher.match(
                extraction("chelbesa-frob", TODAY),           // LLM이 slug를 지목
                new MatchIdentity("전혀 다른 커피", "다른 로스터리"), // 별칭 대조는 실패
                List.of(note));

        assertThat(result.isNew()).isFalse();
        assertThat(result.matchedNote()).isSameAs(note);
    }

    @Test
    @DisplayName("AC-Δ4: 별칭도 LLM slug도 없으면 신규로 판정한다")
    void newWhenNeitherAliasNorLlmSlug() {
        Note note = noteWithAliases("chelbesa-frob", "에티오피아 첼베사", "프롭", Aliases.empty());

        MatchResult result = matcher.match(
                extraction(null, TODAY),
                new MatchIdentity("전혀 다른 커피", null),
                List.of(note));

        assertThat(result.isNew()).isTrue();
    }

    @Test
    @DisplayName("위양성 차단: 커피명이 별칭과 같아도 로스터리가 양쪽에 있고 어긋나면 매칭하지 않는다")
    void roasteryMismatchBlocksFalsePositive() {
        // 같은 커피명 "게이샤"지만 로스터리가 다른 노트 — 별칭 커피 일치만으로 묶으면 안 된다.
        Note note = noteWithAliases("geisha-a", "게이샤", "로스터리A", Aliases.empty());

        MatchResult result = matcher.match(
                extraction(null, TODAY),
                new MatchIdentity("게이샤", "로스터리B"),
                List.of(note));

        assertThat(result.isNew()).isTrue();
    }

    @Test
    @DisplayName("서버 별칭 대조가 LLM matched_slug와 다르면 서버 판정이 우선한다")
    void serverAliasMatchWinsOverLlmSlug() {
        Note aliasNote = noteWithAliases("chelbesa-frob", "에티오피아 첼베사", "프롭", Aliases.empty());
        Note otherNote = noteWithEntries("coffeevera-yirgacheffe-g1", TODAY);

        MatchResult result = matcher.match(
                extraction("coffeevera-yirgacheffe-g1", TODAY),  // LLM은 다른 노트를 지목
                new MatchIdentity("에티오피아 첼베사", "프롭"),      // 서버 별칭 대조는 chelbesa-frob
                List.of(aliasNote, otherNote));

        assertThat(result.matchedNote()).isSameAs(aliasNote);
    }
}
