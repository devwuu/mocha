package com.devwuu.mocha.render;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

/**
 * 파이프라인 [6] — 회차 카드 산출 경계 (ref: plan.md §1 [6], §3; ADR-1, ADR-7, ADR-10, ADR-54·59).
 * <p>JSON(원본)만으로 회차 카드 JPG({@code cards/<slug>/<date>-taste-<n>.jpg}·{@code <date>-recipe-<n>.jpg},
 * n = 회차)를 재생성한다. 산출물은 파생물이며 삭제해도 데이터 손실이 없어야 한다(NFR-3, AC-6).
 * 카드 HTML은 굽는 순간의 중간 입력이라 파일로 남기지 않는다(ADR-10). 갤러리 페이지는 폐기됐다(ADR-9).
 * <p>POLICY: 렌더러는 JSON 외 어떤 상태도 읽지 않는다 — 전체 리렌더 가능성 보장 (ref: plan.md#ADR-1, AC-6).
 * <p>구현: {@link ThymeleafNoteRenderer}. CLI {@code --rerender}로 전체 재생성({@code RerenderRunner}),
 * 저장 커밋 직후엔 {@link #renderEntryCard}로 방금 엔트리의 카드만 증분 렌더한다(AC-Δ7, changes/0002 TΔ4/TΔ5).
 */
public interface NoteRenderer {

    /** 모든 엔트리의 회차 카드 JPG 전체 리렌더(AC-Δ7). {@code --rerender}가 쓴다. */
    void renderAll();

    /**
     * 대상 엔트리의 회차 카드 전부(tasting 있는 회차의 감상 카드 + recipe 있는 회차의 레시피 카드 —
     * AC-78)를 굽고 경로 목록을 반환한다(회차 오름차순, 회차 안에서는 감상 → 레시피). 재생성 전
     * 그 엔트리의 옛 카드 파일을 정리해 회차 감소·파트 소멸 재저장의 잔존 카드를 없앤다(changes/0021 TΔ5a).
     * 저장 커밋 직후 증분 렌더 — 전체 재래스터화 없이 방금 그 엔트리 카드만 굽는다(AC-Δ7).
     *
     * @param slug 대상 노트 slug.
     * @param date 대상 엔트리 날짜.
     * @return 구워진 회차 카드 JPG 경로 목록.
     */
    List<Path> renderEntryCard(String slug, LocalDate date);

    /**
     * 수정 세션 날짜 이동 시 옛 날짜의 회차 카드 파생물 전부({@code cards/<slug>/<date>-*.jpg})를
     * 정리한다(ADR-27, AC-39). 파일이 없어도 무해(멱등). 삭제 실패로 남은 카드는
     * {@link #renderAll}({@code --rerender})이 정리하므로 호출부는 실패를 커밋 롤백 사유로 삼지 않는다(plan §7).
     *
     * @param slug 대상 노트 slug.
     * @param date 삭제할 카드의 엔트리 날짜(이동 전 원본 date).
     */
    void removeEntryCard(String slug, LocalDate date);
}
