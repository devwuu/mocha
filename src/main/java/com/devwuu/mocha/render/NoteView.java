package com.devwuu.mocha.render;

import com.devwuu.mocha.domain.Rating;
import com.devwuu.mocha.domain.Recipe;

import java.time.LocalDate;
import java.util.List;

/**
 * 렌더 템플릿에 넘기는 표시용 뷰 모델 — 도메인 {@link com.devwuu.mocha.domain.Note}를 화면 표현으로 축약한다
 * (날짜 포맷은 {@link KoreanDates}, 파생 수치 표기는 {@link RecipeAmounts}가 렌더 시 계산).
 * <p>카드 단위 = <b>회차 파트 1건</b>(감상 {@link TasteCard} / 레시피 {@link RecipeCard} — changes/0021
 * ADR-54·59). 인덱스 뷰는 index 폐기(ADR-55, TΔ6)와 함께 소멸했다.
 */
public final class NoteView {

    private NoteView() {
    }

    /**
     * 감상 카드 뷰 — {@code templates/<theme>/taste.html}의 바인딩 계약
     * (ref: FR-7 감상 카드 필드↔영역 매핑, changes/0021 ADR-54·59, TΔ4a).
     * <p>카드 단위 = <b>회차 1개의 감상 파트</b>: 노트 메타(커피명·로스터리·beans·roast_level·official_notes)
     * + 그 회차 {@code tasting}(my_taste·rating). 회차 번호는 카드에 표기하지 않는다(파일명만 — ADR-54 POLICY).
     * <p>출처 표기({@code ·검색} 배지)와 sources 영역은 시안·FR-7 매핑에 없다 — 값만 평문으로 담는다.
     * {@code myTaste}가 있는 회차만 이 카드를 굽는다(AC-25·78) — {@code myTaste}는 null 아님 전제.
     * <p>{@link ThymeleafNoteRenderer}가 회차 파트에서 이 뷰를 조립한다(TΔ5a).
     */
    public record TasteCard(
            String coffeeName,
            String roastery,
            List<BeanLine> beans,
            String roastLevel,
            List<String> officialNotes,
            LocalDate date,
            String myTaste,
            Rating rating) {
    }

    /** 감상 카드의 원두 1행 — {@link com.devwuu.mocha.domain.Bean}의 표시 축약(설명 + 가공방식, 출처 무표기). */
    public record BeanLine(String description, String process) {
    }

    /**
     * 레시피 카드 뷰 — {@code templates/<theme>/recipe.html}의 바인딩 계약
     * (ref: FR-7 레시피 카드 필드↔영역 매핑, changes/0021 ADR-54·59, TΔ4b).
     * <p>카드 단위 = <b>회차 1개의 레시피 파트</b>: 노트 메타(커피명·로스터리) + 그 회차 {@code recipe}.
     * {@code recipe}가 있는 회차만 이 카드를 굽는다(AC-25·78) — {@code recipe}는 null 아님 전제.
     * 회차 번호는 카드에 표기하지 않는다(파일명만 — ADR-54 POLICY).
     * <p>비율·시간·grind 서브라벨은 저장하지 않는 파생 표기다 — {@link RecipeAmounts}가 렌더 시 계산한다(ADR-54).
     * <p>{@link ThymeleafNoteRenderer}가 회차 파트에서 이 뷰를 조립한다(TΔ5a).
     */
    public record RecipeCard(
            String coffeeName,
            String roastery,
            LocalDate date,
            Recipe recipe) {

        /**
         * 수치 타일 변형 분기 — 시안의 핸드드립/에스프레소가 갈리는 지점(findings-TΔ0 §5, Thymeleaf 분기 통합).
         * <p>POLICY: {@code method}는 자유 문자열이라 "에스프레소" <b>포함</b>이면 에스프레소 변형
         * (도징·추출량+비율·추출 시간 타일), 그 외(null 포함)는 핸드드립 변형(원두·물·분쇄도 타일)으로 조판한다.
         * 어느 변형이든 값 있는 항목만 표시하고 타일에 못 오른 값은 라벨 행으로 내려간다 — 변형은 배치만 바꾸며
         * 정보를 떨어뜨리지 않는다 (ref: FR-7 "값 있는 항목만", v7-recipe-discussion Q35).
         */
        public boolean espressoLayout() {
            return recipe.method() != null && recipe.method().contains("에스프레소");
        }

        // 시간의 표시 가능 여부는 표기 단일 소스(RecipeAmounts.time)에 위임 — 반올림 0초 등 표기 불가 값이
        // 타일·행 유무 판정만 참으로 만드는 어긋남 방지(changes/0025 리뷰 후속, 템플릿 가드와 동일 기준).

        /** 수치 타일 영역에 오를 값이 하나라도 있는가 — 없으면 타일 행 자체를 숨긴다(구 AC-25 승계). */
        public boolean hasNumericTiles() {
            return espressoLayout()
                    ? recipe.doseG() != null || recipe.yieldMl() != null || RecipeAmounts.time(recipe.timeSec()) != null
                    : recipe.doseG() != null || recipe.waterMl() != null || recipe.grind() != null;
        }

        /** 라벨 그리드(타일 아래 상세 행)에 오를 값이 하나라도 있는가 — 없으면 영역 자체를 숨긴다. */
        public boolean hasDetailRows() {
            return espressoLayout()
                    ? recipe.grind() != null || recipe.machine() != null || recipe.tempC() != null
                            || recipe.waterMl() != null || recipe.pouring() != null
                    : recipe.tempC() != null || recipe.machine() != null || recipe.yieldMl() != null
                            || recipe.pouring() != null || RecipeAmounts.time(recipe.timeSec()) != null;
        }
    }
}
