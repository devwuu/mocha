package com.devwuu.mocha.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 상세 화면이 읽는 노트 전문 — 노트 + <b>그 노트의 사진 전부</b>
 * (ref: changes/0029 tasks.md TΔ5a·TΔ13a, 계약 정본 {@code contract/note-detail.contract.json}).
 *
 * <p>둘을 한 값으로 묶는 이유는 <b>같은 트랜잭션에서 읽어야</b> 하기 때문이다 — 나눠 부르면 그 사이의
 * 저장이 "시음일은 새 날짜인데 사진은 옛 목록"인 조합을 만든다. {@link Note}에 사진을 넣지 않는 것은
 * 반대 방향의 이유다: 사진은 파일이고 노트 행과 같은 원자 단위가 아니라(백엔드 CLAUDE.md §3) 저장·수정
 * 경로 전부가 그것을 지고 다니게 된다.
 *
 * @param note   노트 본문 — 시음일은 날짜 오름차순이다(질의가 소유하는 순서).
 * @param photos 사진 전부, {@code (날짜, seq)} 오름차순. 날짜별로 묶는 것은 {@link #photosOn}이다.
 */
public record NoteDetail(Note note, List<NotePhoto> photos) {

    public NoteDetail {
        photos = photos == null ? List.of() : List.copyOf(photos);
    }

    /**
     * 날짜 → 그 날의 사진 경로 — 시음일에 사진을 붙이는 쪽이 쓴다.
     *
     * <p><b>시음일이 없는 날짜의 사진도 그대로 들어 있다</b>. 그런 조합은 실재한다(사진 확정 뒤 시음일만
     * 날짜 이동한 경우) — 여기서 걸러내지 않고, 시음일 목록을 도는 소비처가 자연히 지나친다. 이 자리에서
     * 지우면 색인은 남았는데 어디서도 보이지 않는 사진이 생겨 원인 추적이 끊긴다.
     */
    public Map<LocalDate, List<String>> photosOn() {
        Map<LocalDate, List<String>> byDate = new LinkedHashMap<>();
        for (NotePhoto photo : photos) {
            byDate.computeIfAbsent(photo.date(), date -> new ArrayList<>()).add(photo.path());
        }
        return byDate;
    }
}
