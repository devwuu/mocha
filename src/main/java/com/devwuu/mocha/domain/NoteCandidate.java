package com.devwuu.mocha.domain;

import java.time.LocalDate;

/**
 * 매칭 후보 1건 — 변경 시트의 한 줄
 * (ref: changes/0029 tasks.md TΔ7·TΔ11; 계약 정본 {@code contract/note-candidates.contract.json}).
 *
 * <p><b>{@link Note}가 아니라 이 납작한 사영인 이유</b>: 시트는 노트를 <i>고르는</i> 화면이라 3단 중첩
 * (엔트리·회차·레시피·감상)이 한 줄도 쓰이지 않는다. {@code findAll()}을 재사용하면 후보 목록 한 번에
 * 자식 8질의가 따라오고, 그 결과의 99%가 버려진다.
 *
 * <p><b>로스터리가 필드에 들어간 근거는 도메인이다</b>(사용자 확정 2026-08-01, TΔ11): 싱글 오리진은
 * 커피명이 산지·농장·품종에서 오므로 <b>로스터리가 다르면 같은 이름의 다른 커피</b>인 것이 정상이다.
 * 커피명만 보여주면 동명 후보를 가를 축이 없고, 사용자는 고를 수 없다.
 *
 * <p>{@code roastery}·{@code latestDate}는 nullable이다 — 로스터리를 모르는 노트가 있고, 엔트리가 없는
 * 노트(삭제 직후가 그렇다)는 시음일이 빈다.
 */
public record NoteCandidate(long noteId, String coffeeName, String roastery, LocalDate latestDate) {
}
