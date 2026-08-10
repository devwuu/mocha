package com.devwuu.mocha.web;

import com.devwuu.mocha.domain.Bean;
import com.devwuu.mocha.domain.Cup;
import com.devwuu.mocha.domain.TastingDay;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.NoteDetail;
import com.devwuu.mocha.domain.Rating;
import com.devwuu.mocha.domain.Recipe;
import com.devwuu.mocha.domain.Sourced;
import com.devwuu.mocha.domain.Review;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * {@code GET /api/notes/&#123;id&#125;} 응답 — 저장된 노트 전문 + 날짜별 사진
 * (ref: changes/0029 tasks.md TΔ5a·TΔ13a, 계약 정본 {@code contract/note-detail.contract.json}).
 *
 * <p><b>{@link NoteBody}(작성 중인 노트)와 갈리는 지점이 이 타입의 존재 이유다</b>:
 * <ul>
 *   <li><b>{@code coffeeName}이 반드시 있다</b> — 작성 중인 노트는 커피명이 아직 안 뽑혔을 수 있지만
 *       저장된 노트는 그것이 정체성이다(V-9, {@code note.coffee_name NOT NULL}).</li>
 *   <li><b>사진이 실린다</b> — 저장 전에는 스테이징 파일명뿐이고 저장 후에야 URL이 생긴다.</li>
 *   <li><b>절단 셋</b>: {@code aliases}(V-13 내부 전용) · {@code created_at}/{@code updated_at}(표시 자리
 *       없음) · <b>{@code my_taste_original}</b>. 마지막은 V-11의 뒷문장이 <i>"렌더는 {@code my_taste}만
 *       사용"</i>이고 상세도 렌더이기 때문이며, {@link Review}와 타입을 나눠 <b>컴파일러가 지키게</b> 했다.</li>
 * </ul>
 *
 * <p><b>출처는 그대로 싣는다</b>(사용자 확정 2026-08-01). 둘이 걸려 있다 — ① 화면이 캡처 폼과 같은 어휘로
 * <i>"이 값은 봉투에서 읽은 것"</i>을 보여준다 ② <b>TΔ13b 수정 폼이 이 응답을 그대로 딛는다</b>: 버리면
 * 로스터리만 고쳐도 나머지 필드가 {@code user}로 덮여, 이후 검색 보강이 닿지 못하는 값이 조용히 는다
 * (V-6이 막으려는 방향의 반대편 사고).
 *
 * <p>중첩 타입 중 {@link Sourced}·{@link Bean}·{@link Recipe}는 도메인 record를 그대로 재사용한다 —
 * 계약과 도메인이 갈라질 여지를 <b>실제로 갈리는 곳</b>(감상·시음일·최상위)으로 한정한다({@link NoteBody}와
 * 같은 규율, REVIEW.md §4 뷰 전용 가공 예외).
 *
 * <p>필드 순서가 곧 JSON 필드 순서이고 계약 파일과 짝이다.
 */
public record NoteDetailBody(
        long noteId,
        Sourced<String> coffeeName,
        Sourced<String> roastery,
        List<Bean> beans,
        Sourced<String> roastLevel,
        Sourced<List<String>> officialNotes,
        List<String> sources,
        List<DetailTastingDay> tastingDays) {

    /**
     * 날짜별 시음 기록 + <b>그 날의 사진</b>.
     * <p>사진이 노트가 아니라 시음일에 붙는 것은 {@code note_photo}의 참조 축이 {@code (note_id, tasted_on)}
     * 이기 때문이고, 계약 하나로 히어로와 날짜별 스트립이 <b>둘 다</b> 나온다 — 노트 레벨 배열을 따로 두면
     * 같은 사진이 두 자리에 실린다.
     */
    public record DetailTastingDay(LocalDate date, List<DetailCup> cups, List<Photo> photos) {
    }

    /** 저장된 회차 1개 — 레시피·감상 중 최소 하나는 non-null이다(V-15). */
    public record DetailCup(Recipe recipe, DetailReview review) {
    }

    /** 저장된 감상 — <b>원문이 없다</b>(위 절단 셋). */
    public record DetailReview(String myTaste, Rating rating) {
    }

    /** 사진 1장 — 목록의 {@code thumbnail_url}과 같은 접두를 쓴다({@link PhotoUrl}). */
    public record Photo(String url) {
    }

    public static NoteDetailBody of(NoteDetail detail) {
        Note note = detail.note();
        Map<LocalDate, List<String>> photosByDate = detail.photosOn();
        return new NoteDetailBody(
                note.id(),
                note.coffeeName(),
                note.roastery(),
                note.beans(),
                note.roastLevel(),
                note.officialNotes(),
                note.sources(),
                note.tastingDays().stream()
                        .map(tastingDay -> toDetailTastingDay(tastingDay, photosByDate.getOrDefault(tastingDay.date(), List.of())))
                        .toList());
    }

    private static DetailTastingDay toDetailTastingDay(TastingDay tastingDay, List<String> paths) {
        return new DetailTastingDay(
                tastingDay.date(),
                tastingDay.cups().stream().map(NoteDetailBody::toDetailCup).toList(),
                paths.stream().map(PhotoUrl::of).filter(Objects::nonNull).map(Photo::new).toList());
    }

    private static DetailCup toDetailCup(Cup cup) {
        Review review = cup.review();
        return new DetailCup(cup.recipe(),
                review == null ? null : new DetailReview(review.myTaste(), review.rating()));
    }
}
