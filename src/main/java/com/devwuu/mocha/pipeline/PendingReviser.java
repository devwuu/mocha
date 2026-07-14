package com.devwuu.mocha.pipeline;

import com.devwuu.mocha.domain.Entry;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.PendingNote;
import com.devwuu.mocha.domain.Rating;
import com.devwuu.mocha.domain.Sourced;
import com.devwuu.mocha.llm.LlmClient;
import com.devwuu.mocha.llm.LlmException;
import com.devwuu.mocha.llm.LlmRequest;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * pending 수정 반영 — 확인 대기 중 들어온 수정 텍스트를 LLM으로 해석해 기존 draft에 병합한다
 * (ref: plan.md §1 [1] 수정 분기, ADR-3; spec FR-5/AC-5; data-model.md#2.3).
 * <p>현재 draft의 값들과 수정 텍스트를 함께 넘겨 "바뀌는 필드만" 패치({@link RevisionResult})로 받고, 그 필드를
 * draft에 덮어쓴 새 pending을 {@link ReviseOutcome}에 담아 돌려준다. 원본 pending은 변경하지 않는다.
 * <ul>
 *   <li>POLICY: pending 중 텍스트는 전부 수정 요청 — 새 노트/엔트리를 만들지 않는다. 기존 엔트리 1건을 제자리
 *       갱신하므로 엔트리 개수는 불변이다 (ref: spec FR-5/AC-5). 저장소를 건드리지 않는다(구조적으로 의존 없음).</li>
 *   <li>수정된 출처 표시 필드는 {@code source=user}로 승격한다 — 사용자가 방금 그 값을 직접 지정했기 때문.
 *       미리보기의 {@code (검색)} 표기는 출처가 user로 바뀌면 자연히 사라진다(AC-2 재료).</li>
 *   <li>edit 모드(FR-21, changes/0012 TΔ5): coffee_name 패치는 draft에 반영하지 않고 거부로 보고하며(V-9),
 *       date 패치는 이번 시음 엔트리의 날짜 이동으로 반영한다(AC-39).</li>
 *   <li>date 패치는 record 모드에도 반영한다(changes/0016 ADR-39, FR-5/AC-56 — 종전 "record 경로 날짜 무시"
 *       개정). record 모드에서 date가 바뀌면 매칭 표기(신규/갱신/추가) 재판정 재료를 {@link ReviseOutcome}에
 *       실어 저장소를 아는 호출부가 {@code match}를 갱신하게 한다(커피명 거부는 여전히 edit 모드 한정 V-9).</li>
 *   <li>상대 날짜("엊그제"·"어제")는 요청에 주입한 {@code today}(Asia/Seoul) 기준으로 해석한다(ADR-39).</li>
 * </ul>
 * <p>match(신규/기존 판정)·preview_ts·created_at은 보존한다 — 필드 수정은 매칭·TTL·미리보기 대상 메시지를
 * 바꾸지 않는다(같은 메시지를 edit로 갱신, data-model §2.3).
 */
public class PendingReviser {

    private static final String SCHEMA_NAME = "coffee_note_revision";

    // structured output 스키마 — 전 필드 required, 값 또는 null(변경 없음). rating은 4범주 enum + null만 허용(V-1).
    private static final String JSON_SCHEMA = """
            {
              "type": "object",
              "additionalProperties": false,
              "required": ["coffee_name","roastery","origin","process","roast_level","official_notes","my_taste","my_taste_original","rating","date"],
              "properties": {
                "coffee_name":    {"type": ["string","null"], "description": "커피 이름 새 값. 사용자가 바꾸라 하지 않았으면 null."},
                "date":           {"type": ["string","null"], "description": "시음 날짜 새 값(YYYY-MM-DD). 사용자가 기록 날짜를 다른 날로 옮기라고 할 때만. 변경 요청 없으면 null."},
                "roastery":       {"type": ["string","null"], "description": "로스터리 새 값. 변경 요청 없으면 null."},
                "origin":         {"type": ["string","null"], "description": "원산지 새 값. 변경 요청 없으면 null."},
                "process":        {"type": ["string","null"], "description": "가공 방식 새 값. 변경 요청 없으면 null."},
                "roast_level":    {"type": ["string","null"], "description": "로스팅 정도 새 값. 변경 요청 없으면 null."},
                "official_notes": {"type": ["array","null"], "items": {"type": "string"}, "description": "로스터리 노트 새 값. 사용자가 직접 불러줄 때만. 변경 요청 없으면 null."},
                "my_taste":       {"type": ["string","null"], "description": "내가 느낀 맛 새 값. 표현·뉘앙스 보존 + 한국어 음슴체 정규화(영어는 번역). 변경 요청 없으면 null."},
                "my_taste_original": {"type": ["string","null"], "description": "말한 그대로의 감상 표현 새 값(언어 불문, 요약·정규화·번역 없이 원문 보존). my_taste를 바꿀 때만 함께 채운다. 아니면 null."},
                "rating":         {"type": ["string","null"], "enum": ["완전 내스타일","맛있다","맛은 있는데 내스타일은 아님","맛이 없다", null], "description": "4범주 평가 새 값. 변경 요청 없으면 null."}
              }
            }
            """;

    private static final String SYSTEM_PROMPT = """
            너는 이미 작성된 커피 기록 초안을 사용자의 수정 요청대로 고치는 편집기다. 아래 규칙을 반드시 지켜라.
            - 입력의 current는 지금 초안의 각 필드 값이고, revision은 사용자의 수정 요청 문장이다.
            - 사용자가 명시적으로 바꾸라고 한 필드에만 새 값을 넣는다. 나머지 필드는 전부 null로 둔다(= 변경 없음).
            - 수정 요청과 무관한 필드를 임의로 채우거나 지어내지 않는다. 검색으로 값을 새로 찾지도 않는다.
            - coffee_name·roastery 같은 고유명사 필드를 고칠 때는 이름 그 자체만 담는다. 뒤에 붙은 조사·연결어미(는/은/이/가/를/을/고/랑/에서 등)는 잘라내고 이름만 남긴다.
              예: "로스터리는 카페 화고 달고 맛있더라" → roastery "카페 화"(❌ "카페 화고").
            - my_taste는 사용자가 표현한 감상을 요약·축약하지 않고, 문장 끝 어미만 한국어 음슴체로 바꿔 반영한다("맛있더라"→"맛있었음", 영어는 한국어로 번역). 맛 묘사·수식어·뉘앙스를 하나도 빼지 말고 그대로 보존한다 — 긴 감상을 통째로 "맛있었음" 따위로 줄이면 안 된다. 산미/단맛 같은 맛 표현 수정은 my_taste에 담는다.
              예: "산미 있으면서 깔끔한데 적당히 달아서 너무 부담스럽지 않은 맛? 또 먹고 싶더라" → my_taste "산미 있으면서 깔끔한데 적당히 달아서 너무 부담스럽지 않은 맛이었음. 또 먹고 싶었음"(❌ "맛있었음"). my_taste를 바꿀 때는 사용자가 말한 그대로의 표현을 my_taste_original에 원문 그대로(요약·정규화·번역 없이) 함께 담는다. my_taste를 바꾸지 않으면 my_taste_original도 null.
            - rating은 사용자가 만족도를 바꿀 때만 4범주 중 하나로 고른다. 아니면 null.
            - date는 사용자가 기록 날짜를 다른 날로 옮기라고 명시할 때만 YYYY-MM-DD로 채운다. "엊그제"·"어제"·"지난 금요일" 같은 상대 표현은 입력의 today(오늘 날짜)를 기준으로 계산한다. 아니면 null.
            입력은 current/revision/today를 담은 JSON으로 주어진다.
            """;

    private final LlmClient llmClient;
    private final ObjectMapper mapper;

    public PendingReviser(LlmClient llmClient, ObjectMapper mapper) {
        this.llmClient = llmClient;
        this.mapper = mapper;
    }

    /**
     * 수정 텍스트를 draft에 병합한 새 pending을 {@link ReviseOutcome}으로 돌려준다. 원본 {@code pending}은 변경하지 않는다.
     *
     * @param pending      라우터가 넘긴 유효(TTL 내) 확인 대기 노트. draft.entries는 이번 시음 엔트리 1건 전제.
     * @param revisionText 사용자의 수정 요청 원문(예: "산미는 낮음으로", "원산지는 콜롬비아야").
     * @param today        오늘 날짜(Asia/Seoul) — 상대 날짜 단서("엊그제")를 이 기준으로 해석한다(ADR-39).
     * @throws LlmException 호출 실패, 또는 재시도 소진 후에도 스키마/도메인 위반이 남을 때(plan §7, V-1).
     */
    public ReviseOutcome revise(PendingNote pending, String revisionText, LocalDate today) {
        String userPrompt = buildUserPrompt(pending.draft(), revisionText, today);
        LlmRequest<RevisionResult> request = new LlmRequest<>(
                SCHEMA_NAME, JSON_SCHEMA, SYSTEM_PROMPT, userPrompt, RevisionResult.class);
        RevisionResult patch = llmClient.complete(request);
        return applyPatch(pending, patch);
    }

    // 현재 draft 값 + 수정 요청 + today를 사용자 프롬프트로 직렬화(snake_case). 부분 편집 근거로 현재 값을,
    // 상대 날짜 해석 기준으로 today를 함께 보여준다(ADR-39).
    private String buildUserPrompt(Note draft, String revisionText, LocalDate today) {
        Entry entry = latestEntry(draft);
        CurrentDraft current = new CurrentDraft(
                valueOf(draft.coffeeName()),
                valueOf(draft.roastery()),
                valueOf(draft.origin()),
                valueOf(draft.process()),
                valueOf(draft.roastLevel()),
                listValueOf(draft.officialNotes()),
                entry == null ? null : entry.myTaste(),
                entry == null || entry.rating() == null ? null : entry.rating().label(),
                entry == null ? null : entry.date().toString());
        try {
            return mapper.writeValueAsString(
                    new RevisionRequest(current, revisionText, today == null ? null : today.toString()));
        } catch (RuntimeException e) {
            throw new LlmException("수정 요청 직렬화 실패", e);
        }
    }

    // 패치의 non-null 필드만 draft에 덮어쓴 새 pending 조립. 출처 표시 필드는 source=user로 승격.
    private ReviseOutcome applyPatch(PendingNote pending, RevisionResult patch) {
        Note draft = pending.draft();
        boolean editMode = pending.mode() == PendingNote.Mode.EDIT;

        // POLICY: 수정 세션에서 coffee_name은 draft에 반영하지 않는다 — 거부 안내(오타 포함 예외 없음)
        //         (ref: plan.md#ADR-27, data-model.md#V-9). 같은 값 그대로면 변경이 아니므로 거부로 치지 않는다.
        boolean coffeeNameRejected = editMode
                && patch.coffeeName() != null
                && !patch.coffeeName().equals(valueOf(draft.coffeeName()));

        Note revisedDraft = new Note(
                draft.slug(),
                editMode ? draft.coffeeName() : promote(draft.coffeeName(), patch.coffeeName()),
                promote(draft.roastery(), patch.roastery()),
                promote(draft.origin(), patch.origin()),
                promote(draft.process(), patch.process()),
                promote(draft.roastLevel(), patch.roastLevel()),
                promoteList(draft.officialNotes(), patch.officialNotes()),
                draft.sources(),
                reviseEntries(draft.entries(), patch),
                draft.createdAt(),
                draft.updatedAt());

        // record 모드에서 date가 바뀌면 매칭 표기 재판정 재료로 새 날짜를 싣는다 — 저장소를 아는 호출부가
        // match(EXISTING이면 대상 날짜)를 갱신한다(ADR-39, AC-56). edit 모드 날짜 이동은 dateConflict(V-10)로
        //         별도 처리하므로 여기 싣지 않는다.
        LocalDate recordDatePatch = !editMode && patch.date() != null ? patch.date() : null;

        // mode·target·match·preview_ts·created_at 보존 — 필드 수정은 세션 종류·매칭·TTL·미리보기 대상을 바꾸지 않는다.
        // (match 표기 자체의 날짜 갱신은 recordDatePatch를 받은 호출부 몫 — PendingReviser는 저장소를 모른다.)
        return new ReviseOutcome(pending.withDraft(revisedDraft), coffeeNameRejected, recordDatePatch);
    }

    // 수정된 값이 있으면 source=user로 승격, 없으면 기존 필드(출처 포함) 유지.
    private static Sourced<String> promote(Sourced<String> current, String revised) {
        return revised != null ? Sourced.user(revised) : current;
    }

    private static Sourced<List<String>> promoteList(Sourced<List<String>> current, List<String> revised) {
        return revised != null ? Sourced.user(List.copyOf(revised)) : current;
    }

    // 이번 시음 엔트리 1건을 제자리 갱신 — 엔트리 개수 불변(AC-5). my_taste/rating/date만 수정 대상.
    private static List<Entry> reviseEntries(List<Entry> entries, RevisionResult patch) {
        if (entries == null || entries.isEmpty()) {
            return entries;
        }
        List<Entry> revised = new ArrayList<>(entries);
        int last = revised.size() - 1;
        Entry entry = revised.get(last);
        revised.set(last, new Entry(
                // POLICY: date 패치는 record·edit 양쪽에 반영한다 — 저장 전 draft의 시음 날짜 정정은
                //         "엊그제 마신 거였어"류 실사용 요청으로, 못 고칠 이유가 없다(changes/0016 ADR-39,
                //         FR-5/AC-56 — changes/0012 "record 날짜 무시" 개정). edit 모드의 날짜 이동 충돌 경고
                //         (V-10)는 호출부가 별도로 재계산한다.
                patch.date() != null ? patch.date() : entry.date(),
                patch.myTaste() != null ? patch.myTaste() : entry.myTaste(),
                // POLICY: 감상 갱신 시 my_taste_original도 동반 갱신 — 원문 병존(V-11, ADR-30). 감상 미변경이면
                //         패치의 두 필드가 null이라 기존 원문을 보존한다(RevisionResult가 원문 누락을 정규화본으로 수렴).
                patch.myTasteOriginal() != null ? patch.myTasteOriginal() : entry.myTasteOriginal(),
                patch.rating() != null ? patch.rating() : entry.rating(),
                entry.recipe(),  // 레시피는 사용자 발화 전용 — 수정 스키마에 없어 보존(ADR-22)
                entry.updatedAt()));
        return List.copyOf(revised);
    }

    private static Entry latestEntry(Note draft) {
        List<Entry> entries = draft.entries();
        if (entries == null || entries.isEmpty()) {
            return null;
        }
        return entries.get(entries.size() - 1);
    }

    private static String valueOf(Sourced<String> field) {
        return field == null ? null : field.value();
    }

    private static List<String> listValueOf(Sourced<List<String>> field) {
        return field == null ? null : field.value();
    }

    /**
     * revise 결과 — 병합된 pending + 그 턴의 1회성 안내 재료(changes/0012 TΔ5, findings-TΔ0 Q3).
     * <p>날짜 이동 충돌 경고는 여기 실리지 않는다 — [저장] 확답까지 표기가 유지돼야 하는 상태라
     * {@link PendingNote#dateConflict()}에 영속한다(V-10). 충돌 판정은 저장소를 아는 호출부의 몫.
     *
     * @param pending            패치가 병합된 새 확인 대기 노트.
     * @param coffeeNameRejected edit 모드에서 커피명 변경 요청이 거부됐는지(V-9) — 거부 안내 전송 재료.
     * @param recordDatePatch    record 모드에서 date가 새로 지정됐을 때 그 날짜(아니면 null) — 매칭 표기
     *                           재판정 재료다. 저장소를 아는 호출부가 이 날짜로 {@code match}(EXISTING이면 대상
     *                           날짜)를 갱신해 미리보기 표기를 정합화한다(ADR-39, AC-56). edit 모드·미변경이면 null.
     */
    public record ReviseOutcome(PendingNote pending, boolean coffeeNameRejected, LocalDate recordDatePatch) {
    }

    /** 사용자 프롬프트 페이로드 — {current, revision, today}. today는 상대 날짜 해석 기준(ADR-39). */
    private record RevisionRequest(CurrentDraft current, String revision, String today) {
    }

    /** 현재 draft 필드 값 스냅샷(출처 벗겨낸 값만) — LLM 부분 편집의 근거. rating은 라벨 문자열로, date는 ISO 문자열로. */
    private record CurrentDraft(
            String coffeeName,
            String roastery,
            String origin,
            String process,
            String roastLevel,
            List<String> officialNotes,
            String myTaste,
            String rating,
            String date) {
    }
}
