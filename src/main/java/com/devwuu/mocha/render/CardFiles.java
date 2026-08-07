package com.devwuu.mocha.render;

import com.devwuu.mocha.domain.Cup;
import com.devwuu.mocha.domain.TastingDay;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.repository.NoteFolderName;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 회차 카드 JPG 파일 경로 규약 — {@code artifact/cards/<접미>/<date>-taste-<n>.jpg}·{@code <date>-recipe-<n>.jpg},
 * n = 회차 번호(= cups 배열 순서, 1부터) (ref: data-model.md §2.4, plan.md#ADR-54·59, changes/0021 TΔ5a).
 * <p>렌더러(산출·정리)와 카드 파생물 재사용 판정(data-model §3.5)이 같은 규약을
 * 공유하도록 한곳에 모은다. 카드 위에 회차를 표기하지 않으므로 파일명이 회차 구분의 유일한 표현이다(ADR-54 POLICY).
 * <p>폴더 접미({@code <id>-<로스터리>-<커피명>})는 <b>노트에서 여기가 직접 만든다</b> — 인자로 문자열을 받으면
 * 사진과 카드가 다른 접미로 갈릴 수 있다. 조립 규칙 자체는 {@link NoteFolderName}이 단일 소유한다
 * (ref: changes/0028-rdb-storage/delta.md §파일 경로 규약, TΔ6c).
 */
public final class CardFiles {

    /** 카드 뿌리 디렉터리명. 노트 삭제의 카드 정리(0029 TΔ8b)가 렌더러 밖에서 같은 규약을 쓴다. */
    public static final String CARDS_DIR = "cards";

    private CardFiles() {
    }

    /**
     * 종류로 고르는 카드 경로 — 온디맨드 API가 쓴다(TΔ9).
     *
     * <p>{@link CardType#id()}가 곧 파일명 세그먼트라 분기가 형식적으로 보이지만, 두 메서드를 남겨 두는
     * 것은 산출 쪽({@code bakeTastingDayCards})이 여전히 파트별로 부르기 때문이다 — 여기서 문자열을 이어
     * 붙이면 규약이 <i>"이름을 만드는 규칙"</i>과 <i>"이름을 고르는 규칙"</i>으로 갈린다.
     */
    public static Path card(Path artifactDir, Note note, LocalDate date, CardType type, int cupNumber) {
        return type == CardType.TASTE
                ? tasteCard(artifactDir, note, date, cupNumber)
                : recipeCard(artifactDir, note, date, cupNumber);
    }

    /** 감상 카드 경로 — review 있는 회차만 산출된다(AC-78). */
    public static Path tasteCard(Path artifactDir, Note note, LocalDate date, int cupNumber) {
        return noteCardsDir(artifactDir, note).resolve(date + "-taste-" + cupNumber + ".jpg");
    }

    /** 레시피 카드 경로 — recipe 있는 회차만 산출된다(AC-78). */
    public static Path recipeCard(Path artifactDir, Note note, LocalDate date, int cupNumber) {
        return noteCardsDir(artifactDir, note).resolve(date + "-recipe-" + cupNumber + ".jpg");
    }

    /**
     * 엔트리의 기대 카드 경로 전부 — 회차 오름차순, 회차 안에서는 감상 → 레시피.
     * 렌더 산출 순서·재사용 판정("전부 존재")·배달 순서의 기준 집합이다.
     */
    public static List<Path> expectedCards(Path artifactDir, Note note, TastingDay tastingDay) {
        List<Path> expected = new ArrayList<>();
        List<Cup> cups = tastingDay.cups();
        for (int i = 0; i < cups.size(); i++) {
            int n = i + 1; // 배열 순서 = 회차 번호(ADR-59)
            if (cups.get(i).review() != null) {
                expected.add(tasteCard(artifactDir, note, tastingDay.date(), n));
            }
            if (cups.get(i).recipe() != null) {
                expected.add(recipeCard(artifactDir, note, tastingDay.date(), n));
            }
        }
        return expected;
    }

    /** 그 엔트리(날짜)의 카드 파일 글롭 — 날짜 이동·회차 감소 시 옛 카드 전부 정리에 쓴다(AC-39). */
    static String tastingDayCardGlob(LocalDate date) {
        return date + "-*.jpg";
    }

    /** 그 노트의 카드 폴더 — 잔존 카드 정리(글롭 순회)가 쓴다. */
    static Path noteCardsDir(Path artifactDir, Note note) {
        return artifactDir.resolve(CARDS_DIR).resolve(NoteFolderName.of(note));
    }
}
