package com.devwuu.mocha.render;

import com.devwuu.mocha.domain.Rating;
import com.devwuu.mocha.domain.Recipe;
import com.devwuu.mocha.domain.Sourced;

import java.time.LocalDate;
import java.util.List;

/**
 * 렌더 템플릿에 넘기는 표시용 뷰 모델 — 도메인 {@link com.devwuu.mocha.domain.Note}를 화면 표현으로 축약한다
 * (날짜 포맷은 {@link KoreanDates}, 출처 배지는 {@code source} 필드, 상대 경로는 렌더러가 계산).
 * <p>카드 단위 = <b>시음 엔트리 1건</b>(커피×날짜, ADR-10). 인덱스 행({@link Row})은 엔트리 1건을
 * 최신순으로 요약해 카드 JPG로 링크하고, 카드 뷰({@link EntryCard})는 대상 엔트리 1건만 렌더한다
 * (ref: changes/0002-instagram-share-card delta, FR-7/AC-13).
 */
public final class NoteView {

    private NoteView() {
    }

    /** 인덱스 페이지 뷰 (ref: FR-8). rows는 엔트리 기록일 내림차순. */
    public record Index(int noteCount, int recordCount, List<Row> rows) {
    }

    /**
     * 인덱스의 시음 엔트리 1행 (ref: FR-8, ADR-10). {@code href}는 {@code cards/<slug>/<date>.jpg} 상대 링크(AC-Δ5).
     * <p>같은 커피를 여러 날 기록하면 엔트리마다 별도 행이 생긴다 — 노트당 1행이 아니라 엔트리당 1행.
     */
    public record Row(
            String href,
            String coffeeName,
            String roastery,
            String origin,
            LocalDate date,
            Rating rating) {
    }

    /**
     * 시음 엔트리 카드 뷰 (ref: FR-7, ADR-10). 노트 메타(로스터리·원두 구성·official_notes·출처) + 대상 엔트리 1건.
     * 출처 표시 필드는 {@link Sourced} 그대로 넘겨 {@code (검색)} 표기 분기(AC-2).
     * <p>TΔ1a 과도기: {@code origin}/{@code process}는 beans 요약(설명·가공 나열)을 물린 표시용 필드다 —
     * TΔ4 카드 2종 이식에서 beans 네이티브 바인딩으로 대체된다(changes/0021 ADR-54).
     * <p>카드는 커피의 <b>그 한 잔</b>만 담는다 — 노트 전체 타임라인이 아니라 {@code entry} 1건(AC-Δ4).
     * "로스터리가 말하길"(officialNotes)은 노트 단위, "내가 느끼길"(entry.myTaste)은 그 엔트리 1건(FR-7).
     * <p>{@code coffeeName}은 {@link Sourced}로 승격됐지만(source ∈ {user, photo}) 카드 제목은 <b>출처 무표기</b>다 —
     * 제목=커피의 정체성이라 {@code (사진)} 배지를 달지 않는다({@code coffeeName.value}만 렌더, delta AC-Δ4·TΔ6).
     */
    public record EntryCard(
            String slug,
            Sourced<String> coffeeName,
            Sourced<String> roastery,
            Sourced<String> origin,
            Sourced<String> process,
            Sourced<String> roastLevel,
            List<String> officialNotes,
            List<String> sources,
            EntryView entry) {
    }

    /**
     * 카드의 날짜 엔트리 1건 (ref: FR-15, data-model §2.2). 사진은 렌더에 실리지 않는다 — 아카이브 전용이라
     * 카드/인덱스는 사진을 읽지 않는다(changes/0014 ADR-32 POLICY, AC-Δ2).
     * {@code recipe}는 "이렇게 내렸어요" 영역용 — 3항목 전무·미언급 시 {@code null}이라 영역 자체를 숨긴다(FR-18, AC-Δ2·TΔ6).
     */
    public record EntryView(
            LocalDate date,
            String myTaste,
            Rating rating,
            Recipe recipe) {
    }

    /**
     * 감상 카드 뷰 — {@code templates/<theme>/taste.html}의 바인딩 계약
     * (ref: FR-7 감상 카드 필드↔영역 매핑, changes/0021 ADR-54·59, TΔ4a).
     * <p>카드 단위 = <b>회차 1개의 감상 파트</b>: 노트 메타(커피명·로스터리·beans·roast_level·official_notes)
     * + 그 회차 {@code tasting}(my_taste·rating). 회차 번호는 카드에 표기하지 않는다(파일명만 — ADR-54 POLICY).
     * <p>출처 표기({@code ·검색} 배지)와 sources 영역은 시안·FR-7 매핑에 없다 — 값만 평문으로 담는다.
     * {@code myTaste}가 있는 회차만 이 카드를 굽는다(AC-25·78) — {@code myTaste}는 null 아님 전제.
     * <p>렌더러가 이 뷰를 조립하는 배선은 TΔ5a — 그 전까지는 템플릿·테스트가 직접 구성한다.
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
     * <p>렌더러가 이 뷰를 조립하는 배선은 TΔ5a — 그 전까지는 템플릿·테스트가 직접 구성한다.
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

        /** 수치 타일 영역에 오를 값이 하나라도 있는가 — 없으면 타일 행 자체를 숨긴다(구 AC-25 승계). */
        public boolean hasNumericTiles() {
            return espressoLayout()
                    ? recipe.doseG() != null || recipe.yieldMl() != null || recipe.timeSec() != null
                    : recipe.doseG() != null || recipe.waterMl() != null || recipe.grind() != null;
        }

        /** 라벨 그리드(타일 아래 상세 행)에 오를 값이 하나라도 있는가 — 없으면 영역 자체를 숨긴다. */
        public boolean hasDetailRows() {
            return espressoLayout()
                    ? recipe.grind() != null || recipe.machine() != null || recipe.tempC() != null
                            || recipe.waterMl() != null || recipe.pouring() != null
                    : recipe.tempC() != null || recipe.machine() != null || recipe.yieldMl() != null
                            || recipe.pouring() != null || recipe.timeSec() != null;
        }
    }
}
