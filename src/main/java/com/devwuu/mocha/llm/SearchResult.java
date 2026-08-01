package com.devwuu.mocha.llm;

import java.util.List;

/**
 * 웹 검색 보강 결과 (ref: specs/coffee-note-agent/plan.md#ADR-5, spec FR-3; changes/0029 TΔ24b).
 * <p>검색이 찾아낸 후보 값 묶음 — 아직 출처 마킹·병합 전이다. 어느 필드를 draft에 실제로 반영할지는
 * {@code agent/turn/TurnProposalEnricher}가 정한다(빈 필드만 채움 = V-6 자동 충족). 여기 담긴 값은 전부
 * "검색이 말하는 것"이며, 못 찾은 필드는 null(문자열)·빈 리스트로 온다.
 * <p>보강 규칙(로스터리 공식 우선 + 신뢰할 일반 출처 fallback, official_notes 로스터리 출처 한정,
 * 동일성 가드, FR-3/ADR-14/ADR-16)은 구현체의 검색 지침이 이미 적용한 뒤의 결과다.
 * <p>{@code coffee_name}이 없다 — 검색은 앵커로 받은 커피명을 만들지 않는다(V-5: coffee_name의 source는
 * {user, photo} 한정).
 *
 * @param roastery      로스터리(검색이 확인/보강한 값, 보통 null — 이미 사용자 언급).
 * @param beans         원두 구성 후보 — 원두별 설명·가공방식(구 {@code origin}/{@code process} 대체,
 *                      changes/0021 ADR-53). 블렌드는 구성 원두마다 요소, 못 찾으면 빈 리스트.
 * @param roastLevel    로스팅 정도.
 * @param officialNotes 로스터리 전시 테이스팅 노트 — 로스터리 출처 없으면 빈 리스트(FR-3).
 * @param sources       참조 링크(FR-12).
 */
public record SearchResult(
        String roastery,
        List<Bean> beans,
        String roastLevel,
        List<String> officialNotes,
        List<String> sources) {

    /**
     * 원두 1종 후보 — {@code beans} 배열의 요소 (ref: data-model.md#2.1, changes/0021 ADR-53).
     * <p>도메인 {@link com.devwuu.mocha.domain.Bean}과 달리 출처 표시가 없다 — 소비자가 search 출처를 얹는다.
     * {@link VisionExtraction.Bean}과 같은 형태이나 경계가 달라 타입을 공유하지 않는다(vision ↔ 검색).
     *
     * @param description 원산지·품종 등을 묶은 자유 텍스트(한국어 표기) — 품종은 확인되면 포함.
     * @param process     그 원두의 가공방식(한국어 관용 표기) — 확인 안 되면 null.
     */
    public record Bean(String description, String process) {
    }

    public SearchResult {
        beans = normalizeBeans(beans);
        officialNotes = officialNotes == null ? List.of() : List.copyOf(officialNotes);
        sources = sources == null ? List.of() : List.copyOf(sources);
    }

    // V-14 준용 위생 — null 배열은 빈 배열로, description이 빈 요소는 드롭, 빈 process는 null로.
    // (모델 출력 후보 단계의 정규화 — 도메인 진입 시 Bean.normalize가 다시 강제한다. VisionExtraction과 동형.)
    private static List<Bean> normalizeBeans(List<Bean> raw) {
        if (raw == null) {
            return List.of();
        }
        return raw.stream()
                .filter(b -> b != null && b.description() != null && !b.description().isBlank())
                .map(b -> new Bean(
                        b.description().strip(),
                        b.process() == null || b.process().isBlank() ? null : b.process().strip()))
                .toList();
    }

    /** 검색이 아무것도 못 찾은 경우(호출·형식 실패 포함, AC-12) — 모든 필드 공란. */
    public static SearchResult empty() {
        return new SearchResult(null, List.of(), null, List.of(), List.of());
    }

    /** 채운 값이 하나도 없는가 — 진짜 무결과와 호출 실패를 구분해 관측하기 위한 판정(plan §6). */
    public boolean isEmpty() {
        return equals(empty());
    }
}
