package com.devwuu.mocha.render;

import com.devwuu.mocha.domain.Rating;
import com.devwuu.mocha.domain.Sourced;

import java.time.LocalDate;
import java.util.List;

/**
 * 렌더 템플릿에 넘기는 표시용 뷰 모델 — 도메인 {@link com.devwuu.mocha.domain.Note}를 화면 표현으로 축약한다
 * (날짜 포맷은 {@link KoreanDates}, 출처 배지는 {@code source} 필드, 상대 경로는 렌더러가 계산).
 * <p>인덱스 카드({@link Row})는 <b>가장 최근 엔트리 1건</b>만 요약하고, 노트 상세({@link NotePage})는
 * 날짜 엔트리 전체를 시간순 타임라인({@link EntryView})으로 표시한다(T5-2, AC-13).
 */
public final class SiteView {

    private SiteView() {
    }

    /** 인덱스 페이지 뷰 (ref: FR-8). rows는 최근 기록일 내림차순. */
    public record Index(int noteCount, int recordCount, List<Row> rows) {
    }

    /** 인덱스의 노트 카드 1행. {@code href}는 {@code notes/<slug>.html} 상대 링크(AC-11). */
    public record Row(
            String href,
            String coffeeName,
            String roastery,
            String origin,
            LocalDate date,
            int recordCount,
            Rating rating,
            String thumb) {
    }

    /**
     * 노트 상세 페이지 뷰 (ref: FR-7). 출처 표시 필드는 {@link Sourced} 그대로 넘겨 {@code (검색)} 표기 분기(AC-2).
     * <p>{@code entries}는 날짜 오름차순(시간순, AC-13) — "내가 느끼길" 영역이 타임라인으로 나열한다.
     * {@code sources}는 검색 참조 링크(FR-12)로 하단 출처 영역에 노출한다.
     */
    public record NotePage(
            String slug,
            String coffeeName,
            Sourced<String> roastery,
            Sourced<String> origin,
            Sourced<String> process,
            Sourced<String> roastLevel,
            List<String> officialNotes,
            List<String> sources,
            List<EntryView> entries) {
    }

    /** 노트 상세의 날짜 엔트리 1건 (ref: FR-15, data-model §2.2). {@code photos}는 렌더러가 계산한 썸네일 상대 경로. */
    public record EntryView(
            LocalDate date,
            String myTaste,
            Rating rating,
            List<String> photos) {
    }
}
