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
 *       date 패치는 이번 시음 엔트리의 날짜 이동으로 반영한다(AC-39). record 모드는 종전과 동일하다(AC-Δ6).</li>
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
              "required": ["coffee_name","roastery","origin","process","roast_level","official_notes","my_taste","rating","date"],
              "properties": {
                "coffee_name":    {"type": ["string","null"], "description": "커피 이름 새 값. 사용자가 바꾸라 하지 않았으면 null."},
                "date":           {"type": ["string","null"], "description": "시음 날짜 새 값(YYYY-MM-DD). 사용자가 기록 날짜를 다른 날로 옮기라고 할 때만. 변경 요청 없으면 null."},
                "roastery":       {"type": ["string","null"], "description": "로스터리 새 값. 변경 요청 없으면 null."},
                "origin":         {"type": ["string","null"], "description": "원산지 새 값. 변경 요청 없으면 null."},
                "process":        {"type": ["string","null"], "description": "가공 방식 새 값. 변경 요청 없으면 null."},
                "roast_level":    {"type": ["string","null"], "description": "로스팅 정도 새 값. 변경 요청 없으면 null."},
                "official_notes": {"type": ["array","null"], "items": {"type": "string"}, "description": "로스터리 노트 새 값. 사용자가 직접 불러줄 때만. 변경 요청 없으면 null."},
                "my_taste":       {"type": ["string","null"], "description": "내가 느낀 맛 새 값. 변경 요청 없으면 null."},
                "rating":         {"type": ["string","null"], "enum": ["완전 내스타일","맛있다","맛은 있는데 내스타일은 아님","맛이 없다", null], "description": "4범주 평가 새 값. 변경 요청 없으면 null."}
              }
            }
            """;

    private static final String SYSTEM_PROMPT = """
            너는 이미 작성된 커피 기록 초안을 사용자의 수정 요청대로 고치는 편집기다. 아래 규칙을 반드시 지켜라.
            - 입력의 current는 지금 초안의 각 필드 값이고, revision은 사용자의 수정 요청 문장이다.
            - 사용자가 명시적으로 바꾸라고 한 필드에만 새 값을 넣는다. 나머지 필드는 전부 null로 둔다(= 변경 없음).
            - 수정 요청과 무관한 필드를 임의로 채우거나 지어내지 않는다. 검색으로 값을 새로 찾지도 않는다.
            - my_taste는 사용자가 표현한 감상을 원문 보존 위주로 반영한다. 산미/단맛 같은 맛 표현 수정은 my_taste에 담는다.
            - rating은 사용자가 만족도를 바꿀 때만 4범주 중 하나로 고른다. 아니면 null.
            - date는 사용자가 기록 날짜를 다른 날로 옮기라고 명시할 때만 YYYY-MM-DD로 채운다. current의 date를 기준으로 계산한다. 아니면 null.
            입력은 current/revision을 담은 JSON으로 주어진다.
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
     * @throws LlmException 호출 실패, 또는 재시도 소진 후에도 스키마/도메인 위반이 남을 때(plan §7, V-1).
     */
    public ReviseOutcome revise(PendingNote pending, String revisionText) {
        String userPrompt = buildUserPrompt(pending.draft(), revisionText);
        LlmRequest<RevisionResult> request = new LlmRequest<>(
                SCHEMA_NAME, JSON_SCHEMA, SYSTEM_PROMPT, userPrompt, RevisionResult.class);
        RevisionResult patch = llmClient.complete(request);
        return applyPatch(pending, patch);
    }

    // 현재 draft 값 + 수정 요청을 사용자 프롬프트로 직렬화(snake_case). 부분 편집 근거로 현재 값을 함께 보여준다.
    private String buildUserPrompt(Note draft, String revisionText) {
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
            return mapper.writeValueAsString(new RevisionRequest(current, revisionText));
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
                reviseEntries(draft.entries(), patch, editMode),
                draft.createdAt(),
                draft.updatedAt());

        // mode·target·match·preview_ts·created_at 보존 — 필드 수정은 세션 종류·매칭·TTL·미리보기 대상을 바꾸지 않는다.
        return new ReviseOutcome(pending.withDraft(revisedDraft), coffeeNameRejected);
    }

    // 수정된 값이 있으면 source=user로 승격, 없으면 기존 필드(출처 포함) 유지.
    private static Sourced<String> promote(Sourced<String> current, String revised) {
        return revised != null ? Sourced.user(revised) : current;
    }

    private static Sourced<List<String>> promoteList(Sourced<List<String>> current, List<String> revised) {
        return revised != null ? Sourced.user(List.copyOf(revised)) : current;
    }

    // 이번 시음 엔트리 1건을 제자리 갱신 — 엔트리 개수 불변(AC-5). my_taste/rating(+edit 모드 date)만 수정 대상.
    private static List<Entry> reviseEntries(List<Entry> entries, RevisionResult patch, boolean editMode) {
        if (entries == null || entries.isEmpty()) {
            return entries;
        }
        List<Entry> revised = new ArrayList<>(entries);
        int last = revised.size() - 1;
        Entry entry = revised.get(last);
        revised.set(last, new Entry(
                // POLICY: date 패치는 edit 모드 한정(FR-21/AC-39) — record 경로의 날짜는 추출 시점에 확정되며
                //         수정 대상이 아니다(신규 기록 흐름 불변, changes/0012 AC-Δ6).
                editMode && patch.date() != null ? patch.date() : entry.date(),
                patch.myTaste() != null ? patch.myTaste() : entry.myTaste(),
                patch.rating() != null ? patch.rating() : entry.rating(),
                entry.recipe(),  // 레시피는 사용자 발화 전용 — 수정 스키마에 없어 보존(ADR-22)
                entry.photos(),
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
     */
    public record ReviseOutcome(PendingNote pending, boolean coffeeNameRejected) {
    }

    /** 사용자 프롬프트 페이로드 — {current, revision}. */
    private record RevisionRequest(CurrentDraft current, String revision) {
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
