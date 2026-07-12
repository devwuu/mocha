package com.devwuu.mocha.slack;

import com.devwuu.mocha.domain.Entry;
import com.devwuu.mocha.domain.MatchInfo;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.PendingNote;
import com.devwuu.mocha.domain.Recipe;
import com.devwuu.mocha.domain.Source;
import com.devwuu.mocha.domain.Sourced;
import com.slack.api.model.block.LayoutBlock;
import com.slack.api.model.block.composition.TextObject;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static com.slack.api.model.block.Blocks.actions;
import static com.slack.api.model.block.Blocks.context;
import static com.slack.api.model.block.Blocks.divider;
import static com.slack.api.model.block.Blocks.header;
import static com.slack.api.model.block.Blocks.section;
import static com.slack.api.model.block.composition.BlockCompositions.markdownText;
import static com.slack.api.model.block.composition.BlockCompositions.plainText;
import static com.slack.api.model.block.element.BlockElements.asElements;
import static com.slack.api.model.block.element.BlockElements.button;

/**
 * 확인 미리보기 Block Kit 조립 (ref: tasks T3-3; spec FR-4/FR-12, AC-2/AC-15).
 * <p>{@link PendingNote}(draft + 매칭 판정)를 저장 전 확인 메시지 블록으로 변환하는 순수 함수다 — Slack 네트워크
 * 의존이 없어 단위로 스냅샷 검증한다(CLAUDE.md §5.3). 전송/갱신은 {@link PreviewMessenger}가 맡는다.
 * <ul>
 *   <li>검색으로 채운 출처 필드에는 {@code (검색)} 표기, 사용자 값에는 표기 없음 (AC-2, FR-12).
 *   <li>매칭 표시: "새 노트" 또는 "기존 노트 {이름}의 {날짜} 기록" (AC-15).
 *   <li>수정 세션(mode=edit)은 ✏️ 헤더 + "기존 노트 수정" 표기, 날짜 이동 충돌 시 덮어쓰기 경고 섹션 필수
 *       (AC-15/AC-37/AC-39, V-10; changes/0012 TΔ6).
 *   <li>[저장]/[취소] 버튼 — action_id는 {@link DefaultConversationRouter}의 계약을 따른다.
 * </ul>
 * <p>멘트(모카 톤)는 구현 디테일이라 상수로 분리한다 — spec의 비즈니스 결정이 아니다.
 */
@Component
public class PreviewBlocks {

    // --- 멘트(모카 톤) 상수 — 구현 디테일, spec 결정 아님. 강아지 말투: 문장 끝에 "멍" + 🐾 ---
    static final String HEADER = "☕ 이렇게 기록할까요 멍? 🐾"; // ☕ 이렇게 기록할까요 멍? 🐾
    static final String HEADER_EDIT = "✏️ 이렇게 고칠까요 멍? 🐾"; // ✏️ 수정 세션 헤더 (AC-15, changes/0012)
    static final String MATCH_NEW = "*새 노트*를 만들게요 멍! 🐾"; // *새 노트*를 만들게요 멍! 🐾
    static final String MATCH_EXISTING_FMT = "기존 노트 *%s*의 *%s* 기록이에요 멍! 🐾"; // 기존 노트 *{이름}*의 *{날짜}* 기록이에요 멍! 🐾
    static final String MATCH_EDIT_FMT = "*기존 노트 수정* — *%s*의 *%s* 기록을 고쳐요 멍! 🐾"; // 기존 노트 수정 — *{이름}*의 *{대상 날짜}* (AC-15)
    static final String MATCH_EDIT_MOVED_FMT = "*기존 노트 수정* — *%s*의 *%s* 기록을 *%s*로 옮겨서 고쳐요 멍! 🐾"; // 날짜 이동 시 {옛 날짜} → {새 날짜} 표기
    static final String DATE_CONFLICT_WARNING_FMT =
            "⚠️ *%s*에는 이미 기록이 있어요 멍 — [저장]하면 그 날 기록을 이번 내용으로 덮어써요! 🐾"; // 덮어쓰기 경고 (AC-39, V-10)
    static final String OFFICIAL_NOTES_LABEL = "로스터리가 말하길"; // 로스터리가 말하길
    static final String RECIPE_LABEL = "이렇게 내렸어요"; // 이렇게 내렸어요 (FR-18, changes/0010)
    static final String MY_TASTE_LABEL = "내가 느끼길"; // 내가 느끼길
    static final String SOURCES_LABEL = "출처"; // 출처
    static final String SEARCH_TAG = " _(검색)_"; // (검색) — 이탤릭 표기
    static final String PHOTO_TAG = " _(사진)_"; // (사진) — 수신 사진 OCR 유래 표기 (FR-12, changes/0010)
    static final String SAVE_LABEL = "저장"; // 저장
    static final String CANCEL_LABEL = "취소"; // 취소

    /** 알림/폴백 텍스트(블록 미지원 클라이언트·푸시용). */
    static final String FALLBACK_TEXT = "모카 미리보기 — 저장할까요 멍? 🐾"; // 모카 미리보기 — 저장할까요 멍? 🐾

