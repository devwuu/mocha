package com.devwuu.mocha.render;

import com.devwuu.mocha.domain.Rating;
import com.devwuu.mocha.domain.Sourced;

import java.time.LocalDate;
import java.util.List;

/**
 * 렌더 템플릿에 넘기는 표시용 뷰 모델 — 도메인 {@link com.devwuu.mocha.domain.Note}를 화면 표현으로 축약한다
 * (날짜 포맷은 {@link KoreanDates}, 출처 배지는 {@code source} 필드, 상대 경로는 렌더러가 계산).
 * <p>카드 단위 = <b>시음 엔트리 1건</b>(커피×날짜, ADR-10). 인덱스 행({@link Row})은 엔트리 1건을
 * 최신순으로 요약해 카드 JPG로 링크하고, 카드 뷰({@link EntryCard})는 대상 엔트리 1건만 렌더한다
 * (ref: changes/0002-instagram-share-card delta, FR-7/AC-13).
 */
public final class NoteView {

    private NoteView() {
    }

    /** 인덱스 페이지 뷰 (ref: FR-8). rows는 엔트리 기록일 내림차순. */
    public record Index(int noteCount, int recordCount, List<Row> rows) {
    }

    /**
     * 인덱스의 시음 엔트리 1행 (ref: FR-8, ADR-10). {@code href}는 {@code cards/<slug>/<date>.jpg} 상대 링크(AC-Δ5).
     * <p>같은 커피를 여러 날 기록하면 엔트리마다 별도 행이 생긴다 — 노트당 1행이 아니라 엔트리당 1행.
     */
    public record Row(
            String href,
            String coffeeName,
            String roastery,
            String origin,
            LocalDate date,
            Rating rating,
            String thumb) {
    }

    /**
     * 시음 엔트리 카드 뷰 (ref: FR-7, ADR-10). 노트 메타(로스터리·원산지·official_notes·출처) + 대상 엔트리 1건.
     * 출처 표시 필드는 {@link Sourced} 그대로 넘겨 {@code (검색)} 표기 분기(AC-2).
     * <p>카드는 커피의 <b>그 한 잔</b>만 담는다 — 노트 전체 타임라인이 아니라 {@code entry} 1건(AC-Δ4).
     * "로스터리가 말하길"(officialNotes)은 노트 단위, "내가 느끼길"(entry.myTaste)은 그 엔트리 1건(FR-7).
     */
    public record EntryCard(
            String slug,
            String coffeeName,
            Sourced<String> roastery,
            Sourced<String> origin,
            Sourced<String> process,
            Sourced<String> roastLevel,
            List<String> officialNotes,
            List<String> sources,
            EntryView entry) {
    }

    /** 카드의 날짜 엔트리 1건 (ref: FR-15, data-model §2.2). {@code photos}는 렌더러가 계산한 썸네일 상대 경로. */
    public record EntryView(
            LocalDate date,
            String myTaste,
            Rating rating,
            List<String> photos) {
    }
}
