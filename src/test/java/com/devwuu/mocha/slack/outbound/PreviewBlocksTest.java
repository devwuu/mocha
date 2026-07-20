package com.devwuu.mocha.slack.outbound;

import com.devwuu.mocha.domain.Bean;
import com.devwuu.mocha.domain.Brew;
import com.devwuu.mocha.domain.Entry;
import com.devwuu.mocha.domain.MatchInfo;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.PendingNote;
import com.devwuu.mocha.domain.Rating;
import com.devwuu.mocha.domain.Recipe;
import com.devwuu.mocha.domain.Sourced;
import com.devwuu.mocha.domain.Tasting;
import com.devwuu.mocha.slack.AgentConversationRouter;
import com.slack.api.model.block.ActionsBlock;
import com.slack.api.model.block.ContextBlock;
import com.slack.api.model.block.HeaderBlock;
import com.slack.api.model.block.LayoutBlock;
import com.slack.api.model.block.SectionBlock;
import com.slack.api.model.block.composition.MarkdownTextObject;
import com.slack.api.model.block.composition.TextObject;
import com.slack.api.model.block.element.ButtonElement;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T3-3: 확인 미리보기 Block Kit 조립 검증 — draft → 블록 구조 스냅샷과 source별 표기 분기(AC-2/AC-15).
 * 순수 함수라 Slack 네트워크 없이 단위로 단언한다(CLAUDE.md §5.3).
 */
class PreviewBlocksTest {

    private final PreviewBlocks previewBlocks = new PreviewBlocks();

    private static Entry entry(String myTaste, Rating rating) {
        return entry(myTaste, rating, null);
    }

    private static Entry entry(String myTaste, Rating rating, Recipe recipe) {
        // 회차 구조(changes/0021 ADR-59) — 구 단일 감상·레시피를 회차 1개로 담는다.
        return new Entry(LocalDate.of(2026, 7, 10),
                List.of(new Brew(recipe, new Tasting(myTaste, null, rating))), OffsetDateTime.now());
    }

    private static String md(TextObject text) {
        return ((MarkdownTextObject) text).getText();
    }

    @Test
    @DisplayName("AC-2/AC-15: 기존 매칭 + 검색 필드에는 (검색) 표기, 사용자 필드에는 표기 없음")
    void existingMatchAndSearchTags() {
        Note draft = new Note(
                "coffeevera-yirgacheffe-g1",
                Sourced.user("커피베라 예가체프 G1"),
                Sourced.user("커피베라"),          // 사용자 값 — (검색) 없음
                List.of(new Bean(Sourced.search("에티오피아"), Sourced.search("워시드"))), // 검색 보강 — (검색)
                null,                               // 미언급 — 필드 생략
                Sourced.search(List.of("자몽", "홍차")), // official_notes 검색
                List.of("https://roastery.example/yirga", "https://coffee.example/wiki"),
                List.of(entry("새콤하고 좋았다", Rating.GOOD)),
                OffsetDateTime.now(),
                OffsetDateTime.now());
        PendingNote pending = new PendingNote(
                draft, MatchInfo.existing("coffeevera-yirgacheffe-g1", LocalDate.of(2026, 7, 10)),
                null, OffsetDateTime.now());

        List<LayoutBlock> blocks = previewBlocks.build(pending);

        // 헤더
        assertTrue(blocks.get(0) instanceof HeaderBlock);
        assertEquals(PreviewBlocks.HEADER, ((HeaderBlock) blocks.get(0)).getText().getText());

        // 매칭 표시(AC-15): 기존 노트 + 커피명 + 날짜
        String matchLine = md(((SectionBlock) blocks.get(1)).getText());
        assertTrue(matchLine.contains("기존 노트"), matchLine);
        assertTrue(matchLine.contains("커피베라 예가체프 G1"), matchLine);
        assertTrue(matchLine.contains("2026-07-10"), matchLine);

        // 출처 필드 표기 분기(AC-2)
        SectionBlock fieldsSection = firstFieldsSection(blocks);
        assertNotNull(fieldsSection);
        String roastery = fieldByLabel(fieldsSection, "로스터리");
        String bean = fieldByLabel(fieldsSection, "원두");
        assertFalse(roastery.contains("(검색)"), "사용자 값에는 (검색) 표기 없음: " + roastery);
        assertTrue(bean.contains("(검색)"), "검색 값에는 (검색) 표기: " + bean);
        // 미언급 필드(roast_level)는 생략
        assertTrue(fieldsSection.getFields().stream().map(PreviewBlocksTest::md)
                .noneMatch(f -> f.contains("로스팅")));

        // official_notes(검색)와 my_taste 2단 분리
        assertTrue(blocks.stream().anyMatch(b -> b instanceof SectionBlock s
                && s.getText() instanceof MarkdownTextObject t
                && t.getText().contains("로스터리가 말하길") && t.getText().contains("(검색)")));
        assertTrue(blocks.stream().anyMatch(b -> b instanceof SectionBlock s
                && s.getText() instanceof MarkdownTextObject t
                && t.getText().contains("내가 느끼길") && t.getText().contains("새콤하고 좋았다")));

        // 출처 링크(FR-12) — context 블록에 링크 2개
        ContextBlock context = (ContextBlock) blocks.stream().filter(b -> b instanceof ContextBlock)
                .findFirst().orElseThrow();
        String sources = ((MarkdownTextObject) context.getElements().get(0)).getText();
        assertTrue(sources.contains("<https://roastery.example/yirga|1>"), sources);
        assertTrue(sources.contains("<https://coffee.example/wiki|2>"), sources);

        // [저장]/[취소] 버튼 — action_id 계약
        ActionsBlock actions = (ActionsBlock) blocks.get(blocks.size() - 1);
        List<ButtonElement> buttons = actions.getElements().stream()
                .map(e -> (ButtonElement) e).toList();
        assertEquals(2, buttons.size());
        assertEquals(AgentConversationRouter.ACTION_SAVE, buttons.get(0).getActionId());
        assertEquals(AgentConversationRouter.ACTION_CANCEL, buttons.get(1).getActionId());
    }