    // 필드 라벨(출처 표시 필드)
    private static final String LABEL_COFFEE = "커피"; // 커피
    private static final String LABEL_ROASTERY = "로스터리"; // 로스터리
    private static final String LABEL_ORIGIN = "원산지"; // 원산지
    private static final String LABEL_PROCESS = "가공"; // 가공
    private static final String LABEL_ROAST = "로스팅"; // 로스팅
    private static final String LABEL_RATING = "평가"; // 평가

    // 레시피 항목 표기(있는 항목만·전무 시 영역 미출력, ADR-22) — 원두 15g · 물 240ml · 분쇄도 중간
    private static final String RECIPE_DOSE_FMT = "원두 %sg"; // 원두 {dose}g
    private static final String RECIPE_WATER_FMT = "물 %sml"; // 물 {water}ml
    private static final String RECIPE_GRIND_FMT = "분쇄도 %s"; // 분쇄도 {grind}
    private static final String RECIPE_SEP = " · "; // 항목 구분

    /**
     * pending을 확인 미리보기 블록 목록으로 조립한다.
     *
     * @param pending 확인 대기 노트(draft + 매칭). draft.entries는 이번 시음 엔트리 1건을 담은 상태를 전제.
     */
    public List<LayoutBlock> build(PendingNote pending) {
        List<LayoutBlock> blocks = contentBlocks(pending);
        blocks.add(divider());
        Note draft = pending.draft();
        blocks.add(actions(asElements(
                button(b -> b.text(plainText(SAVE_LABEL)).style("primary")
                        .actionId(DefaultConversationRouter.ACTION_SAVE).value(saveValue(draft))),
                button(b -> b.text(plainText(CANCEL_LABEL)).style("danger")
                        .actionId(DefaultConversationRouter.ACTION_CANCEL).value(saveValue(draft)))
        )));
        return blocks;
    }

    /**
     * 버튼 소진용 블록 — 미리보기 필드 내용은 그대로 유지하고 [저장]/[취소] 버튼 대신 상태 문구 섹션을 붙인다
     * (ref: plan.md#ADR-20, spec AC-22; changes/0009 AC-Δ1). {@link #build}와 본문 블록을 공유한다.
     *
     * @param statusText 하단에 표기할 상태 문구(예: "✅ 저장 완료" / "취소됨"). 모카 톤 상수는 호출부가 정한다.
     */
    public List<LayoutBlock> buildFinalized(PendingNote pending, String statusText) {
        List<LayoutBlock> blocks = contentBlocks(pending);
        blocks.add(divider());
        blocks.add(section(s -> s.text(markdownText(statusText))));
        return blocks;
    }

    // 미리보기 본문(헤더·매칭·필드·노트·출처) — 버튼/상태 문구 앞까지 공통. build/buildFinalized가 공유한다.
    private List<LayoutBlock> contentBlocks(PendingNote pending) {
        Note draft = pending.draft();
        Entry entry = latestEntry(draft);

        boolean editMode = pending.mode() == PendingNote.Mode.EDIT;
        List<LayoutBlock> blocks = new ArrayList<>();
        blocks.add(header(h -> h.text(plainText(editMode ? HEADER_EDIT : HEADER))));
        blocks.add(section(s -> s.text(markdownText(editMode
                ? editMatchLine(pending, entry)
                : matchLine(pending.match(), value(draft.coffeeName()))))));
        // POLICY: 날짜 이동 덮어쓰기는 미리보기 경고 표기 없이는 금지 — 충돌 플래그가 서 있으면 경고 섹션 필수
        //         (ref: specs/coffee-note-agent/plan.md#ADR-27, data-model.md#V-10, spec AC-39).
        if (editMode && pending.dateConflict() && entry != null) {
            blocks.add(section(s -> s.text(markdownText(
                    String.format(DATE_CONFLICT_WARNING_FMT, entry.date())))));
        }
        blocks.add(divider());

        // 출처 표시 필드 — 2열 fields. null 값은 생략, source 3값 표기(user 무표기·photo (사진)·search (검색)) (AC-2, AC-26, FR-12).
        List<TextObject> fields = new ArrayList<>();
        addSourcedField(fields, LABEL_COFFEE, draft.coffeeName()); // 커피명도 source=photo면 (사진) 표기(AC-26)
        addSourcedField(fields, LABEL_ROASTERY, draft.roastery());
        addSourcedField(fields, LABEL_ORIGIN, draft.origin());
        addSourcedField(fields, LABEL_PROCESS, draft.process());
        addSourcedField(fields, LABEL_ROAST, draft.roastLevel());
        if (entry != null && entry.rating() != null) {
            addField(fields, LABEL_RATING, entry.rating().label(), null);
        }
        if (!fields.isEmpty()) {
            blocks.add(section(s -> s.fields(fields)));
        }

        // 로스터리가 말하길 / 내가 느끼길 2단 분리(FR-7 재료).
        String officialNotes = officialNotesText(draft.officialNotes());
        if (officialNotes != null) {
            blocks.add(section(s -> s.text(markdownText("*" + OFFICIAL_NOTES_LABEL + "*\n" + officialNotes))));
        }
        // 이렇게 내렸어요(레시피) — 있는 항목만·전무 시 미출력(FR-18, AC-24/25). 수정 가능하도록 미리보기 포함(FR-12).
        String recipe = entry == null ? null : recipeText(entry.recipe());
        if (recipe != null) {
            blocks.add(section(s -> s.text(markdownText("*" + RECIPE_LABEL + "*\n" + recipe))));
        }
        if (entry != null && entry.myTaste() != null && !entry.myTaste().isBlank()) {
            blocks.add(section(s -> s.text(markdownText("*" + MY_TASTE_LABEL + "*\n" + entry.myTaste()))));
        }

        // 출처 링크(FR-12) — context 블록에 <url|n> 형식 링크.
        String sources = sourcesText(draft.sources());
        if (sources != null) {
            blocks.add(context(c -> c.elements(List.of(markdownText(SOURCES_LABEL + ": " + sources)))));
        }

        return blocks;
    }

