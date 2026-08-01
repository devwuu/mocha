package com.devwuu.mocha.web;

import com.devwuu.mocha.domain.NoteFacets;
import com.devwuu.mocha.domain.NoteListItem;
import com.devwuu.mocha.domain.NotePage;

import java.time.LocalDate;
import java.util.List;

/**
 * {@code GET /api/notes} 응답 — 갤러리 그리드 한 페이지
 * (ref: changes/0029 tasks.md TΔ5a·TΔ12, 계약 정본 {@code contract/note-list.contract.json}).
 *
 * <p>도메인 {@link NotePage}와 갈리는 지점이 <b>둘뿐</b>이다: 사진이 상대 경로가 아니라 URL이고
 * ({@link PhotoUrl}), 커서가 값이 아니라 불투명 문자열이다({@link NoteCursorCodec}). 둘 다 <i>"HTTP라서
 * 있는 것"</i>이라 전송 계층이 소유한다(백엔드 CLAUDE.md §2) — 도메인이 URL과 base64를 알면 카드 렌더러
 * 같은 비-HTTP 소비처가 그것을 되벗겨야 한다.
 *
 * <p>필드 순서가 곧 JSON 필드 순서이고 계약 파일과 짝이다 — 바꾸면 계약을 바꾸는 것이다.
 *
 * @param notes      이 페이지의 노트들.
 * @param nextCursor 다음 페이지 지점, 마지막 페이지면 {@code null} — 그것이 <b>"더 없다"의 유일한 신호</b>다.
 *                   {@code page}·{@code total_pages}가 없는 것 자체가 계약이다(무한 스크롤).
 * @param total      필터가 적용된 총 건수 — 헤더의 <i>"N편의 기록"</i>.
 * @param facets     필터 칩의 선택지 — 이쪽은 필터와 무관한 저장분 전체다({@link NoteFacets} POLICY).
 */
public record NoteListBody(
        List<Item> notes,
        String nextCursor,
        long total,
        Facets facets) {

    /**
     * 그리드 한 칸 — 노트 1건의 납작한 사영.
     * <p>3단 중첩({@code entries → brews → recipe/tasting})이 한 줄도 없다: 갤러리는 노트를 <i>고르는</i>
     * 화면이고, 무엇을 보여줄지는 {@code GET /api/notes/{id}}가 따로 답한다.
     */
    public record Item(
            long noteId,
            String coffeeName,
            String roastery,
            LocalDate latestDate,
            String thumbnailUrl) {
    }

    /** 칩 선택지 — 로스터리·가공방식뿐이다(평가는 고정 4범주, 원산지는 자유 텍스트). */
    public record Facets(List<String> roastery, List<String> process) {
    }

    public static NoteListBody of(NotePage page) {
        return new NoteListBody(
                page.notes().stream().map(NoteListBody::toItem).toList(),
                NoteCursorCodec.encode(page.nextCursor()),
                page.total(),
                new Facets(page.facets().roastery(), page.facets().process()));
    }

    private static Item toItem(NoteListItem note) {
        return new Item(note.noteId(), note.coffeeName(), note.roastery(), note.latestDate(),
                PhotoUrl.of(note.photoPath()));
    }
}
