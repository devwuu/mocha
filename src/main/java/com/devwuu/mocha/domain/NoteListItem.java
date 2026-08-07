package com.devwuu.mocha.domain;

import java.time.LocalDate;

/**
 * 갤러리 그리드 한 칸 — 노트 1건의 <b>납작한 사영</b>
 * (ref: changes/0029 tasks.md TΔ5a·TΔ12, 계약 정본 {@code contract/note-list.contract.json}).
 *
 * <p>필드가 {@link NoteCandidate} + 사진 하나인 것은 우연이 아니다: 두 화면 다 노트를 <i>고르는</i> 자리라
 * 3단 중첩({@code tastingDays → cups → recipe/review})을 한 줄도 쓰지 않는다. 그래서 이 조회는 자식 8질의
 * ({@code findChildRows})를 통째로 지나지 않는다 — 실으면 노트마다 따라오고 결과의 대부분이 버려진다.
 *
 * <p><b>{@code photoPath}는 URL이 아니라 V-4 상대 경로다</b>({@code photos/…}). 도메인은 HTTP를 모르고,
 * {@code /api/photos/} 접두를 붙여 완성 URL을 만드는 것은 전송 계층의 일이다 — 그 규칙이 도메인에
 * 들어오면 카드 렌더러·삭제 경로 같은 비-HTTP 소비처가 URL을 되벗겨야 한다.
 *
 * @param noteId     식별자.
 * @param coffeeName 커피명 — 저장된 노트에는 반드시 있다(V-9, {@code note.coffee_name NOT NULL}).
 * @param roastery   로스터리 또는 {@code null}(모르는 노트).
 * @param latestDate 최근 시음일 또는 {@code null}(시음일이 없는 노트 — 삭제 직후가 그 상태다).
 * @param photoPath  썸네일이 될 사진의 상대 경로 또는 {@code null}(사진 없이 발화만으로 기록한 노트).
 *                   <b>가장 최근 날짜의 첫 장</b>이라 상세 화면의 히어로와 같은 사진이다.
 */
public record NoteListItem(
        long noteId,
        String coffeeName,
        String roastery,
        LocalDate latestDate,
        String photoPath
) {
}