    @Test
    @DisplayName("AC-15: 신규 매칭이면 '새 노트' 표기, 사용자 전용 필드엔 (검색) 없음, 검색 무결과면 출처 블록 생략")
    void newMatchWithoutSearch() {
        Note draft = new Note(
                "coffeevera-yirgacheffe-g1",
                Sourced.user("커피베라 예가체프 G1"),
                Sourced.user("커피베라"),
                List.of(), null,
                null,
                List.of(),                          // 출처 없음
                List.of(entry("새콤함", Rating.PERFECT)),
                OffsetDateTime.now(),
                OffsetDateTime.now());
        PendingNote pending = new PendingNote(draft, MatchInfo.newNote(), null, OffsetDateTime.now());

        List<LayoutBlock> blocks = previewBlocks.build(pending);

        String matchLine = md(((SectionBlock) blocks.get(1)).getText());
        assertTrue(matchLine.contains("새 노트"), matchLine);

        // 사용자 값에는 (검색) 없음, 평가는 라벨 표기
        SectionBlock fieldsSection = firstFieldsSection(blocks);
        assertFalse(fieldByLabel(fieldsSection, "로스터리").contains("(검색)"));
        assertTrue(fieldByLabel(fieldsSection, "평가").contains(Rating.PERFECT.label()));

        // 출처 없음 → context(출처) 블록 없음
        assertTrue(blocks.stream().noneMatch(b -> b instanceof ContextBlock));
    }

