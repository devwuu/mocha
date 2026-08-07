package com.devwuu.mocha.domain;

import java.time.LocalDate;

/**
 * 저장된 노트에 딸린 사진 1장 — 아카이브 파일의 <b>색인</b>
 * (ref: changes/0029 tasks.md TΔ5a·TΔ8b, plan.md#ADR-79).
 *
 * <p><b>사진은 노트가 아니라 날짜에 붙는다</b>. {@code note_photo}의 참조 축이 {@code (note_id, tasted_on)}
 * 이기 때문이고(시음일이 저장 때마다 통째로 교체되므로 시음일 id를 참조할 수 없다), 상세 화면은 이 한 벌로
 * 히어로와 날짜별 스트립을 <b>둘 다</b> 만든다 — 노트 레벨 배열을 따로 두면 같은 사진이 두 자리에 실린다.
 *
 * <p>{@code path}는 URL이 아니라 V-4 상대 경로({@code photos/…})다 — 근거는 {@link NoteListItem}과 같다.
 *
 * @param date 그 사진이 붙은 시음 날짜.
 * @param path {@code photos/}로 시작하는 아카이브 상대 경로.
 */
public record NotePhoto(LocalDate date, String path) {
}
