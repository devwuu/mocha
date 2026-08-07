package com.devwuu.mocha.domain;

import java.time.LocalDate;

/**
 * 갤러리 목록의 페이징 지점 — <b>마지막으로 돌려준 행의 정렬 키</b>
 * (ref: changes/0029 tasks.md TΔ5a·TΔ12, 계약 정본 {@code contract/note-list.contract.json}).
 *
 * <p>정렬 축이 {@code (latest_date desc nulls last, note_id desc)}이므로 그 두 값이 곧 위치다. offset이
 * 아니라 키를 싣는 이유는 무한 스크롤 도중 노트가 하나 저장되면 offset 페이징이 <b>같은 노트를 두 번
 * 보여주거나 한 건을 건너뛰기</b> 때문이다.
 *
 * <p><b>클라이언트에게는 불투명 문자열로 나간다</b> — 만들지도 해석하지도 않고 받은 값을 그대로 되싣는다.
 * 그래야 정렬 축이 바뀌어도 클라이언트가 따라 바뀌지 않는다. 문자열 ↔ 이 값의 변환은 전송 표현이라
 * {@code web/NoteCursorCodec}이 소유하고, 이 record는 값만 안다.
 *
 * @param latestDate 그 노트의 최근 시음일. <b>{@code null}이 정상값</b>이다 — 시음일이 없는 노트는
 *                   정렬에서 맨 뒤에 서고, 그 지점도 커서가 될 수 있다.
 * @param noteId     같은 날짜 안의 순서를 가르는 축.
 */
public record NoteCursor(LocalDate latestDate, long noteId) {
}
