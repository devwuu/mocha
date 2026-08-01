package com.devwuu.mocha.domain;

import java.util.List;

/**
 * 갤러리 목록 조회의 한 페이지 (ref: changes/0029 tasks.md TΔ5a·TΔ12).
 *
 * <p>페이지 번호도 총 페이지 수도 없다 — 무한 스크롤이라 <b>다음 지점 하나</b>면 충분하고, 그것이 없는
 * 것이 "더 없다"의 유일한 신호다. 번호가 생기면 화면이 페이지네이션으로 되돌아간다.
 *
 * @param notes      이 페이지의 노트들 — 최근 시음일 내림차순(NULLS LAST) → id 내림차순.
 * @param nextCursor 다음 페이지의 시작 지점, 마지막 페이지면 {@code null}.
 * @param total      <b>필터가 적용된</b> 총 건수 — 헤더의 <i>"N편의 기록"</i>이 그 값이라 필터를 걸면
 *                   함께 줄어든다. 페이지마다 같은 값이 실린다.
 * @param facets     필터 칩의 선택지 — 이쪽은 필터와 무관한 저장분 전체다({@link NoteFacets} POLICY).
 */
public record NotePage(
        List<NoteListItem> notes,
        NoteCursor nextCursor,
        long total,
        NoteFacets facets
) {

    public NotePage {
        notes = notes == null ? List.of() : List.copyOf(notes);
    }

    /** 저장된 노트가 하나도 없거나 필터가 전부 걸러낸 상태 — 오류가 아니라 정상 응답이다. */
    public static NotePage empty() {
        return new NotePage(List.of(), null, 0, NoteFacets.empty());
    }
}
