package com.devwuu.mocha.llm;

import java.util.List;

/**
 * 커피 이미지 OCR·구조화 추출 결과 (ref: specs/coffee-note-agent/plan.md#ADR-23, §3 read(imageUrls, hint)).
 * <p>원두 봉투·커피 노트 카드·상품 상세처럼 <b>텍스트로 존재하지 않는</b> 스펙(커피명·원산지·가공·로스팅·
 * 공식 노트)을 vision(OCR)으로 읽어낸 후보 값 묶음이다. 수신 사진 OCR(FR-19 — 루프 전 전처리, ADR-23)이
 * 주 소비자이고, 예비 {@code read_page_images} tool(ADR-49, 비활성)이 같은 경계를 재사용한다(NFR-4).
 * "추측 금지" — 이미지에서 확인 안 되는 값은 null(문자열)·빈 리스트로 온다.
 * <p>{@code sources}가 없다 — 출처 표기는 이 값을 소비하는 상위(에이전트 제안·미리보기)가 photo 출처로
 * 다룬다(V-5·V-6).
 *
 * @param coffeeName    커피 이름 — 이미지에 표시된 상품명. 확인 안 되면 null.
 * @param roastery      로스터리(이미지에서 확인된 값, 보통 null — 이미 문맥으로 주어짐).
 * @param origin        원산지(블렌드는 여러 원산지를 쉼표 문자열로 나열, ADR-14와 일관).
 * @param process       가공 방식.
 * @param roastLevel    로스팅 정도.
 * @param officialNotes 로스터리 공식 테이스팅 노트 — 이미지에서 확인 안 되면 빈 리스트.
 */
public record VisionExtraction(
        String coffeeName,
        String roastery,
        String origin,
        String process,
        String roastLevel,
        List<String> officialNotes) {

    public VisionExtraction {
        officialNotes = officialNotes == null ? List.of() : List.copyOf(officialNotes);
    }

    /** 이미지에서 아무것도 못 읽은 경우(호출/형식 실패 포함, AC-Δ2) — 모든 필드 공란. */
    public static VisionExtraction empty() {
        return new VisionExtraction(null, null, null, null, null, List.of());
    }
}
