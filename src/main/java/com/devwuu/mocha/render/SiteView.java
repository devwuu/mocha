package com.devwuu.mocha.render;

import com.devwuu.mocha.domain.Rating;
import com.devwuu.mocha.domain.Sourced;

import java.time.LocalDate;
import java.util.List;

/**
 * 렌더 템플릿에 넘기는 표시용 뷰 모델 — 도메인 {@link com.devwuu.mocha.domain.Note}를 화면 표현으로 축약한다
 * (날짜 포맷은 {@link KoreanDates}, 출처 배지는 {@code source} 필드, 상대 경로는 렌더러가 계산).
 * <p>T5-1 골격은 <b>가장 최근 엔트리 1건</b>만 노트 카드에 표시한다 — 여러 날짜 엔트리 타임라인은 T5-2가 채운다.
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

    /** 노트 상세 페이지 뷰 (ref: FR-7). 출처 표시 필드는 {@link Sourced} 그대로 넘겨 {@code (검색)} 표기 분기(AC-2). */
    public record NotePage(
            String slug,
            String coffeeName,
            Sourced<String> roastery,
            Sourced<String> origin,
            Sourced<String> process,
            Sourced<String> roastLevel,
            List<String> officialNotes,
            List<String> sources,
            LocalDate date,
            String myTaste,
            Rating rating,
            List<String> photos) {
    }
}
