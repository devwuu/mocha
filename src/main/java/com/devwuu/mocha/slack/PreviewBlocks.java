package com.devwuu.mocha.slack;

import com.devwuu.mocha.domain.Entry;
import com.devwuu.mocha.domain.MatchInfo;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.PendingNote;
import com.devwuu.mocha.domain.Source;
import com.devwuu.mocha.domain.Sourced;
import com.slack.api.model.block.LayoutBlock;
import com.slack.api.model.block.composition.TextObject;
import org.springframework.stereotype.Component;

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
 *   <li>[저장]/[취소] 버튼 — action_id는 {@link DefaultConversationRouter}의 계약을 따른다.
 * </ul>
 * <p>멘트(모카 톤)는 구현 디테일이라 상수로 분리한다 — spec의 비즈니스 결정이 아니다.
 */
@Component
public class PreviewBlocks {

    // --- 멘트(모카 톤) 상수 — 구현 디테일, spec 결정 아님. 강아지 말투: 문장 끝에 "멍" + 🐾 ---
    static final String HEADER = "☕ 이렇게 기록할까요 멍? 🐾"; // ☕ 이렇게 기록할까요 멍? 🐾
    static final String MATCH_NEW = "*새 노트*를 만들게요 멍! 🐾"; // *새 노트*를 만들게요 멍! 🐾
    static final String MATCH_EXISTING_FMT = "기존 노트 *%s*의 *%s* 기록이에요 멍! 🐾"; // 기존 노트 *{이름}*의 *{날짜}* 기록이에요 멍! 🐾
    static final String OFFICIAL_NOTES_LABEL = "로스터리가 말하길"; // 로스터리가 말하길
    static final String MY_TASTE_LABEL = "내가 느끼길"; // 내가 느끼길
    static final String SOURCES_LABEL = "출처"; // 출처
    static final String SEARCH_TAG = " _(검색)_"; // (검색) — 이탤릭 표기
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

    /**
     * pending을 확인 미리보기 블록 목록으로 조립한다.
     *
     * @param pending 확인 대기 노트(draft + 매칭). draft.entries는 이번 시음 엔트리 1건을 담은 상태를 전제.
     */
    public List<LayoutBlock> build(PendingNote pending) {
        Note draft = pending.draft();
        Entry entry = latestEntry(draft);

        List<LayoutBlock> blocks = new ArrayList<>();
        blocks.add(header(h -> h.text(plainText(HEADER))));
        blocks.add(section(s -> s.text(markdownText(matchLine(pending.match(), draft.coffeeName())))));
        blocks.add(divider());

        // 출처 표시 필드 — 2열 fields. null 값은 생략, source=search면 (검색) 표기(AC-2).
        List<TextObject> fields = new ArrayList<>();
        addField(fields, LABEL_COFFEE, draft.coffeeName(), null);
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
        if (entry != null && entry.myTaste() != null && !entry.myTaste().isBlank()) {
            blocks.add(section(s -> s.text(markdownText("*" + MY_TASTE_LABEL + "*\n" + entry.myTaste()))));
        }

        // 출처 링크(FR-12) — context 블록에 <url|n> 형식 링크.
        String sources = sourcesText(draft.sources());
        if (sources != null) {
            blocks.add(context(c -> c.elements(List.of(markdownText(SOURCES_LABEL + ": " + sources)))));
        }

        blocks.add(divider());
        blocks.add(actions(asElements(
                button(b -> b.text(plainText(SAVE_LABEL)).style("primary")
                        .actionId(DefaultConversationRouter.ACTION_SAVE).value(saveValue(draft))),
                button(b -> b.text(plainText(CANCEL_LABEL)).style("danger")
                        .actionId(DefaultConversationRouter.ACTION_CANCEL).value(saveValue(draft)))
        )));
        return blocks;
    }

    private static String matchLine(MatchInfo match, String coffeeName) {
        if (match == null || match.type() == MatchInfo.MatchType.NEW) {
            return MATCH_NEW;
        }
        // 갱신/추가 구분은 pending(MatchInfo)에 없다 — "기록"으로 중립 표기(tasks T3-3 라인).
        return String.format(MATCH_EXISTING_FMT, coffeeName, match.date());
    }

    private static void addSourcedField(List<TextObject> fields, String label, Sourced<String> sourced) {
        if (sourced == null || sourced.value() == null || sourced.value().isBlank()) {
            return;
        }
        addField(fields, label, sourced.value(), sourced.source());
    }

    // source=search면 (검색) 표기, user/무출처면 표기 없음 (AC-2, FR-12).
    private static void addField(List<TextObject> fields, String label, String value, Source source) {
        String tag = source == Source.SEARCH ? SEARCH_TAG : "";
        fields.add(markdownText("*" + label + "*\n" + value + tag));
    }

    private static String officialNotesText(Sourced<List<String>> officialNotes) {
        if (officialNotes == null || officialNotes.value() == null || officialNotes.value().isEmpty()) {
            return null;
        }
        String joined = String.join(", ", officialNotes.value());
        return officialNotes.source() == Source.SEARCH ? joined + SEARCH_TAG : joined;
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
