package com.devwuu.mocha.render;

import com.devwuu.mocha.domain.Brew;
import com.devwuu.mocha.domain.Entry;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 회차 카드 JPG 파일 경로 규약 — {@code artifact/cards/<slug>/<date>-taste-<n>.jpg}·{@code <date>-recipe-<n>.jpg},
 * n = 회차 번호(= brews 배열 순서, 1부터) (ref: data-model.md §2.4, plan.md#ADR-54·59, changes/0021 TΔ5a).
 * <p>렌더러(산출·정리)와 카드 재전송(send_entry_card의 파생물 재사용 판정 — data-model §3.5)이 같은 규약을
 * 공유하도록 한곳에 모은다. 카드 위에 회차를 표기하지 않으므로 파일명이 회차 구분의 유일한 표현이다(ADR-54 POLICY).
 */
public final class CardFiles {

    static final String CARDS_DIR = "cards";

    private CardFiles() {
    }

    /** 감상 카드 경로 — tasting 있는 회차만 산출된다(AC-78). */
    public static Path tasteCard(Path artifactDir, String slug, LocalDate date, int brewNumber) {
        return cardsDir(artifactDir, slug).resolve(date + "-taste-" + brewNumber + ".jpg");
    }

    /** 레시피 카드 경로 — recipe 있는 회차만 산출된다(AC-78). */
    public static Path recipeCard(Path artifactDir, String slug, LocalDate date, int brewNumber) {
        return cardsDir(artifactDir, slug).resolve(date + "-recipe-" + brewNumber + ".jpg");
    }

    /**
     * 엔트리의 기대 카드 경로 전부 — 회차 오름차순, 회차 안에서는 감상 → 레시피.
     * 렌더 산출 순서·재사용 판정("전부 존재")·배달 순서의 기준 집합이다.
     */
    public static List<Path> expectedCards(Path artifactDir, String slug, Entry entry) {
        List<Path> expected = new ArrayList<>();
        List<Brew> brews = entry.brews();
        for (int i = 0; i < brews.size(); i++) {
            int n = i + 1; // 배열 순서 = 회차 번호(ADR-59)
            if (brews.get(i).tasting() != null) {
                expected.add(tasteCard(artifactDir, slug, entry.date(), n));
            }
            if (brews.get(i).recipe() != null) {
                expected.add(recipeCard(artifactDir, slug, entry.date(), n));
            }
        }
        return expected;
    }

    /** 그 엔트리(날짜)의 카드 파일 글롭 — 날짜 이동·회차 감소 시 옛 카드 전부 정리에 쓴다(AC-39). */
    static String entryCardGlob(LocalDate date) {
        return date + "-*.jpg";
    }

    private static Path cardsDir(Path artifactDir, String slug) {
        return artifactDir.resolve(CARDS_DIR).resolve(slug);
    }
}
