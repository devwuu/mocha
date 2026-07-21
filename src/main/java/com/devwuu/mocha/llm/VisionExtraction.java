package com.devwuu.mocha.llm;

import java.util.List;

/**
 * 커피 이미지 OCR·구조화 추출 결과 (ref: specs/coffee-note-agent/plan.md#ADR-23, §3 read(imageUrls, hint)).
 * <p>원두 봉투·커피 노트 카드·상품 상세처럼 <b>텍스트로 존재하지 않는</b> 스펙(커피명·원두 구성·로스팅·
 * 공식 노트)을 vision(OCR)으로 읽어낸 후보 값 묶음이다. 수신 사진 OCR(FR-19 — 루프 전 전처리, ADR-23)이
 * 주 소비자이고, 예비 {@code read_page_images} tool(ADR-49, 비활성)이 같은 경계를 재사용한다(NFR-4).
 * "추측 금지" — 이미지에서 확인 안 되는 값은 null(문자열)·빈 리스트로 온다.
 * <p>{@code sources}가 없다 — 출처 표기는 이 값을 소비하는 상위(에이전트 제안·미리보기)가 photo 출처로
 * 다룬다(V-5·V-6).
 *
 * @param coffeeName    커피 이름 — 이미지에 표시된 상품명. 확인 안 되면 null.
 * @param roastery      로스터리(이미지에서 확인된 값, 보통 null — 이미 문맥으로 주어짐).
 * @param beans         원두 구성 — 원두별 설명·가공방식(구 origin/process 필드 대체, changes/0021 ADR-53).
 *                      블렌드는 구성 원두마다 요소, 확인 안 되면 빈 리스트.
 * @param roastLevel    로스팅 정도.
 * @param officialNotes 로스터리 공식 테이스팅 노트 — 이미지에서 확인 안 되면 빈 리스트.
 */
public record VisionExtraction(
        String coffeeName,
        String roastery,
        List<Bean> beans,
        String roastLevel,
        List<String> officialNotes) {

    /**
     * 원두 1종 후보 — {@code beans} 배열의 요소 (ref: data-model.md#4.2, changes/0021 ADR-53).
     * <p>도메인 {@link com.devwuu.mocha.domain.Bean}과 달리 출처 표시가 없다 — 소비자가 photo 출처를 얹는다.
     *
     * @param description 원산지·품종 등을 묶은 자유 텍스트(한국어 표기) — 품종은 이미지에서 확인되면 포함.
     * @param process     그 원두의 가공방식(한국어 관용 표기) — 확인 안 되면 null.
     */
    public record Bean(String description, String process) {
    }

    public VisionExtraction {
        officialNotes = officialNotes == null ? List.of() : List.copyOf(officialNotes);
        beans = normalizeBeans(beans);
    }

    // V-14 준용 위생 — null 배열은 빈 배열로, description이 빈 요소는 드롭, 빈 process는 null로.
    // (모델 출력 후보 단계의 정규화 — 도메인 진입 시 Bean.normalize가 다시 강제한다.)
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

    /** 이미지에서 아무것도 못 읽은 경우(호출/형식 실패 포함, AC-Δ2) — 모든 필드 공란. */
    public static VisionExtraction empty() {
        return new VisionExtraction(null, null, List.of(), null, List.of());
    }
}
