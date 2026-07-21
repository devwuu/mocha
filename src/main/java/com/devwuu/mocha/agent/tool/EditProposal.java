package com.devwuu.mocha.agent.tool;

import com.devwuu.mocha.domain.Bean;
import com.devwuu.mocha.domain.Brew;
import com.devwuu.mocha.domain.Sourced;

import java.time.LocalDate;
import java.util.List;

/**
 * {@code propose_edit} 서버 검증(V-1·5·8·10·11·14·15, 단일 대기)을 통과한 수정 제안 — 도메인 타입으로
 * 정규화된 형태. pending(mode=edit) draft 갱신·✏️ 미리보기 전송(TΔ6)의 입력이 된다
 * (ref: specs/coffee-note-agent/data-model.md#3.4, plan#ADR-45·59).
 * <p>patch 필드는 null = 유지. coffee_name은 인자 스키마에 없어 여기까지 올 수 없다(V-9 구조 차단).
 *
 * @param slug          수정 대상 노트.
 * @param targetDate    수정 대상 엔트리 날짜 — pending target의 근거.
 * @param roastery      로스터리 새 값 — null이면 유지. 이하 동일.
 * @param beans         원두 구성 새 값(V-14 정규화, 통째 교체) — null이면 유지 (changes/0021 ADR-53).
 * @param roastLevel    로스팅 정도.
 * @param officialNotes 공식 테이스팅 노트.
 * @param brews         회차 배열 새 값(V-15 정규화, <b>통째 교체</b>) — null이면 유지. 교체 결과 회차 0개는
 *                      검증이 거부해 여기까지 오지 않는다 (changes/0021 ADR-59).
 * @param newDate       날짜 이동처 — 이동 없으면 null(대상 자신의 날짜로의 "이동"도 null로 정규화).
 * @param dateConflict  이동처에 대상 노트의 기존 엔트리가 있는지 — 서버 계산(V-10), 미리보기 경고 근거.
 */
public record EditProposal(
        String slug,
        LocalDate targetDate,
        Sourced<String> roastery,
        List<Bean> beans,
        Sourced<String> roastLevel,
        Sourced<List<String>> officialNotes,
        List<Brew> brews,
        LocalDate newDate,
        boolean dateConflict
) {
}
