package com.devwuu.mocha.render;

import com.devwuu.mocha.domain.Rating;
import com.devwuu.mocha.domain.Recipe;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 렌더 템플릿에 넘기는 표시용 뷰 모델 — 도메인 {@link com.devwuu.mocha.domain.Note}를 화면 표현으로 축약한다
 * (날짜 포맷은 {@link KoreanDates}, 파생 수치 표기는 {@link RecipeAmounts}가 렌더 시 계산).
 * <p>카드 단위 = <b>회차 파트 1건</b>(감상 {@link ReviewCard} / 레시피 {@link RecipeCard} — changes/0021
 * ADR-54·59). 인덱스 뷰는 index 폐기(ADR-55, TΔ6)와 함께 소멸했다.
 */
public final class NoteView {

    private NoteView() {
    }

    /**
     * 감상 카드 뷰 — {@code templates/<theme>/review.html}의 바인딩 계약
     * (ref: FR-7 감상 카드 필드↔영역 매핑, changes/0021 ADR-54·59, TΔ4a).
     * <p>카드 단위 = <b>회차 1개의 감상 파트</b>: 노트 메타(커피명·로스터리·beans·roast_level·official_notes)
     * + 그 회차 {@code review}(my_taste·rating). 회차 번호는 카드에 표기하지 않는다(파일명만 — ADR-54 POLICY).
     * <p>출처 표기({@code ·검색} 배지)와 sources 영역은 시안·FR-7 매핑에 없다 — 값만 평문으로 담는다.
     * {@code myTaste}가 있는 회차만 이 카드를 굽는다(AC-25·78) — {@code myTaste}는 null 아님 전제.
     * <p>{@link ThymeleafNoteRenderer}가 회차 파트에서 이 뷰를 조립한다(TΔ5a).
     */
    public record ReviewCard(
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
         * 3열 그리드에 올릴 타일 — <b>값 있는 항목만</b> 시안 순서(원두·물·시간·온도·추출량·분쇄도)로 담는다.
         * 템플릿은 이 목록을 행 우선으로 흘려 넣기만 하고, 빈 목록이면 그리드 영역 자체를 숨긴다.
         * <p>POLICY: 카드 레이아웃은 {@code method}를 <b>분기 조건으로 쓰지 않는다</b> — 표시 판정의 기준은
         * 오직 "그 값이 있는가"다. 구 {@code espressoLayout()}(= {@code method.contains("에스프레소")})과
         * 그것이 가르던 타일/상세 행 2개 레이아웃은 함께 사라졌다: {@code method}가 자유 문자열인 한
         * "아메리카노"처럼 어느 분기도 아닌 값이 계속 나오고, 그때마다 값이 엉뚱한 영역으로 밀렸다
         * (ref: plan.md#ADR-87, spec FR-7 ③·AC-25, changes/0030 §1.3).
         * <p>POLICY: 타일 6종은 <b>레시피의 수치성 필드 전량</b>과 일치한다(ADR-86 재편 이후) — 그래서
         * "타일에 못 오른 값을 담는 상세 행 영역"이라는 층이 필요 없다. 수치가 아닌 것({@code detail}·
         * {@code feedback})은 타일이 아니라 텍스트 블록이 받는다.
         * <p>POLICY: 분쇄도 타일의 값은 {@code grind}이고 {@code grinder}는 그 서브라벨이다 —
         * <b>그라인더명만 있고 수치가 없으면 타일 자체가 없다</b>(2026-08-10 사용자 확정). 이름은
         * "얼마나 갈았나"의 답이 아니라서 값 자리를 대신 채우지 않고, 카드는 상세 화면과 달리
         * 없는 항목의 자리를 남기지 않는다(AC-25 — 상세 화면의 {@code —} 규칙은 회차 간 비교가 근거였다).
         * <p>시간의 표시 가능 여부는 표기 단일 소스({@link RecipeAmounts#time})에 위임한다 — 반올림 0초 등
         * 표기 불가 값이 타일 유무 판정만 참으로 만드는 어긋남 방지(changes/0025 리뷰 후속).
         */
        public List<Tile> tiles() {
            List<Tile> tiles = new ArrayList<>(6);
            if (recipe.doseG() != null) {
                tiles.add(new Tile("원두", RecipeAmounts.num(recipe.doseG()) + "g", null));
            }
            if (recipe.waterMl() != null) {
                tiles.add(new Tile("물", RecipeAmounts.num(recipe.waterMl()) + "ml", null));
            }
            String time = RecipeAmounts.time(recipe.timeSec());
            if (time != null) {
                tiles.add(new Tile("시간", time, null)); // 표기는 "2분 40초" — 시안의 2:40은 기각(D-7)
            }
            if (recipe.tempC() != null) {
                tiles.add(new Tile("온도", RecipeAmounts.num(recipe.tempC()) + "℃", null));
            }
            if (recipe.yieldMl() != null) {
                // 비율은 도징·추출량 둘 다 있을 때만, 그리고 추출 방식과 무관하게 붙는다(AC-Δ12 — 구 규칙은
                // 에스프레소 변형 전용이었다). 서브라벨 자리라 계산 불가 시 null로 생략된다(ADR-54).
                String ratio = RecipeAmounts.ratio(recipe.doseG(), recipe.yieldMl());
                tiles.add(new Tile("추출량", RecipeAmounts.num(recipe.yieldMl()) + "ml",
                        ratio == null ? null : "비율 " + ratio));
            }
            if (recipe.grind() != null) {
                tiles.add(new Tile("분쇄도", RecipeAmounts.num(recipe.grind()), recipe.grinder()));
            }
            return List.copyOf(tiles);
        }
    }

    /**
     * 레시피 카드의 수치 타일 1칸 — {@link RecipeCard#tiles()}가 값 있는 항목만 담아 돌려준다.
     * 단위·비율 접두는 여기서 이미 붙어 있어 템플릿은 문자열을 그대로 찍는다(두 테마의 타일 어휘·표기가
     * 시안에서 동일하고, 갈리는 것은 색·치수뿐이다 — ADR-87 이식).
     *
     * @param label 타일 라벨("원두"·"물"·"시간"·"온도"·"추출량"·"분쇄도")
     * @param value 단위까지 붙인 표시값("15g"·"2분 40초"·"92℃")
     * @param sub   서브라벨 — 추출량의 "비율 1 : 2", 분쇄도의 그라인더명. 없으면 null(줄 생략)
     */
    public record Tile(String label, String value, String sub) {
    }
}
