package com.devwuu.mocha.pipeline;

import com.devwuu.mocha.domain.Rating;

import java.time.LocalDate;
import java.util.List;

/**
 * pending 수정 요청의 LLM 병합 응답 (ref: tasks T3-4; spec FR-5/AC-5). structured output 스키마가 강제하는
 * "이번 수정으로 바뀌는 필드"만 담는 패치 값객체다.
 * <p>POLICY: 각 필드는 <b>null이면 변경 없음</b>이다 — 사용자가 명시적으로 바꾸라고 한 필드에만 새 값이 실린다.
 * NoteExtractor의 "언급 없으면 null"(FR-2)과 같은 규약을 수정 맥락으로 재사용한다. 어떤 필드가 채워졌는지로
 * {@link PendingReviser}가 draft를 부분 갱신하고, 갱신된 출처 표시 필드는 {@code source=user}로 승격한다
 * (사용자가 방금 그 값을 직접 지정했으므로).
 *
 * @param coffeeName    커피 이름 새 값(변경 없으면 null).
 * @param roastery      로스터리 새 값(변경 없으면 null) — 승격 대상.
 * @param origin        원산지 새 값(변경 없으면 null) — 승격 대상.
 * @param process       가공 방식 새 값(변경 없으면 null) — 승격 대상.
 * @param roastLevel    로스팅 정도 새 값(변경 없으면 null) — 승격 대상.
 * @param officialNotes 로스터리 노트 새 값(변경 없으면 null). 사용자가 직접 불러주면 source=user 허용(data-model §2.1).
 * @param myTaste       내가 느낀 맛 새 값(변경 없으면 null) — 엔트리 필드.
 * @param rating        4범주 평가 새 값(변경 없으면 null) — 엔트리 필드. 4범주 외 값은 역직렬화에서 거부(V-1).
 * @param date          시음 날짜 새 값(변경 없으면 null) — 엔트리 필드. edit 모드의 날짜 이동(FR-21/AC-39,
 *                      changes/0012 TΔ5) 전용이며 record 모드에서는 무시한다(AC-Δ6 신규 기록 경로 불변).
 */
public record RevisionResult(
        String coffeeName,
        String roastery,
        String origin,
        String process,
        String roastLevel,
        List<String> officialNotes,
        String myTaste,
        Rating rating,
        LocalDate date
) {
}
