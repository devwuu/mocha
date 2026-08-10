package com.devwuu.mocha.web;

import com.devwuu.mocha.domain.Bean;
import com.devwuu.mocha.domain.TastingDay;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.Sourced;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 클라이언트가 주고받는 <b>작성 중인 노트</b> — 도메인 {@link Note}에서 {@code aliases}만 뺀 형태
 * (ref: changes/0029 tasks.md TΔ10·TΔ6a, {@code contract/agent-turn.contract.json}).
 *
 * <p><b>왜 한 벌 더 있는가</b>: 별칭(V-13)은 <i>내부 매칭·검색 전용</i>이고 폼은 그것을 표시하지도
 * 편집하지도 않는다. 커밋 시 축적도 저장된 값을 읽어 서버가 계산한다(TΔ4c
 * {@code commit(noteId, meta, tastingDay, generated)} — {@code generated == null}이 그 신호). TΔ10이 화면을
 * 세우며 이 사실을 확인하고 계약에서 잘라냈고, 이 타입이 그 결정의 구현이다.
 *
 * <p>{@code REVIEW.md} §4(<i>"같은 데이터를 표현하는 클래스가 두 벌 생기면 리뷰 지적 대상"</i>)의
 * <b>뷰 전용 가공 예외</b>로 둔다 — 두 벌이 생기는 범위는 <b>이 한 겹뿐</b>이다. 중첩 타입
 * ({@link Sourced}·{@link Bean}·{@link TastingDay}·회차·레시피·감상)은 도메인 record를 그대로 재사용하므로
 * 계약과 도메인이 갈라질 여지가 노트 최상위 필드 목록으로 한정된다. {@code ClientApiContractTest}가
 * 그 목록을 TΔ2 스냅샷과 대조해 드리프트를 잡는다.
 *
 * <p>필드 순서가 곧 JSON 필드 순서이고 계약 파일과 바이트 단위로 짝이다 — 바꾸면 계약을 바꾸는 것이다.
 */
public record NoteBody(
        Long id,
        Sourced<String> coffeeName,
        Sourced<String> roastery,
        List<Bean> beans,
        Sourced<String> roastLevel,
        Sourced<List<String>> officialNotes,
        List<String> sources,
        List<TastingDay> tastingDays,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {

    public static NoteBody of(Note note) {
        return new NoteBody(note.id(), note.coffeeName(), note.roastery(), note.beans(),
                note.roastLevel(), note.officialNotes(), note.sources(), note.tastingDays(),
                note.createdAt(), note.updatedAt());
    }

    /**
     * 도메인 노트로 되돌린다 — 별칭은 <b>빈 값</b>이다.
     * <p>클라이언트가 별칭을 보낼 수 없으므로 여기서 지어낼 것도 없다. 커밋 경로가 저장된 값을 읽어
     * 축적하고(V-13 ②), 신규 노트의 생성 콜은 {@code NoteService}가 판정한다(ADR-37).
     */
    public Note toNote() {
        return new Note(id, coffeeName, roastery, beans, roastLevel, officialNotes,
                sources, tastingDays, createdAt, updatedAt);
    }
}
