package com.devwuu.mocha.pipeline;

import java.time.LocalDate;
import java.util.List;

/**
 * 추출 요청에 실리는 기존 노트 매칭 후보 (ref: data-model.md#3 existing_notes).
 * <p>LLM이 수신 메시지의 커피가 기존 노트와 동일한지 판정(matched_slug)하도록 식별 정보를 넘긴다.
 * changes/0016(plan ADR-37)에서 동일성 판단 재료를 넓히기 위해 <b>별칭·원산지·official_notes·최근 시음일</b>을
 * 후보에 확장했다 — 표기가 갈린 발화("FroB" vs "FroB Coffee roasters")도 컨텍스트로 같은 노트를 가리키게 한다.
 * 매칭의 서버측 확정(정규화 별칭 대조)은 이후 {@link NoteMatcher}가 맡는다(LLM 판정 보조).
 *
 * @param slug          기존 노트 식별자.
 * @param coffeeName    표시용 커피 이름.
 * @param roastery      로스터리(있으면).
 * @param aliases       coffee_name·roastery 이표기 통합 목록(내부 별칭, 표시값과 별개). 없으면 빈 목록.
 * @param origin        원산지(있으면 null 아님) — 동일성 판단 보조.
 * @param officialNotes 로스터리 공식 테이스팅 노트(없으면 빈 목록) — 동일성 판단 보조.
 * @param lastTasted    최근 시음일(YYYY-MM-DD, 없으면 null) — "저번에 마신" 참조 해석 보조.
 */
public record NoteCandidate(
        String slug,
        String coffeeName,
        String roastery,
        List<String> aliases,
        String origin,
        List<String> officialNotes,
        LocalDate lastTasted) {

    public NoteCandidate {
        aliases = aliases == null ? List.of() : aliases;
        officialNotes = officialNotes == null ? List.of() : officialNotes;
    }

    /** 별칭·메타 확장 전(changes/0016 이전) 시그니처 호환 — 식별 정보만 담고 확장 필드는 빈 값/null. */
    public NoteCandidate(String slug, String coffeeName, String roastery) {
        this(slug, coffeeName, roastery, List.of(), null, List.of(), null);
    }
}