    @Test
    @DisplayName("AC-22/AC-Δ1: buildFinalized는 [저장]/[취소] 버튼을 없애고 필드 내용은 유지한 채 상태 문구 섹션을 붙인다")
    void buildFinalizedDropsButtonsKeepsFields() {
        Note draft = new Note(
                "coffeevera-yirgacheffe-g1",
                Sourced.user("커피베라 예가체프 G1"),
                Sourced.user("커피베라"),
                List.of(new Bean(Sourced.search("에티오피아"), Sourced.search("워시드"))),
                null,
                Sourced.search(List.of("자몽", "홍차")),
                List.of("https://roastery.example/yirga"),
                List.of(entry("새콤하고 좋았다", Rating.GOOD)),
                OffsetDateTime.now(),
                OffsetDateTime.now());
        PendingNote pending = new PendingNote(
                draft, MatchInfo.existing("coffeevera-yirgacheffe-g1", LocalDate.of(2026, 7, 10)),
                "1720000000.000999", OffsetDateTime.now());

        List<LayoutBlock> blocks = previewBlocks.buildFinalized(pending, MochaMessages.FINALIZE_SAVED);

        // 버튼 소진: ActionsBlock이 하나도 없어야 재클릭이 불가능하다(AC-22).
        assertTrue(blocks.stream().noneMatch(b -> b instanceof ActionsBlock), "버튼 블록이 남으면 안 된다");

        // 필드 내용 유지: 필드 섹션이 그대로 있고 (검색) 표기 분기도 보존된다(문맥 보존, D-5-1a).
        SectionBlock fieldsSection = firstFieldsSection(blocks);
        assertNotNull(fieldsSection);
        assertFalse(fieldByLabel(fieldsSection, "로스터리").contains("(검색)"));
        assertTrue(fieldByLabel(fieldsSection, "원두").contains("(검색)"));
        assertTrue(blocks.stream().anyMatch(b -> b instanceof SectionBlock s
                && s.getText() instanceof MarkdownTextObject t && t.getText().contains("내가 느끼길")),
                "내가 느끼길 내용이 유지된다");

        // 상태 문구가 마지막 블록으로 붙는다.
        LayoutBlock last = blocks.get(blocks.size() - 1);
        assertTrue(last instanceof SectionBlock s
                && s.getText() instanceof MarkdownTextObject t
                && t.getText().equals(MochaMessages.FINALIZE_SAVED),
                "마지막 블록은 상태 문구 섹션이어야 한다");
    }

    @Test
    @DisplayName("AC-26/FR-12: 커피명이 source=photo면 (사진) 표기, user면 표기 없음")
    void coffeeNameSourceTags() {
        // photo 유래 커피명 — (사진) 표기, photo로 채운 로스터리도 (사진)
        Note photoDraft = new Note(
                "coffeevera-yirgacheffe-g1",
                Sourced.photo("커피베라 예가체프 G1"),
                Sourced.photo("커피베라"),
                List.of(new Bean(Sourced.search("에티오피아"), null)),
                null, null,
                List.of(),
                List.of(entry("좋았다", Rating.GOOD)),
                OffsetDateTime.now(),
                OffsetDateTime.now());
        SectionBlock photoFields = firstFieldsSection(
                previewBlocks.build(new PendingNote(photoDraft, MatchInfo.newNote(), null, OffsetDateTime.now())));
        assertTrue(fieldByLabel(photoFields, "커피").contains("(사진)"),
                "사진 유래 커피명엔 (사진) 표기");
        assertFalse(fieldByLabel(photoFields, "커피").contains("(검색)"));
        assertTrue(fieldByLabel(photoFields, "로스터리").contains("(사진)"), "photo 값엔 (사진) 표기");
        assertTrue(fieldByLabel(photoFields, "원두").contains("(검색)"), "search 값엔 (검색) 표기");

        // user 유래 커피명 — 표기 없음
        Note userDraft = new Note(
                "coffeevera-yirgacheffe-g1",
                Sourced.user("커피베라 예가체프 G1"),
                Sourced.user("커피베라"),
                List.of(), null, null,
                List.of(),
                List.of(entry("좋았다", Rating.GOOD)),
                OffsetDateTime.now(),
                OffsetDateTime.now());
        SectionBlock userFields = firstFieldsSection(
                previewBlocks.build(new PendingNote(userDraft, MatchInfo.newNote(), null, OffsetDateTime.now())));
        String coffee = fieldByLabel(userFields, "커피");
        assertFalse(coffee.contains("(사진)"), "사용자 커피명엔 표기 없음: " + coffee);
        assertFalse(coffee.contains("(검색)"), "사용자 커피명엔 표기 없음: " + coffee);
    }

    @Test
    @DisplayName("AC-24: 레시피가 있으면 '이렇게 내렸어요' 영역에 있는 항목이 표시된다")
    void recipeShownWhenPresent() {
        Note draft = new Note(
                "coffeevera-yirgacheffe-g1",
                Sourced.user("커피베라 예가체프 G1"),
                Sourced.user("커피베라"),
                List.of(), null, null,
                List.of(),
                List.of(entry("좋았다", Rating.GOOD, Recipe.normalize(new Recipe(15.0, 240.0, "중간")))),
                OffsetDateTime.now(),
                OffsetDateTime.now());

        List<LayoutBlock> blocks = previewBlocks.build(new PendingNote(draft, MatchInfo.newNote(), null, OffsetDateTime.now()));

        String recipeBlock = blocks.stream()
                .filter(b -> b instanceof SectionBlock s && s.getText() instanceof MarkdownTextObject t
                        && t.getText().contains(PreviewBlocks.RECIPE_LABEL))
                .map(b -> md(((SectionBlock) b).getText()))
                .findFirst().orElseThrow(() -> new AssertionError("레시피 영역 없음"));
        assertTrue(recipeBlock.contains("원두 15g"), recipeBlock);
        assertTrue(recipeBlock.contains("물 240ml"), recipeBlock);
        assertTrue(recipeBlock.contains("분쇄도 중간"), recipeBlock);
    }

