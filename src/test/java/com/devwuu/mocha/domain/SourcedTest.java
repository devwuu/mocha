package com.devwuu.mocha.domain;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TΔ14: 출처 표시 필드의 null 안전 추출 헬퍼.
 * <p>백로그 CR25-2(officialNotes 추출 형태 분화) 해소로 세운 {@code valuesOrEmpty}의 계약을 고정한다 —
 * 소비처 4곳({@code NoteLookupTools}·{@code ThymeleafNoteRenderer}·{@code NoteEntityMapper}·
 * {@code PreviewBlocks})이 각자 기본값 3항을 갖던 것을 여기 하나로 모았으므로, 수렴 규칙은 이 테스트가 소유한다.
 * (ref: specs/coffee-note-agent/plan.md#ADR-67, backlog.md 해소 이력 2026-07-31)
 */
class SourcedTest {

    @Test
    @DisplayName("valueOrNull: 필드 부재와 값 부재를 구분하지 않고 null로 수렴한다")
    void valueOrNullConverges() {
        assertThat(Sourced.<String>valueOrNull(null)).isNull();
        assertThat(Sourced.<String>valueOrNull(new Sourced<>(null, Source.SEARCH))).isNull();
        assertThat(Sourced.valueOrNull(new Sourced<>("커피베라", Source.USER))).isEqualTo("커피베라");
    }

    @Test
    @DisplayName("valuesOrEmpty: 필드 부재·값 부재를 모두 빈 목록으로 접는다 — 소비처가 null을 보지 않는다")
    void valuesOrEmptyConverges() {
        assertThat(Sourced.<String>valuesOrEmpty(null)).isEmpty();
        // 값 없이 source만 있는 상태(roast_level의 Sourced<>(null, SEARCH)와 같은 부류)도 빈 목록이다.
        assertThat(Sourced.<String>valuesOrEmpty(new Sourced<>(null, Source.SEARCH))).isEmpty();
    }

    @Test
    @DisplayName("valuesOrEmpty: 값이 있으면 목록을 그대로 돌려준다(사본을 만들지 않는다)")
    void valuesOrEmptyPassesThrough() {
        List<String> notes = List.of("자몽", "홍차");
        assertThat(Sourced.valuesOrEmpty(new Sourced<>(notes, Source.PHOTO))).isSameAs(notes);
    }
}
