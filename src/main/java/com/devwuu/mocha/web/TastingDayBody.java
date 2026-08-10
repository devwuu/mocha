package com.devwuu.mocha.web;

import com.devwuu.mocha.domain.Cup;
import com.devwuu.mocha.domain.TastingDay;
import com.devwuu.mocha.domain.Review;

import java.time.LocalDate;
import java.util.List;

/**
 * {@code PATCH /api/notes/&#123;id&#125;/tasting-days/&#123;date&#125;} 요청 — 그 날짜 시음 기록의 회차 교체
 * + 날짜 이동 (ref: changes/0029 tasks.md TΔ13b·TΔ5b-3, 계약 정본
 * {@code contract/note-update.contract.json}).
 *
 * <p><b>경로의 {@code date}가 대상이고 본문의 {@code date}가 결과다.</b> 둘이 다르면 날짜 이동, 같으면
 * 제자리 수정이다. 한 필드가 두 뜻을 지지 않게 자리를 나눈 것이라 요청만 보고 어느 쪽인지 알 수 있고,
 * {@code NoteTxService.replaceTastingDay(noteId, targetDate, tastingDay, …)}가 이미 그 모양이다(TΔ4a).
 *
 * <p><b>회차 타입은 상세 응답의 것을 그대로 쓴다</b>({@link NoteDetailBody.DetailCup}) — 폼이 받은 것을
 * 고쳐 되돌려 보내는 자리라 두 벌로 나눌 이유가 없고, 나누면 <i>"응답에는 있는데 요청에는 없는 필드"</i>가
 * 조용히 생긴다. 그 타입에 {@code myTasteOriginal}이 없다는 사실이 여기서 한 번 더 값을 낸다(아래).
 *
 * <p>POLICY: 감상 원문은 요청에 싣지 않는다 — 폼이 고치는 것은 정규화본이고, 원문이 비면 {@link Review}가
 * 정규화본을 양쪽에 담는다. 수정하면 <i>"말한 그대로"</i>가 편집본으로 수렴하는 것이 이 계약의 뜻이다
 * (ref: data-model.md#V-11 뒷문장).
 */
public record TastingDayBody(LocalDate date, List<NoteDetailBody.DetailCup> cups) {

    /**
     * 도메인 시음일로 되돌린다 — 회차는 <b>V-15 정규화를 거친다</b>(빈 감상 드롭 · 레시피·감상 둘 다 없는
     * 회차 드롭). 정규화 결과가 빈 배열이면 저장할 것이 없다는 뜻이고, 그 거부는 쓰기 경로의 진입점인
     * 컨트롤러가 진다(data-model.md#V-15).
     *
     * <p>{@code updatedAt}은 {@code null}이다 — 시음일 행의 시각은 감사 리스너가 채운다(0028 TΔ4).
     */
    public TastingDay toTastingDay() {
        List<Cup> raw = cups == null ? List.of() : cups.stream().map(TastingDayBody::toCup).toList();
        return new TastingDay(date, Cup.normalize(raw), null);
    }

    private static Cup toCup(NoteDetailBody.DetailCup cup) {
        if (cup == null) {
            return null;
        }
        NoteDetailBody.DetailReview review = cup.review();
        // 원문 자리는 비워 넘긴다 — Review가 정규화본을 양쪽에 담는다(V-11).
        return new Cup(cup.recipe(),
                review == null ? null : new Review(review.myTaste(), null, review.rating()));
    }
}