    @Test
    @DisplayName("AC-25: 레시피 일부만 있으면 있는 항목만, 전무면 레시피 영역 미출력")
    void recipePartialAndAbsent() {
        // 물량만 있는 부분 레시피 — 원두·분쇄도 항목은 표시하지 않음
        Note partial = new Note(
                "coffeevera-yirgacheffe-g1",
                Sourced.user("커피베라 예가체프 G1"),
                Sourced.user("커피베라"),
                List.of(), null, null, List.of(),
                List.of(entry("좋았다", Rating.GOOD, Recipe.normalize(new Recipe(null, 240.0, null)))),
                OffsetDateTime.now(), OffsetDateTime.now());
        String recipeBlock = recipeSectionText(
                previewBlocks.build(new PendingNote(partial, MatchInfo.newNote(), null, OffsetDateTime.now())));
        assertNotNull(recipeBlock, "부분 레시피는 영역 출력");
        assertTrue(recipeBlock.contains("물 240ml"), recipeBlock);
        assertFalse(recipeBlock.contains("원두"), recipeBlock);
        assertFalse(recipeBlock.contains("분쇄도"), recipeBlock);

        // 레시피 전무(null) — 영역 자체 미출력
        Note none = new Note(
                "coffeevera-yirgacheffe-g1",
                Sourced.user("커피베라 예가체프 G1"),
                Sourced.user("커피베라"),
                List.of(), null, null, List.of(),
                List.of(entry("좋았다", Rating.GOOD, null)),
                OffsetDateTime.now(), OffsetDateTime.now());
        assertNull(recipeSectionText(
                previewBlocks.build(new PendingNote(none, MatchInfo.newNote(), null, OffsetDateTime.now()))),
                "레시피 전무면 영역 미출력");
    }

    @Test
    @DisplayName("AC-15/AC-37: edit 모드는 ✏️ 헤더 + '기존 노트 수정' 표기, 충돌 없으면 경고 미출력, [저장]/[취소] 버튼 유지")
    void editModeHeaderWithoutConflict() {
        Note draft = editDraft(LocalDate.of(2026, 7, 10));
        PendingNote pending = new PendingNote(
                PendingNote.Mode.EDIT, draft,
                new PendingNote.EditTarget(draft.slug(), LocalDate.of(2026, 7, 10)),
                null, null, OffsetDateTime.now());

        List<LayoutBlock> blocks = previewBlocks.build(pending);

        assertEquals(PreviewBlocks.HEADER_EDIT, ((HeaderBlock) blocks.get(0)).getText().getText());
        String matchLine = md(((SectionBlock) blocks.get(1)).getText());
        assertTrue(matchLine.contains("기존 노트 수정"), matchLine);
        assertTrue(matchLine.contains("2026-07-10"), matchLine);

        // 충돌 플래그가 없으면 경고 섹션 미출력
        assertTrue(blocks.stream().noneMatch(b -> b instanceof SectionBlock s
                && s.getText() instanceof MarkdownTextObject t && t.getText().contains("덮어써")),
                "충돌 없으면 경고 없음");

        // 수정 세션도 [저장]/[취소] 버튼 계약 동일(pending 재사용, ADR-27)
        ActionsBlock actions = (ActionsBlock) blocks.get(blocks.size() - 1);
        assertEquals(2, actions.getElements().size());
    }

