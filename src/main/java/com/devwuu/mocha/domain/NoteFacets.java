package com.devwuu.mocha.domain;

import java.util.List;

/**
 * 필터 칩이 보여줄 선택지 — <b>저장된 값에서 나온다</b>
 * (ref: changes/0029 tasks.md TΔ5a·TΔ12, 사용자 확정 2026-08-01).
 *
 * <p>목록 응답에 동봉하는 이유는 무한 스크롤이라 클라이언트가 한 페이지에서 전체 값을 알 수 없어서다.
 * 별도 엔드포인트로 가르면 화면 진입에 요청이 둘이 되고, 그 둘이 어긋나는 순간 <b>있지도 않은 로스터리를
 * 고를 수 있는</b> 상태가 생긴다.
 *
 * <p>POLICY: 축은 둘뿐이다 — 평가는 4범주 고정(V-1)이라 클라이언트 상수가 소유하고, 원산지는 자유
 * 텍스트 부분일치라 열거할 값 집합이 애초에 없다 (ref: {@link NoteFilter}).
 *
 * <p>POLICY: <b>필터를 적용하지 않은 저장분 전체</b>에서 집계한다 — 걸린 필터로 좁히면 로스터리 하나를
 * 고르는 순간 나머지가 선택지에서 사라져 <b>고른 것을 되돌릴 수 없다</b>(칩이 잠긴다). 페이지가 아니라
 * 전체를 훑는 것과 같은 이유이고, 이 축이 답하는 질문이 <i>"무엇으로 좁힐 수 있는가"</i>이지
 * <i>"지금 결과에 무엇이 있는가"</i>가 아니다 (ref: 계약 {@code note-list.contract.json}).
 *
 * @param roastery 저장된 로스터리 전부, 오름차순. 로스터리를 모르는 노트는 기여하지 않는다.
 * @param process  저장된 가공방식 전부, 오름차순.
 */
public record NoteFacets(List<String> roastery, List<String> process) {

    public NoteFacets {
        roastery = roastery == null ? List.of() : List.copyOf(roastery);
        process = process == null ? List.of() : List.copyOf(process);
    }

    /** 저장된 노트가 없을 때 — 칩이 잠기고 시트가 열리지 않는다. */
    public static NoteFacets empty() {
        return new NoteFacets(List.of(), List.of());
    }
}
