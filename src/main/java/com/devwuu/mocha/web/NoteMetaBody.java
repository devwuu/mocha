package com.devwuu.mocha.web;

import com.devwuu.mocha.domain.Bean;
import com.devwuu.mocha.domain.NoteMeta;
import com.devwuu.mocha.domain.Sourced;

import java.util.List;

/**
 * {@code PATCH /api/notes/&#123;id&#125;} 요청 — 저장된 노트의 <b>사실</b> 수정
 * (ref: changes/0029 tasks.md TΔ13b·TΔ5b-3, 계약 정본 {@code contract/note-update.contract.json}).
 *
 * <p><b>{@code coffeeName} 필드가 없는 것이 이 타입의 존재 이유다.</b> 커피명은 노트 생성 후 불변이고
 * (V-9) 이름이 다르면 다른 커피다 — 필드를 두지 않아 <b>구조로 차단</b>한다. 구 {@code propose_edit}
 * patch 스키마가 같은 방식으로 막던 자리이고(data-model §3.4), 그 tool이 TΔ1에서 사라지며 잠시 비었다.
 * {@code NoteTxService.updateMeta}의 저장값 대조는 그 아래의 최종 방어선으로 남는다(TΔ4a) — 이 경로에서
 * 그 검사는 <b>항상 통과</b>하지만, 지우지 않는 이유는 호출부가 하나 더 생기는 날의 방어선이어서다.
 *
 * <p>필드 목록은 {@link NoteDetailBody}에서 {@code noteId}·{@code coffeeName}·{@code tasting_days}를 뺀 것과
 * 정확히 같다 — 수정 폼이 {@code GET /api/notes/&#123;id&#125;}를 그대로 딛기 때문이다. <b>출처를 함께
 * 싣는 이유가 그것이다</b>: 고치지 않은 필드가 원래 출처를 유지해야 이후 검색 보강이 닿을 수 있는 값으로
 * 남는다(V-6이 막으려던 방향의 반대편 사고).
 *
 * <p>필드 순서가 곧 JSON 필드 순서이고 계약 파일과 짝이다.
 */
public record NoteMetaBody(
        Sourced<String> roastery,
        List<Bean> beans,
        Sourced<String> roastLevel,
        Sourced<List<String>> officialNotes,
        List<String> sources) {

    /**
     * 도메인 메타로 되돌린다 — 커피명은 <b>저장된 값</b>이 채운다.
     *
     * <p>클라이언트가 보낼 수 없는 값이라 서버가 실어야 하고, 지어낼 것이 아니라 이미 그 노트에 있는
     * 것이다. 그래서 인자로 받는다 — 여기서 조회하면 이 record가 저장소를 알게 된다.
     */
    public NoteMeta toMeta(Sourced<String> storedCoffeeName) {
        return new NoteMeta(storedCoffeeName, roastery, beans, roastLevel, officialNotes, sources);
    }
}