    private static String matchLine(MatchInfo match, String coffeeName) {
        if (match == null || match.type() == MatchInfo.MatchType.NEW) {
            return MATCH_NEW;
        }
        // 갱신/추가 구분은 pending(MatchInfo)에 없다 — "기록"으로 중립 표기(tasks T3-3 라인).
        return String.format(MATCH_EXISTING_FMT, coffeeName, match.date());
    }

    // edit 모드 매칭 라인 — "기존 노트 수정" 명시(AC-15). 미리보기가 draft(저장될 내용)를 반영해야 하므로(FR-4)
    // 날짜 이동이 있으면 대상 날짜 → 새 날짜를 함께 표기한다.
    private static String editMatchLine(PendingNote pending, Entry entry) {
        String coffeeName = value(pending.draft().coffeeName());
        LocalDate targetDate = pending.target().date();
        LocalDate entryDate = entry == null ? null : entry.date();
        if (entryDate != null && !entryDate.equals(targetDate)) {
            return String.format(MATCH_EDIT_MOVED_FMT, coffeeName, targetDate, entryDate);
        }
        return String.format(MATCH_EDIT_FMT, coffeeName, targetDate);
    }

    private static String value(Sourced<String> sourced) {
        return sourced == null ? null : sourced.value();
    }

    private static void addSourcedField(List<TextObject> fields, String label, Sourced<String> sourced) {
        if (sourced == null || sourced.value() == null || sourced.value().isBlank()) {
            return;
        }
        addField(fields, label, sourced.value(), sourced.source());
    }

    // 출처 3값 표기: photo→(사진)·search→(검색)·user/무출처→표기 없음 (AC-2, AC-26, FR-12).
    private static void addField(List<TextObject> fields, String label, String value, Source source) {
        fields.add(markdownText("*" + label + "*\n" + value + sourceTag(source)));
    }

    // 출처별 표기 태그. 우선순위 user > photo > search와 무관한 순수 표기 결정.
    private static String sourceTag(Source source) {
        if (source == Source.PHOTO) {
            return PHOTO_TAG;
        }
        if (source == Source.SEARCH) {
            return SEARCH_TAG;
        }
        return "";
    }

    private static String officialNotesText(Sourced<List<String>> officialNotes) {
        if (officialNotes == null || officialNotes.value() == null || officialNotes.value().isEmpty()) {
            return null;
        }
        String joined = String.join(", ", officialNotes.value());
        return joined + sourceTag(officialNotes.source());
    }

    // 레시피 표기 — 있는 항목만 " · "로 이어 붙이고, 전무(null)면 null 반환(영역 미출력, ADR-22).
    private static String recipeText(Recipe recipe) {
        if (recipe == null) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        if (recipe.doseG() != null) {
            parts.add(String.format(RECIPE_DOSE_FMT, number(recipe.doseG())));
        }
        if (recipe.waterMl() != null) {
            parts.add(String.format(RECIPE_WATER_FMT, number(recipe.waterMl())));
        }
        if (recipe.grind() != null && !recipe.grind().isBlank()) {
            parts.add(String.format(RECIPE_GRIND_FMT, recipe.grind()));
        }
        return parts.isEmpty() ? null : String.join(RECIPE_SEP, parts);
    }

    // 정수면 소수점 없이(15.0 → "15"), 아니면 그대로(15.5 → "15.5") 표기.
    private static String number(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    private static String sourcesText(List<String> sources) {
        if (sources == null || sources.isEmpty()) {
            return null;
        }
        List<String> links = new ArrayList<>();
        for (int i = 0; i < sources.size(); i++) {
            links.add("<" + sources.get(i) + "|" + (i + 1) + ">");
        }
        return String.join(" ", links);
    }

    // 신규 노트는 slug가 아직 없을 수 있다 — 커밋은 userId로 pending을 조회하므로 value는 참고용.
    private static String saveValue(Note draft) {
        return draft.slug() != null ? draft.slug() : "";
    }

    private static Entry latestEntry(Note draft) {
        List<Entry> entries = draft.entries();
        if (entries == null || entries.isEmpty()) {
            return null;
        }
        return entries.get(entries.size() - 1);
    }
}