    @Test
    @DisplayName("AC-39/V-10: edit 모드 날짜 이동 충돌 시 덮어쓰기 경고 필수 표기 + 이동 표기(옛 날짜 → 새 날짜)")
    void editModeDateConflictWarning() {
        // 7/10 기록을 7/12로 이동, 7/12에 기존 엔트리가 있어 충돌(dateConflict=true)
        Note draft = editDraft(LocalDate.of(2026, 7, 12));
        PendingNote pending = new PendingNote(
                PendingNote.Mode.EDIT, draft,
                new PendingNote.EditTarget(draft.slug(), LocalDate.of(2026, 7, 10)),
                true, null, null, OffsetDateTime.now());

        List<LayoutBlock> blocks = previewBlocks.build(pending);

        // 이동 표기: 대상 날짜와 새 날짜 모두 명시
        String matchLine = md(((SectionBlock) blocks.get(1)).getText());
        assertTrue(matchLine.contains("기존 노트 수정"), matchLine);
        assertTrue(matchLine.contains("2026-07-10"), matchLine);
        assertTrue(matchLine.contains("2026-07-12"), matchLine);

        // 경고 섹션 필수(경고 없는 덮어쓰기 금지) — 충돌 날짜 명시
        String warning = blocks.stream()
                .filter(b -> b instanceof SectionBlock s && s.getText() instanceof MarkdownTextObject t
                        && t.getText().contains("덮어써"))
                .map(b -> md(((SectionBlock) b).getText()))
                .findFirst().orElseThrow(() -> new AssertionError("덮어쓰기 경고 섹션 없음(V-10 위반)"));
        assertTrue(warning.contains("2026-07-12"), warning);

        // buildFinalized(버튼 소진, 0009 재사용)에서도 본문 공유로 경고·✏️ 헤더 유지
        List<LayoutBlock> finalized = previewBlocks.buildFinalized(pending, MochaMessages.FINALIZE_SAVED);
        assertEquals(PreviewBlocks.HEADER_EDIT, ((HeaderBlock) finalized.get(0)).getText().getText());
        assertTrue(finalized.stream().noneMatch(b -> b instanceof ActionsBlock));
        assertTrue(finalized.stream().anyMatch(b -> b instanceof SectionBlock s
                && s.getText() instanceof MarkdownTextObject t && t.getText().contains("덮어써")));
    }

    @Test
    @DisplayName("AC-Δ6 회귀: record 모드 미리보기는 ☕ 헤더 그대로, '기존 노트 수정' 표기·경고 없음")
    void recordModeUnchanged() {
        Note draft = editDraft(LocalDate.of(2026, 7, 10));
        PendingNote pending = new PendingNote(draft, MatchInfo.newNote(), null, OffsetDateTime.now());

        List<LayoutBlock> blocks = previewBlocks.build(pending);

        assertEquals(PreviewBlocks.HEADER, ((HeaderBlock) blocks.get(0)).getText().getText());
        assertTrue(blocks.stream()
                .filter(b -> b instanceof SectionBlock s && s.getText() instanceof MarkdownTextObject)
                .map(b -> md(((SectionBlock) b).getText()))
                .noneMatch(t -> t.contains("기존 노트 수정") || t.contains("덮어써")));
    }

    // edit 모드 draft — 대상 노트를 로드한 사본 형태(엔트리 1건, 날짜는 인자).
    private static Note editDraft(LocalDate entryDate) {
        return new Note(
                "coffeevera-yirgacheffe-g1",
                Sourced.user("커피베라 예가체프 G1"),
                Sourced.user("커피베라"),
                List.of(), null, null,
                List.of(),
                List.of(new Entry(entryDate,
                        List.of(new Brew(null, new Tasting("산미가 좋았다", null, Rating.GOOD))), OffsetDateTime.now())),
                OffsetDateTime.now(),
                OffsetDateTime.now());
    }

    // "이렇게 내렸어요" 섹션 텍스트 또는 null(영역 없음).
    private static String recipeSectionText(List<LayoutBlock> blocks) {
        return blocks.stream()
                .filter(b -> b instanceof SectionBlock s && s.getText() instanceof MarkdownTextObject t
                        && t.getText().contains(PreviewBlocks.RECIPE_LABEL))
                .map(b -> md(((SectionBlock) b).getText()))
                .findFirst().orElse(null);
    }

    private static SectionBlock firstFieldsSection(List<LayoutBlock> blocks) {
        return (SectionBlock) blocks.stream()
                .filter(b -> b instanceof SectionBlock s && s.getFields() != null && !s.getFields().isEmpty())
                .findFirst().orElseThrow();
    }

    private static String fieldByLabel(SectionBlock section, String label) {
        return section.getFields().stream().map(PreviewBlocksTest::md)
                .filter(f -> f.contains("*" + label + "*"))
                .findFirst().orElseThrow(() -> new AssertionError("필드 없음: " + label));
    }
}
