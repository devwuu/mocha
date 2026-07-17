package com.devwuu.mocha.agent;

import com.devwuu.mocha.domain.Entry;
import com.devwuu.mocha.domain.MatchInfo;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.PendingNote;
import com.devwuu.mocha.repository.NoteRepository;
import com.devwuu.mocha.repository.PendingStore;
import com.devwuu.mocha.slack.PreviewMessenger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 쓰기 제안 축 tool 2종 — {@code propose_record}·{@code propose_edit}
 * (ref: specs/coffee-note-agent/data-model.md#3.3~3.4, plan#ADR-45; changes/0018 TΔ6).
 * <p>{@link AgentTools}(façade)가 조립하는 내부 협력자라 Spring 빈이 아니다 —
 * {@link com.devwuu.mocha.slack.SlackConversationFlows}의 flow 분할과 동일 규칙.
 * <p>strict schema(전 필드 required·additionalProperties=false)가 인자 <b>형태</b>를 보장하고, 값 수준
 * 규칙(V-1·5·8·10·11, 단일 대기)은 {@link ProposalValidator}가 결정론으로 강제한다 — 위반은 예외가 아니라
 * <b>사유를 담은 오류 결과</b>로 돌려줘 에이전트가 루프 안에서 정정한다(ADR-45, findings-TΔ0 §SDK).
 * <p>POLICY: 노트·pending 쓰기는 제안 tool 2종(propose_record/propose_edit)과 버튼 커밋뿐 — 읽기 tool·
 * 최종 텍스트는 파일 무변화 (ref: specs/coffee-note-agent/plan.md#ADR-45, AC-59).
 */
class ProposalTools {

    private static final Logger log = LoggerFactory.getLogger(ProposalTools.class);

    // slug 시각 세그먼트(ADR-28, V-2) — 생성 시각을 HHmmss로 붙여 날짜 세그먼트와 겹치지 않게 한다.
    private static final DateTimeFormatter SLUG_TIME = DateTimeFormatter.ofPattern("HHmmss");

    // 제안 tool 공통 인자 조각 — strict 계약(전 필드 required, additionalProperties=false)을 조각 단위로 지킨다.
    private static final String MY_TASTE_SCHEMA = """
            {"type":["string","null"],"description":"내 느낌 — 한국어 음슴체 정규화본(ADR-30). 미언급이면 null"}""";
    private static final String MY_TASTE_ORIGINAL_SCHEMA = """
            {"type":["string","null"],"description":"말한 그대로의 감상 원문 — my_taste를 채웠으면 함께 채운다(V-11)"}""";
    private static final String RATING_SCHEMA = """
            {"type":["string","null"],"enum":["완전 내스타일","맛있다","맛은 있는데 내스타일은 아님","맛이 없다",null],
             "description":"4범주 평가 — 미언급이면 null(V-1)"}""";
    private static final String RECIPE_SCHEMA = """
            {"type":["object","null"],"description":"발화 속 추출 레시피 — 사용자 발화 전용(검색·OCR 보강 금지), 전무면 null(V-8)","properties":{
              "dose_g":{"type":["number","null"],"description":"원두량(g)"},
              "water_ml":{"type":["number","null"],"description":"물량(ml)"},
              "grind":{"type":["string","null"],"description":"분쇄도"}
            },"required":["dose_g","water_ml","grind"],"additionalProperties":false}""";

    private static final String PROPOSE_RECORD_SCHEMA = """
            {"type":"object","properties":{
              "coffee_name":%s,
              "roastery":%s,
              "origin":%s,
              "process":%s,
              "roast_level":%s,
              "official_notes":%s,
              "my_taste":%s,
              "my_taste_original":%s,
              "rating":%s,
              "recipe":%s,
              "target_date":{"type":"string","description":"시음일 YYYY-MM-DD — 상대 날짜(\\"어제\\")는 컨텍스트의 today 기준으로 절대화해 보낸다"},
              "match":{"type":"object","description":"신규/기존 판정 — 기존 노트의 시음 기록이면 existing(list_notes로 대조)","properties":{
                "type":{"type":"string","enum":["new","existing"]},
                "slug":{"type":["string","null"],"description":"existing일 때 대상 노트 slug"},
                "date":{"type":["string","null"],"description":"existing일 때 대상 날짜 YYYY-MM-DD"}
              },"required":["type","slug","date"],"additionalProperties":false},
              "sources":{"type":"array","items":{"type":"string"},"description":"검색 참조 링크 — 동일성 가드를 통과한 출처만(AC-58)"}
            },"required":["coffee_name","roastery","origin","process","roast_level","official_notes","my_taste","my_taste_original","rating","recipe","target_date","match","sources"],"additionalProperties":false}"""
            .formatted(
                    sourcedSchema("커피 이름 — 기록의 정체성이자 검색 앵커(검색 보강 금지)", false),
                    sourcedSchema("로스터리", true),
                    sourcedSchema("원산지 — 블렌드는 여러 원산지를 쉼표로 나열한 한 문자열", true),
                    sourcedSchema("가공 방식", true),
                    sourcedSchema("로스팅 정도", true),
                    sourcedNotesSchema("로스터리 전시 테이스팅 노트 — 로스터리 공식 출처 한정(FR-3)"),
                    MY_TASTE_SCHEMA, MY_TASTE_ORIGINAL_SCHEMA, RATING_SCHEMA, RECIPE_SCHEMA);

    // POLICY: 수정에서 coffee_name은 바뀌지 않는다 — 이 patch 스키마에 필드 자체가 없다(구조 차단) + 거부 안내
    //         (ref: specs/coffee-note-agent/plan.md#ADR-45, data-model.md#V-9, spec AC-38).
    private static final String PROPOSE_EDIT_SCHEMA = """
            {"type":"object","properties":{
              "slug":{"type":"string","description":"수정 대상 노트 slug — list_notes/get_note로 확인한 실존 slug"},
              "date":{"type":"string","description":"수정 대상 엔트리 날짜 YYYY-MM-DD — get_note 응답의 entries[].date"},
              "patch":{"type":"object","description":"바꿀 필드만 채우는 부분 패치 — null은 유지. 커피 이름은 바꿀 수 없다(다른 커피는 새 기록)","properties":{
                "roastery":%s,
                "origin":%s,
                "process":%s,
                "roast_level":%s,
                "official_notes":%s,
                "my_taste":%s,
                "my_taste_original":%s,
                "rating":%s,
                "recipe":%s,
                "new_date":{"type":["string","null"],"description":"날짜 이동처 YYYY-MM-DD — 이동 없으면 null. 충돌은 서버가 계산해 경고한다(V-10)"}
              },"required":["roastery","origin","process","roast_level","official_notes","my_taste","my_taste_original","rating","recipe","new_date"],"additionalProperties":false}
            },"required":["slug","date","patch"],"additionalProperties":false}"""
            .formatted(
                    sourcedSchema("로스터리 새 값", true),
                    sourcedSchema("원산지 새 값", true),
                    sourcedSchema("가공 방식 새 값", true),
                    sourcedSchema("로스팅 정도 새 값", true),
                    sourcedNotesSchema("공식 테이스팅 노트 새 값"),
                    MY_TASTE_SCHEMA, MY_TASTE_ORIGINAL_SCHEMA, RATING_SCHEMA, RECIPE_SCHEMA);

    private final NoteRepository noteRepository;
    private final PendingStore pendingStore;
    private final PreviewMessenger previewMessenger;
    private final ProposalValidator validator;
    private final ConversationTranscript transcript;
    private final ObjectMapper mapper;
    private final Clock clock;

    ProposalTools(NoteRepository noteRepository, PendingStore pendingStore, PreviewMessenger previewMessenger,
                  ProposalValidator validator, ConversationTranscript transcript, ObjectMapper mapper,
                  Clock clock) {
        this.noteRepository = noteRepository;
        this.pendingStore = pendingStore;
        this.previewMessenger = previewMessenger;
        this.validator = validator;
        this.transcript = transcript;
        this.mapper = mapper;
        this.clock = clock;
    }

    // 출처 표시 인자 조각(V-5) — coffee_name만 search 불허(검색 앵커·정체성). null이면 미언급.
    private static String sourcedSchema(String description, boolean searchEnrichable) {
        return """
                {"type":["object","null"],"description":"%s","properties":{
                  "value":{"type":["string","null"],"description":"미언급이면 null — 추측 금지"},
                  "source":{"type":["string","null"],"enum":[%s],"description":"value를 채웠으면 출처 자기 보고(V-5)"}
                },"required":["value","source"],"additionalProperties":false}"""
                .formatted(description,
                        searchEnrichable ? "\"user\",\"photo\",\"search\",null" : "\"user\",\"photo\",null");
    }

    // official_notes 변형 — 값이 문자열 배열이라는 점만 다르다.
    private static String sourcedNotesSchema(String description) {
        return """
                {"type":["object","null"],"description":"%s","properties":{
                  "value":{"type":["array","null"],"items":{"type":"string"},"description":"미확인이면 null — 추측 금지"},
                  "source":{"type":["string","null"],"enum":["user","photo","search",null],"description":"value를 채웠으면 출처 자기 보고(V-5)"}
                },"required":["value","source"],"additionalProperties":false}""".formatted(description);
    }

    // ---- propose_record (data-model §3.3, FR-2/FR-5) ----

    AgentTool proposeRecord(String userId, String channelId) {
        return new AgentTool(
                "propose_record",
                "신규 시음 기록(또는 기존 노트에 더하는 새 시음)을 제안한다 — 검증 통과 시 확인 대기(pending)가 "
                        + "만들어지고 미리보기가 전송된다. 저장은 사용자의 [저장] 버튼만 한다. 확인 대기 중 같은 커피의 "
                        + "재호출은 대기 내용 갱신이다(수정 발화 반영). 검증 거부는 사유를 돌려주니 정정해 재호출해라.",
                PROPOSE_RECORD_SCHEMA,
                argumentsJson -> executeProposeRecord(userId, channelId, argumentsJson));
    }

    private String executeProposeRecord(String userId, String channelId, String argumentsJson) {
        ProposeRecordArgs args = mapper.readValue(argumentsJson, ProposeRecordArgs.class);
        PendingNote pending = pendingStore.get(userId).orElse(null);
        // POLICY: 제안 tool의 서버 검증 실패는 오류 사유를 tool 결과로 반환 — 조용한 드롭·서버 대행 금지
        //         (ref: specs/coffee-note-agent/plan.md#ADR-45, AC-9). 단일 대기 거부(AC-30)도 여기 수렴한다.
        ToolValidation<RecordProposal> validation = validator.validateRecord(args, pending);
        if (validation instanceof ToolValidation.Rejected<RecordProposal>(String reason)) {
            log.info("propose_record 검증 거부: user={} reason={}", userId, reason);
            return ToolSupport.errorOutput(mapper, reason);
        }
        RecordProposal proposal = ((ToolValidation.Ok<RecordProposal>) validation).value();

        // 환각 필터(get_note 미존재 오류와 같은 정신): match=existing의 대상 노트가 실존해야 커밋(upsertEntry)이
        // 유령 slug로 새 파일을 만들지 않는다.
        if (proposal.match().type() == MatchInfo.MatchType.EXISTING
                && ToolSupport.resolveNote(noteRepository, proposal.match().slug()).isEmpty()) {
            log.info("propose_record 검증 거부: user={} reason=미존재 match.slug {}", userId, proposal.match().slug());
            return ToolSupport.errorOutput(mapper, ToolSupport.missingNoteReason(proposal.match().slug()));
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        Entry entry = new Entry(proposal.targetDate(), proposal.myTaste(), proposal.myTasteOriginal(),
                proposal.rating(), proposal.recipe(), now);
        Note draft = new Note(
                recordSlug(proposal, pending, now),
                proposal.meta().coffeeName(), proposal.meta().roastery(), proposal.meta().origin(),
                proposal.meta().process(), proposal.meta().roastLevel(), proposal.meta().officialNotes(),
                proposal.meta().sources(), List.of(entry), now, now);

        // FR-5 갱신 경로: 검증을 통과한 대기 중 재호출(같은 커피)은 preview_ts·created_at을 보존해 같은
        // 미리보기 메시지를 edit로 갱신한다(재전송 아님, data-model §2.3).
        PendingNote next = pending == null
                ? new PendingNote(draft, proposal.match(), null, now)
                : new PendingNote(PendingNote.Mode.RECORD, draft, null, false, proposal.match(),
                        pending.previewTs(), pending.createdAt());

        String failure = persistAndPublish(userId, channelId, pending, next);
        if (failure != null) {
            return ToolSupport.errorOutput(mapper, failure);
        }
        // 제안 성공 = 트랜스크립트 접힘(ADR-46 규칙 ①) — 이후 문맥은 pending draft가 대신한다.
        transcript.clear(userId, ConversationTranscript.FoldTrigger.PROPOSAL_ACCEPTED);
        log.info("propose_record 수용: user={} slug={} match={} updated={}",
                userId, draft.slug(), proposal.match().type(), pending != null);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("proposed", true);
        result.put("slug", draft.slug());
        result.put("target_date", proposal.targetDate().toString());
        result.put("updated_existing_pending", pending != null);
        return mapper.writeValueAsString(result);
    }

    // slug 확정(V-2): 기존 노트 대상은 그 slug, 신규는 최초 기록일+생성 시각 대체키. 갱신 경로에서 직전 제안도
    // 신규였으면 할당된 slug를 보존한다 — 대기 중 정정마다 slug가 흔들리지 않게(구 draft 병합과 동일 효과).
    private String recordSlug(RecordProposal proposal, PendingNote pending, OffsetDateTime now) {
        if (proposal.match().type() == MatchInfo.MatchType.EXISTING) {
            return proposal.match().slug();
        }
        if (pending != null && pending.match() != null
                && pending.match().type() == MatchInfo.MatchType.NEW && pending.draft().slug() != null) {
            return pending.draft().slug();
        }
        return noteRepository.nextAvailableSlug(proposal.targetDate() + "-" + now.format(SLUG_TIME));
    }

    // ---- propose_edit (data-model §3.4, FR-21) ----

    AgentTool proposeEdit(String userId, String channelId) {
        return new AgentTool(
                "propose_edit",
                "저장된 노트의 기존 시음 엔트리 수정을 제안한다 — patch에 바꿀 필드만 채우면(null=유지) 검증 후 "
                        + "✏️ 미리보기가 전송되고, 저장은 사용자의 [저장] 버튼만 한다. 커피 이름은 바꿀 수 없다"
                        + "(다른 커피는 propose_record로 새 기록). 같은 대상의 재호출은 대기 중 내용에 누적 반영된다.",
                PROPOSE_EDIT_SCHEMA,
                argumentsJson -> executeProposeEdit(userId, channelId, argumentsJson));
    }

    private String executeProposeEdit(String userId, String channelId, String argumentsJson) {
        ProposeEditArgs args = mapper.readValue(argumentsJson, ProposeEditArgs.class);
        Optional<Note> noteOpt = ToolSupport.resolveNote(noteRepository, args.slug());
        if (noteOpt.isEmpty()) {
            return ToolSupport.errorOutput(mapper, ToolSupport.missingNoteReason(args.slug()));
        }
        Note note = noteOpt.get();
        PendingNote pending = pendingStore.get(userId).orElse(null);
        ToolValidation<EditProposal> validation = validator.validateEdit(args, note, pending);
        if (validation instanceof ToolValidation.Rejected<EditProposal>(String reason)) {
            log.info("propose_edit 검증 거부: user={} reason={}", userId, reason);
            return ToolSupport.errorOutput(mapper, reason);
        }
        EditProposal proposal = ((ToolValidation.Ok<EditProposal>) validation).value();

        // FR-5 후속 수정: 같은 대상의 재호출(검증이 보장)은 대기 중 draft 위에 누적 적용하고, 신규 진입은
        // 원본 노트+대상 엔트리 사본에서 시작한다(구 수정 세션 진입과 동일, data-model §2.3).
        OffsetDateTime now = OffsetDateTime.now(clock);
        Note base = pending != null ? pending.draft() : editBaseDraft(note, proposal.targetDate());
        // new_date=대상 자신의 날짜는 검증이 "이동 아님"으로 정규화한다(V-10) — 누적 draft에서는 직전 이동을
        // 원위치로 되돌리는 명시 지정이므로, 원시 인자에 값이 있었으면 대상 날짜로 복원한다.
        boolean explicitRestore = proposal.newDate() == null && args.patch() != null
                && args.patch().newDate() != null && !args.patch().newDate().isBlank();
        LocalDate entryDate = proposal.newDate() != null ? proposal.newDate()
                : explicitRestore ? proposal.targetDate()
                : latestEntry(base).date();
        Note draft = applyEditPatch(base, proposal, entryDate, now);

        // POLICY: 날짜 이동 충돌(V-10)은 서버가 계산해 pending에 싣는다 — 미리보기 덮어쓰기 경고의 근거.
        //         제안마다 현 draft 기준 재계산: new_date 없는 재호출에서도 직전 이동의 경고가 유지된다
        //         (ref: specs/coffee-note-agent/data-model.md#V-10, plan.md#ADR-45, spec AC-39).
        boolean dateConflict = !entryDate.equals(proposal.targetDate())
                && note.entries().stream().anyMatch(e -> entryDate.equals(e.date()));

        PendingNote next = pending != null
                ? pending.withDraft(draft).withDateConflict(dateConflict)
                : new PendingNote(PendingNote.Mode.EDIT, draft,
                        new PendingNote.EditTarget(note.slug(), proposal.targetDate()), null, null, now)
                        .withDateConflict(dateConflict);

        String failure = persistAndPublish(userId, channelId, pending, next);
        if (failure != null) {
            return ToolSupport.errorOutput(mapper, failure);
        }
        // 제안 성공 = 트랜스크립트 접힘(ADR-46 규칙 ①) — 이후 문맥은 pending draft가 대신한다.
        transcript.clear(userId, ConversationTranscript.FoldTrigger.PROPOSAL_ACCEPTED);
        log.info("propose_edit 수용: user={} slug={} target={} movedTo={} conflict={} updated={}",
                userId, note.slug(), proposal.targetDate(),
                entryDate.equals(proposal.targetDate()) ? null : entryDate, dateConflict, pending != null);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("proposed", true);
        result.put("slug", note.slug());
        result.put("date", proposal.targetDate().toString());
        result.put("entry_date", entryDate.toString());
        result.put("date_conflict", dateConflict);
        return mapper.writeValueAsString(result);
    }

    // 수정 세션의 시작 draft — 대상 노트+대상 엔트리 1건을 담은 사본(원본 참조는 pending.target에 남는다).
    private static Note editBaseDraft(Note note, LocalDate targetDate) {
        Entry target = note.entries().stream()
                .filter(e -> targetDate.equals(e.date()))
                .findFirst().orElseThrow(); // 실존은 검증(validateEdit)이 이미 보장했다.
        return new Note(
                note.slug(), note.coffeeName(), note.roastery(), note.origin(), note.process(),
                note.roastLevel(), note.officialNotes(), note.sources(),
                List.of(target), note.createdAt(), note.updatedAt());
    }

    // patch(null=유지)를 base draft에 적용한 새 draft — coffee_name은 proposal에 없어 항상 유지된다(V-9).
    private static Note applyEditPatch(Note base, EditProposal proposal, LocalDate entryDate, OffsetDateTime now) {
        Entry baseEntry = latestEntry(base);
        // my_taste 갱신은 원문과 함께 움직인다(V-11) — 감상을 안 바꾸는 patch는 양쪽 다 유지.
        Entry entry = new Entry(
                entryDate,
                proposal.myTaste() != null ? proposal.myTaste() : baseEntry.myTaste(),
                proposal.myTaste() != null ? proposal.myTasteOriginal() : baseEntry.myTasteOriginal(),
                proposal.rating() != null ? proposal.rating() : baseEntry.rating(),
                proposal.recipe() != null ? proposal.recipe() : baseEntry.recipe(),
                now);
        return new Note(
                base.slug(), base.coffeeName(),
                proposal.roastery() != null ? proposal.roastery() : base.roastery(),
                proposal.origin() != null ? proposal.origin() : base.origin(),
                proposal.process() != null ? proposal.process() : base.process(),
                proposal.roastLevel() != null ? proposal.roastLevel() : base.roastLevel(),
                proposal.officialNotes() != null ? proposal.officialNotes() : base.officialNotes(),
                base.sources(), List.of(entry), base.createdAt(), base.updatedAt());
    }

    // ---- 제안 공통: pending 영속 + 미리보기 전송 ----

    // POLICY: 제안은 pending까지 — 노트 커밋은 [저장] 버튼(action_id)만, 에이전트 미경유
    //         (ref: specs/coffee-note-agent/plan.md#ADR-45, #ADR-3).
    // put을 먼저 해 재시작 생존(NFR-2)을 확보하고, 전송 후 확정된 preview_ts로 재저장한다. 전송 실패 시
    // 이전 상태로 되돌린다 — 신규는 "미리보기 없으면 pending 없음", 갱신은 무변화(ADR-48 정신).
    private String persistAndPublish(String userId, String channelId, PendingNote before, PendingNote next) {
        pendingStore.put(userId, next);
        try {
            String previewTs = previewMessenger.publish(channelId, next);
            pendingStore.put(userId, next.withPreviewTs(previewTs));
            return null;
        } catch (Exception publishFailure) {
            if (before == null) {
                pendingStore.clear(userId);
            } else {
                pendingStore.put(userId, before);
            }
            log.warn("제안 미리보기 전송 실패(pending 이전 상태 복원): user={}", userId, publishFailure);
            return "미리보기 전송에 실패했다 — 제안은 반영되지 않았다. 사용자에게 잠시 후 다시 요청해 달라고 안내해라.";
        }
    }

    // 이번 시음 엔트리 — draft.entries는 1건 전제(확인 미리보기와 동일 가정). 마지막 엔트리를 취한다.
    private static Entry latestEntry(Note draft) {
        List<Entry> entries = draft.entries();
        return entries.get(entries.size() - 1);
    }
}
