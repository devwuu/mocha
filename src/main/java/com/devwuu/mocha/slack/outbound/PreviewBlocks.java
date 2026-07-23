package com.devwuu.mocha.slack.outbound;

import com.devwuu.mocha.domain.Bean;
import com.devwuu.mocha.domain.Brew;
import com.devwuu.mocha.domain.Entry;
import com.devwuu.mocha.domain.MatchInfo;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.PendingNote;
import com.devwuu.mocha.domain.Recipe;
import com.devwuu.mocha.domain.Source;
import com.devwuu.mocha.domain.Sourced;
import com.devwuu.mocha.domain.Tasting;
import com.devwuu.mocha.render.RecipeAmounts;
import com.devwuu.mocha.slack.AgentConversationRouter;
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
 *   <li>회차가 여럿이면 회차별 레시피·감상 요약을 구분 표시한다 — 회차 분리가 눈에 보여야 확인 역할을 한다
 *       (FR-4, changes/0021 ADR-59). 회차 1개면 종전 단일 표시를 유지한다.
 *   <li>[저장]/[취소] 버튼 — action_id는 {@link AgentConversationRouter}의 계약을 따른다.
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
    private static final String LABEL_BEAN = "원두"; // 원두 (beans 요소당 1필드, changes/0021)
    private static final String LABEL_ROAST = "로스팅"; // 로스팅
    private static final String LABEL_RATING = "평가"; // 평가

    // 레시피 항목 표기(있는 항목만·전무 시 영역 미출력, ADR-22) — 10필드 flat 스키마(FR-18, changes/0021)
    // 수치·단문은 " · " 한 줄, 과정 서술(푸어링)·피드백은 줄 분리. 시간은 카드와 동일하게 60초 기준 포맷(AC-76).
    private static final String RECIPE_DOSE_FMT = "원두 %sg"; // 원두 {dose}g
    private static final String RECIPE_WATER_FMT = "물 %sml"; // 물 {water}ml
    private static final String RECIPE_YIELD_FMT = "추출량 %sml"; // 추출량 {yield}ml
    private static final String RECIPE_TIME_FMT = "시간 %s"; // 시간 2분 40초
    private static final String RECIPE_TEMP_FMT = "온도 %s℃"; // 온도 {temp}℃
    private static final String RECIPE_GRIND_FMT = "분쇄도 %s"; // 분쇄도 {grind}
    private static final String RECIPE_MACHINE_FMT = "기구 %s"; // 기구 {machine}
    private static final String RECIPE_POURING_FMT = "푸어링: %s"; // 푸어링 과정 서술(자유 텍스트)
    private static final String RECIPE_FEEDBACK_FMT = "피드백: %s"; // 그 시도의 관찰·진단·계획
    private static final String RECIPE_SEP = " · "; // 항목 구분

    // 회차별 요약(FR-4, changes/0021 TΔ3c) — 회차 2개 이상일 때 회차마다 섹션 1개로 구분 표시.
    static final String BREW_HEADER_FMT = "*%d회차*"; // 회차 구분 헤더
    private static final String BREW_RATING_FMT = "평가: %s"; // 회차 감상에 붙는 rating 표기

    // POLICY: 시간·수치 표기는 RecipeAmounts(render)가 단일 소스 — 미리보기는 위임만 하고 사본을 두지 않는다.
    //         카드↔미리보기 동일 문자열(AC-76)을 코드로 강제한다 (ref: specs/coffee-note-agent/plan.md#ADR-67).
    private static final RecipeAmounts AMOUNTS = new RecipeAmounts();

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
                        .actionId(AgentConversationRouter.ACTION_SAVE).value(saveValue(draft))),
                button(b -> b.text(plainText(CANCEL_LABEL)).style("danger")
                        .actionId(AgentConversationRouter.ACTION_CANCEL).value(saveValue(draft)))
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
                : matchLine(pending.match(), Sourced.valueOrNull(draft.coffeeName()))))));
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
        addBeanFields(fields, draft.beans()); // 원두별 1필드 — 서브필드(설명·가공) 출처 표기 포함(V-14, changes/0021)
        addSourcedField(fields, LABEL_ROAST, draft.roastLevel());
        List<Brew> brews = entry == null || entry.brews() == null ? List.of() : entry.brews();
        boolean multiBrew = brews.size() >= 2;
        // 회차 1개면 rating을 필드 영역에 종전대로 표기 — 회차별 요약(복수)에서는 각 회차 섹션에 담는다.
        Tasting singleTasting = brews.size() == 1 ? brews.get(0).tasting() : null;
        if (singleTasting != null && singleTasting.rating() != null) {
            addField(fields, LABEL_RATING, singleTasting.rating().label(), null);
        }
        if (!fields.isEmpty()) {
            blocks.add(section(s -> s.fields(fields)));
        }

        // 로스터리가 말하길 / 내가 느끼길 2단 분리(FR-7 재료).
        String officialNotes = officialNotesText(draft.officialNotes());
        if (officialNotes != null) {
            blocks.add(section(s -> s.text(markdownText("*" + OFFICIAL_NOTES_LABEL + "*\n" + officialNotes))));
        }
        // POLICY: 회차가 여럿이면 회차별 레시피·감상 요약을 구분 표시한다 — 회차 분리가 눈에 보여야 확인
        //         역할을 한다 (ref: specs/coffee-note-agent/spec.md#FR-4, changes/0021 ADR-59).
        if (multiBrew) {
            for (int i = 0; i < brews.size(); i++) {
                String brewSummary = brewText(i + 1, brews.get(i));
                blocks.add(section(s -> s.text(markdownText(brewSummary))));
            }
        } else {
            // 회차 1개(또는 0개) — 종전 단일 표시. 레시피는 있는 항목만·전무 시 미출력(FR-18, AC-24/25),
            // 수정 가능하도록 미리보기 포함(FR-12).
            String recipe = brews.isEmpty() ? null : recipeText(brews.get(0).recipe());
            if (recipe != null) {
                blocks.add(section(s -> s.text(markdownText("*" + RECIPE_LABEL + "*\n" + recipe))));
            }
            if (singleTasting != null && singleTasting.myTaste() != null && !singleTasting.myTaste().isBlank()) {
                blocks.add(section(s -> s.text(markdownText("*" + MY_TASTE_LABEL + "*\n" + singleTasting.myTaste()))));
            }
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
        String coffeeName = Sourced.valueOrNull(pending.draft().coffeeName());
        LocalDate targetDate = pending.target().date();
        LocalDate entryDate = entry == null ? null : entry.date();
        if (entryDate != null && !entryDate.equals(targetDate)) {
            return String.format(MATCH_EDIT_MOVED_FMT, coffeeName, targetDate, entryDate);
        }
        return String.format(MATCH_EDIT_FMT, coffeeName, targetDate);
    }

    // 회차 1개의 요약 — 헤더("{n}회차") + 레시피 + 감상(+평가)을 섹션 1개 텍스트로 구성한다(FR-4).
    // 레시피만/감상만인 회차도 있는 쪽만 담는다(V-15 — 둘 다 없는 회차는 저장 전에 드롭됨).
    private static String brewText(int number, Brew brew) {
        StringBuilder text = new StringBuilder(String.format(BREW_HEADER_FMT, number));
        String recipe = recipeText(brew.recipe());
        if (recipe != null) {
            text.append("\n*").append(RECIPE_LABEL).append("*\n").append(recipe);
        }
        Tasting tasting = brew.tasting();
        if (tasting != null && tasting.myTaste() != null && !tasting.myTaste().isBlank()) {
            text.append("\n*").append(MY_TASTE_LABEL).append("*\n").append(tasting.myTaste());
            if (tasting.rating() != null) {
                text.append("\n").append(String.format(BREW_RATING_FMT, tasting.rating().label()));
            }
        }
        return text.toString();
    }

    // beans는 원두당 1필드 — "설명 (출처) · 가공 (출처)" 표기로 서브필드 단위 출처(V-6)를 살린다(changes/0021).
    private static void addBeanFields(List<TextObject> fields, List<Bean> beans) {
        if (beans == null) {
            return;
        }
        for (Bean bean : beans) {
            if (bean.description() == null || bean.description().value() == null
                    || bean.description().value().isBlank()) {
                continue;
            }
            String text = bean.description().value() + sourceTag(bean.description().source());
            if (bean.process() != null && bean.process().value() != null && !bean.process().value().isBlank()) {
                text += RECIPE_SEP + bean.process().value() + sourceTag(bean.process().source());
            }
            fields.add(markdownText("*" + LABEL_BEAN + "*\n" + text));
        }
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

    // 레시피 표기 — 10필드 중 있는 항목만(FR-4 "채워진 내용 전체"·FR-18), 전무(null)면 null 반환(영역 미출력, ADR-22).
    // 수치·단문 항목은 " · " 한 줄, 과정 서술(푸어링)·피드백은 각각 줄을 나눈다.
    private static String recipeText(Recipe recipe) {
        if (recipe == null) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        if (recipe.method() != null) {
            parts.add(recipe.method());
        }
        // 수치는 num의 표기 불가(null·비유한값 → "")를 행 생략으로 매핑한다 — 템플릿의 th:if 숨김과 같은 효과.
        addNumberPart(parts, RECIPE_DOSE_FMT, recipe.doseG());
        addNumberPart(parts, RECIPE_WATER_FMT, recipe.waterMl());
        addNumberPart(parts, RECIPE_YIELD_FMT, recipe.yieldMl());
        // 시간은 카드와 동일 기준 — 비양수는 null 반환이라 행 자체를 생략한다(ADR-54 정렬, ADR-67).
        String time = AMOUNTS.time(recipe.timeSec());
        if (time != null) {
            parts.add(String.format(RECIPE_TIME_FMT, time));
        }
        addNumberPart(parts, RECIPE_TEMP_FMT, recipe.tempC());
        if (recipe.grind() != null) {
            parts.add(String.format(RECIPE_GRIND_FMT, recipe.grind()));
        }
        if (recipe.machine() != null) {
            parts.add(String.format(RECIPE_MACHINE_FMT, recipe.machine()));
        }
        List<String> lines = new ArrayList<>();
        if (!parts.isEmpty()) {
            lines.add(String.join(RECIPE_SEP, parts));
        }
        if (recipe.pouring() != null) {
            lines.add(String.format(RECIPE_POURING_FMT, recipe.pouring()));
        }
        if (recipe.feedback() != null) {
            lines.add(String.format(RECIPE_FEEDBACK_FMT, recipe.feedback()));
        }
        return lines.isEmpty() ? null : String.join("\n", lines);
    }

    // 수치 항목 1개 추가 — RecipeAmounts.num이 표기 불가로 빈 문자열을 돌려주면 항목을 만들지 않는다.
    private static void addNumberPart(List<String> parts, String format, Double value) {
        String num = AMOUNTS.num(value);
        if (!num.isEmpty()) {
            parts.add(String.format(format, num));
        }
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
