package com.devwuu.mocha.pipeline;

import com.devwuu.mocha.domain.Entry;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.SearchSession;
import com.devwuu.mocha.llm.LlmClient;
import com.devwuu.mocha.llm.LlmException;
import com.devwuu.mocha.llm.LlmRequest;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 검색 세션의 후보 선정·턴 판정 — 전체 노트 메타를 LLM 컨텍스트에 넣어 후보를 고르고(data-model §4.2),
 * 단일 매치/복수 후보/무후보 재질문/상한 종료를 가른다 (ref: spec FR-20, plan.md §3·#ADR-25, changes/0011 TΔ5).
 * 수정 의도(FR-21)가 감지되면 대상 확정 정도에 따라 수정 전환 분기(날짜 목록 선택·전환 확정)로 판정한다 —
 * 감지는 같은 검색 턴 스키마의 확장 필드로 받아 LLM 콜 수를 늘리지 않는다(ADR-27, changes/0012 TΔ4).
 * <p>세션 저장·폐기와 응답 전송(카드 재전송·목록·멘트)은 배선 단(ConversationFlows)의 몫이다 — 여기는
 * 상태 없는 판정만 한다. 추출과 같은 경량 모델(`mocha.llm.model`)을 공용한다(새 모델 키 없음).
 * <p>POLICY: LLM candidate_slugs는 서버가 실존 노트로 재검증한다 — 실존 slug가 0개면 무후보로 수렴
 * (환각 필터, FR-14 matched_slug 재검증과 동일 정신) (ref: data-model.md#4.2).
 * <p>POLICY: 무후보 재질문은 세션당 {@code mocha.search-session.max-requery}(기본 0=무제한)로 상한 —
 * 도달 시 세션 종료로 판정한다 (ref: spec FR-20/AC-33, plan.md#ADR-25).
 */
public class NoteSearchService {

    // 날짜/타임스탬프는 Asia/Seoul 기준 — pending·검색 세션 store와 동일(V-3).
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private static final String SCHEMA_NAME = "note_search_candidates";

    // structured output 스키마(data-model.md#4.2). strict — 후보 선정 + 수정 의도 신호(FR-21, changes/0012)를
    // 같은 턴 1콜에 싣는다(별도 감지 콜 없음, findings-TΔ0 Q1).
    private static final String JSON_SCHEMA = """
            {
              "type": "object",
              "additionalProperties": false,
              "required": ["candidate_slugs", "edit_requested", "edit_target_date"],
              "properties": {
                "candidate_slugs": {
                  "type": "array",
                  "items": {"type": "string"},
                  "description": "사용자가 찾는 노트의 후보 slug — 관련도순. 확신 있는 단일 매치면 1개만, 들어맞는 노트가 없으면 빈 배열."
                },
                "edit_requested": {
                  "type": "boolean",
                  "description": "사용자가 찾은/제시된 기록을 고치고 싶다는 수정 의도면 true. 대상 노트 slug는 candidate_slugs에 담는다(확정이면 1개만)."
                },
                "edit_target_date": {
                  "type": ["string", "null"],
                  "description": "edit_date_options가 제시된 상태에서 사용자가 고른 날짜(YYYY-MM-DD). 목록에 있는 날짜만 담고, 날짜 선택 답변이 아니면 null."
                }
              }
            }
            """;

    private static final String SYSTEM_PROMPT = """
            너는 사용자의 커피 시음 기록(노트) 중에서 사용자가 찾는 기록의 후보를 고르는 검색기다.
            입력 JSON: message(이번 텍스트), clues(이 검색 세션의 누적 단서), presented_candidates(직전에 제시한 후보 slug — 제시 순서), edit_date_options(수정 대상으로 제시한 엔트리 날짜 목록 — 제시 순서), notes(전체 노트 메타).
            - notes에 실제로 있는 slug만 candidate_slugs에 담는다. slug를 지어내지 않는다.
            - 단서가 하나의 노트를 확신 있게 가리키면 그 slug 1개만 담는다.
            - 여러 노트가 그럴듯하면 관련도 순으로 모두 담는다.
            - 들어맞는 노트가 없으면 빈 배열로 둔다. 억지로 고르지 않는다.
            - "첫 번째", "두 번째" 같은 선택 표현은 presented_candidates의 제시 순서로 해석한다.
            - 사용자가 찾은 기록을 고치고/수정하고 싶다는 의도("그거 수정할래", "날짜 고쳐줘")면 edit_requested를 true로 하고 대상 노트 slug를 candidate_slugs에 담는다.
            - edit_date_options가 비어 있지 않으면 사용자는 고칠 기록의 날짜를 고르는 중이다 — 이번 텍스트가 그 목록의 날짜를 가리키면("두 번째", "7월 1일 거") edit_target_date에 그 날짜를 YYYY-MM-DD로 담는다. 목록에 없는 날짜를 지어내지 않는다.
            """;

    private final LlmClient llmClient;
    private final ObjectMapper mapper;
    private final int maxRequery; // 0 = 무제한 (mocha.search-session.max-requery)
    private final Clock clock;

    public NoteSearchService(LlmClient llmClient, ObjectMapper mapper, int maxRequery) {
        this(llmClient, mapper, maxRequery, Clock.system(SEOUL));
    }

    // 테스트에서 세션 시작 시각을 고정하기 위한 생성자(store들과 동일 패턴).
    NoteSearchService(LlmClient llmClient, ObjectMapper mapper, int maxRequery, Clock clock) {
        this.llmClient = llmClient;
        this.mapper = mapper;
        this.maxRequery = maxRequery;
        this.clock = clock;
    }

    /**
     * 검색 세션의 한 턴을 판정한다 — 세션이 없으면 시작(누적 단서·재질문 횟수 0에서 출발)이고,
     * 있으면 계속(단서 누적·직전 후보를 선택 해석 기준으로 주입)이다.
     *
     * @param text            이번 수신 텍스트(최초 질의·추가 단서·"두 번째" 같은 선택 답 전부).
     * @param existingSession 진행 중 세션(TTL 내). 비어 있으면 새 세션을 시작한다.
     * @param notes           후보 선정 대상 전체 노트. 비어 있으면 LLM 호출 없이 무후보로 수렴한다.
     * @throws LlmException 후보 선정 호출/스키마 실패(plan §7 — 호출부는 세션을 유지하고 안내로 수렴).
     */
    public SearchOutcome handle(String text, Optional<SearchSession> existingSession, List<Note> notes) {
        SearchSession base = existingSession.orElseGet(
                () -> new SearchSession(List.of(), List.of(), 0, OffsetDateTime.now(clock)));
        List<String> clues = appended(base.clues(), text);

        // 날짜 선택 대기 컨텍스트(FR-21/AC-42): 수정 전환 대상 slug가 실존 노트일 때만 날짜 목록을 싣는다 —
        // 대상 노트 소실(dangling slug)은 일반 검색 턴으로 조용히 복귀한다(방어).
        Note editPending = base.pendingEditSlug() == null ? null
                : notes.stream().filter(n -> base.pendingEditSlug().equals(n.slug())).findFirst().orElse(null);
        List<LocalDate> dateOptions = editPending == null ? List.of() : entryDates(editPending);

        SearchSelection selection = notes.isEmpty()
                ? new SearchSelection(List.of())
                : select(text, clues, base.candidateSlugs(), dateOptions, notes);

        // 날짜 선택 답변 판정(AC-42) — 제시 목록에 실존하는 날짜만 인정한다(환각 필터와 동일 정신).
        if (editPending != null) {
            LocalDate chosen = parseDate(selection.editTargetDate());
            if (chosen != null && dateOptions.contains(chosen)) {
                SearchSession next = new SearchSession(
                        clues, List.of(editPending.slug()), base.requeryCount(), base.createdAt(), editPending.slug());
                return new SearchOutcome(SearchOutcome.Type.EDIT_TARGET_CONFIRMED, next,
                        List.of(hitOf(editPending)), chosen, List.of());
            }
        }

        List<Note> matched = existingOnly(selection.candidateSlugs(), notes);

        if (matched.isEmpty()) {
            // 날짜 선택 대기 중인데 날짜도 새 후보도 해석하지 못했다 → 날짜 목록을 다시 제시한다(선택 계속, AC-42).
            if (editPending != null) {
                SearchSession next = new SearchSession(
                        clues, base.candidateSlugs(), base.requeryCount(), base.createdAt(), editPending.slug());
                return new SearchOutcome(SearchOutcome.Type.EDIT_DATE_CHOICES, next,
                        List.of(hitOf(editPending)), null, dateOptions);
            }
            // POLICY: 재질문 상한 도달 시 세션 종료 판정 — 0=무제한 (spec FR-20/AC-33, ADR-25).
            if (maxRequery > 0 && base.requeryCount() >= maxRequery) {
                return new SearchOutcome(SearchOutcome.Type.LIMIT_REACHED, null, List.of());
            }
            // 직전 제시 후보는 유지한다 — 재질문 답변이 "두 번째" 같은 선택일 수도 있어 해석 기준을 잃지 않는다.
            SearchSession next = new SearchSession(
                    clues, base.candidateSlugs(), base.requeryCount() + 1, base.createdAt());
            return new SearchOutcome(SearchOutcome.Type.NO_MATCH, next, List.of());
        }

        List<SearchHit> hits = matched.stream().map(NoteSearchService::hitOf).toList();
        List<String> slugs = matched.stream().map(Note::slug).toList();

        // 수정 의도 + 대상 노트 단일 확정(FR-21) — 엔트리 1건이면 즉시 전환 확정, 복수면 날짜 목록 선택(AC-42).
        if (selection.editRequested() && matched.size() == 1) {
            Note target = matched.get(0);
            List<LocalDate> dates = entryDates(target);
            if (dates.size() == 1) {
                SearchSession next = new SearchSession(
                        clues, slugs, base.requeryCount(), base.createdAt(), target.slug());
                return new SearchOutcome(SearchOutcome.Type.EDIT_TARGET_CONFIRMED, next, hits, dates.get(0), List.of());
            }
            if (dates.size() > 1) {
                SearchSession next = new SearchSession(
                        clues, slugs, base.requeryCount(), base.createdAt(), target.slug());
                return new SearchOutcome(SearchOutcome.Type.EDIT_DATE_CHOICES, next, hits, null, dates);
            }
            // 엔트리 0건(비정상 데이터)은 전환할 대상 date가 없다 — 일반 검색 분기로 폴백.
        }

        // 새 후보가 잡히면 날짜 선택 대기 상태는 해제된다 — 화제가 다른 노트로 옮겨간 것(pendingEditSlug 미보존).
        SearchSession next = new SearchSession(clues, slugs, base.requeryCount(), base.createdAt());
        SearchOutcome.Type type = hits.size() == 1
                ? SearchOutcome.Type.SINGLE_MATCH
                : SearchOutcome.Type.MULTIPLE_CANDIDATES;
        return new SearchOutcome(type, next, hits);
    }

    // data-model §4.2 요청을 조립해 LLM 후보 선정(+수정 의도 신호)을 호출한다.
    private SearchSelection select(
            String text, List<String> clues, List<String> presentedCandidates,
            List<LocalDate> editDateOptions, List<Note> notes) {
        String userPrompt = buildUserPrompt(text, clues, presentedCandidates, editDateOptions, notes);
        return llmClient.complete(new LlmRequest<>(
                SCHEMA_NAME, JSON_SCHEMA, SYSTEM_PROMPT, userPrompt, SearchSelection.class));
    }

    // 실존 slug만 남긴다(환각 필터·순서 보존·중복 제거) — data-model §4.2.
    private static List<Note> existingOnly(List<String> candidateSlugs, List<Note> notes) {
        Map<String, Note> bySlug = new LinkedHashMap<>();
        for (Note note : notes) {
            bySlug.put(note.slug(), note);
        }
        return candidateSlugs.stream()
                .distinct()
                .map(bySlug::get)
                .filter(Objects::nonNull)
                .toList();
    }

    private String buildUserPrompt(
            String text, List<String> clues, List<String> presentedCandidates,
            List<LocalDate> editDateOptions, List<Note> notes) {
        List<NotePayload> payloads = notes.stream().map(NoteSearchService::payloadOf).toList();
        try {
            return mapper.writeValueAsString(
                    new SearchRequest(text, clues, presentedCandidates, editDateOptions, payloads));
        } catch (RuntimeException e) {
            throw new LlmException("검색 후보 선정 요청 직렬화 실패", e);
        }
    }

    // LLM이 돌려준 날짜 문자열의 방어적 해석 — 형식 위반은 무시(null)로 수렴한다(V-1과 동일 정신).
    private static LocalDate parseDate(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (RuntimeException e) {
            return null;
        }
    }

    // 수정 대상 선택지가 되는 엔트리 날짜 목록 — 저장 규칙(날짜 오름차순·중복 없음)을 방어적으로 재보장한다.
    private static List<LocalDate> entryDates(Note note) {
        if (note.entries() == null) {
            return List.of();
        }
        return note.entries().stream()
                .map(Entry::date)
                .filter(Objects::nonNull)
                .distinct()
                .sorted()
                .toList();
    }

    private static List<String> appended(List<String> clues, String text) {
        List<String> merged = new ArrayList<>(clues);
        merged.add(text);
        return List.copyOf(merged);
    }

    private static SearchHit hitOf(Note note) {
        return new SearchHit(note.slug(), coffeeNameValue(note), roasteryValue(note), latestDate(note));
    }

    private static String coffeeNameValue(Note note) {
        return note.coffeeName() == null ? null : note.coffeeName().value();
    }

    private static String roasteryValue(Note note) {
        return note.roastery() == null ? null : note.roastery().value();
    }

    // 카드 재전송 대상 엔트리 = 최신(기본, FR-20). 저장 규칙상 entries는 날짜 오름차순이지만 방어적으로 최댓값을 취한다.
    private static LocalDate latestDate(Note note) {
        if (note.entries() == null) {
            return null;
        }
        return note.entries().stream()
                .map(Entry::date)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .orElse(null);
    }

    /** data-model.md#4.2 요청 스키마 대응 내부 페이로드(snake_case 직렬화). */
    private record SearchRequest(
            String message, List<String> clues, List<String> presentedCandidates,
            List<LocalDate> editDateOptions, List<NotePayload> notes) {
    }

    /** 후보 선정 컨텍스트에 싣는 노트 메타 축약 — 커피명·로스터리·원산지·official_notes·최근 시음일(FR-20). */
    private record NotePayload(
            String slug, String coffeeName, String roastery, String origin,
            List<String> officialNotes, LocalDate lastTasted) {
    }

    private static NotePayload payloadOf(Note note) {
        return new NotePayload(
                note.slug(),
                note.coffeeName() == null ? null : note.coffeeName().value(),
                note.roastery() == null ? null : note.roastery().value(),
                note.origin() == null ? null : note.origin().value(),
                note.officialNotes() == null ? null : note.officialNotes().value(),
                latestDate(note));
    }
}
