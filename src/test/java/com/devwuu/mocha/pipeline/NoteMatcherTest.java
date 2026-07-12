package com.devwuu.mocha.pipeline;

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
                .map(d -> new Entry(d, "맛", Rating.GOOD, null, List.of(), OffsetDateTime.now()))
                .toList();
        return new Note(
                slug, Sourced.user("커피베라 예가체프 G1"),
                Sourced.user("커피베라"), null, null, null, null,
                List.of(), entries, OffsetDateTime.now(), OffsetDateTime.now());
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
}
