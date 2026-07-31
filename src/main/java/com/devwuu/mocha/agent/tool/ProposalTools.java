package com.devwuu.mocha.agent.tool;

import com.devwuu.mocha.agent.conversation.FoldingChatMemory;
import com.devwuu.mocha.agent.tool.validation.RecordProposalValidator;
import com.devwuu.mocha.agent.tool.validation.ToolValidation;
import com.devwuu.mocha.agent.turn.TurnUserMessage;
import com.devwuu.mocha.domain.Entry;
import com.devwuu.mocha.domain.MatchInfo;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.PendingNote;
import com.devwuu.mocha.repository.NoteRepository;
import com.devwuu.mocha.repository.PendingStore;
import com.devwuu.mocha.slack.outbound.PreviewMessenger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 쓰기 제안 축 tool — {@code propose_record}
 * (ref: specs/coffee-note-agent/data-model.md#3.3, plan#ADR-45; changes/0018 TΔ6, 0029 TΔ1).
 * <p>{@link ToolCallbackProvider}(façade)가 조립하는 내부 협력자라 Spring 빈이 아니다.
 * <p>strict schema(전 필드 required·additionalProperties=false)가 인자 <b>형태</b>를 보장하고, 값 수준
 * 규칙(V-1·5·8·11·14·15·16)은 검증 진입점 {@link RecordProposalValidator}가 결정론으로 강제한다.
 * 위반은 예외가 아니라 <b>사유를 담은 오류 결과</b>로 돌려줘 에이전트가 루프 안에서 정정한다
 * (ADR-45, findings-TΔ0 §SDK).
 * <p>changes/0029 TΔ1에서 {@code propose_edit}과 단일 대기 게이트가 함께 사라졌다 — 저장된 노트 수정은
 * UI 전용이 되고(delta 0029 D-1·D-2), 검증 진입점은 여기 하나만 남는다(백로그 R-3·R-4 해소).
 * <p>POLICY: 노트·pending 쓰기는 제안 tool {@code propose_record}와 버튼 커밋뿐 — 읽기 tool·
 * 최종 텍스트는 파일 무변화 (ref: specs/coffee-note-agent/plan.md#ADR-45, AC-59).
 */
class ProposalTools {

    private static final Logger log = LoggerFactory.getLogger(ProposalTools.class);

    // 제안 tool 공통 인자 조각 — strict 계약(전 필드 required, additionalProperties=false)을 조각 단위로 지킨다.
    private static final String RATING_SCHEMA = """
            {"type":["string","null"],"enum":["완전 내스타일","맛있다","맛은 있는데 내스타일은 아님","맛이 없다",null],
             "description":"4범주 평가 — 미언급이면 null(V-1)"}""";
    private static final String TASTING_SCHEMA = """
            {"type":["object","null"],"description":"그 회차의 맛 감상 — 감상 없는 시도면 null(V-15). 맛 감상·평가는 여기, 추출 관찰·진단·계획은 recipe.feedback에(ADR-59 분리 규칙)","properties":{
              "my_taste":{"type":"string","description":"내 느낌 — 한국어 음슴체 정규화본(ADR-30), 키워드화 금지"},
              "my_taste_original":{"type":["string","null"],"description":"말한 그대로의 감상 원문 — my_taste와 함께 채운다(V-11)"},
              "rating":%s
            },"required":["my_taste","my_taste_original","rating"],"additionalProperties":false}""".formatted(RATING_SCHEMA);
    private static final String RECIPE_SCHEMA = """
            {"type":["object","null"],"description":"그 시도의 추출 레시피 — 사용자 발화 전용(검색·OCR 보강 금지), 전무면 null(V-8)","properties":{
              "method":{"type":["string","null"],"description":"추출 방식(\\"핸드드립\\"·\\"에스프레소\\" 등) — 미언급이면 null"},
              "dose_g":{"type":["number","null"],"description":"원두량(g) — 양수"},
              "water_ml":{"type":["number","null"],"description":"부은 물량(ml) — 양수"},
              "yield_ml":{"type":["number","null"],"description":"추출량(ml) — 대략 발화(\\"10ml정도\\")는 숫자만(ADR-59)"},
              "time_sec":{"type":["number","null"],"description":"총 추출 시간(초) — \\"2분 40초\\"는 160. 과정 시간은 pouring에"},
              "temp_c":{"type":["number","null"],"description":"물/머신 온도(℃)"},
              "grind":{"type":["string","null"],"description":"분쇄도 — \\"<분쇄값> (<그라인더명>)\\" 형식, 그라인더 언급 없으면 값만(FR-18)"},
              "machine":{"type":["string","null"],"description":"머신·기구명"},
              "pouring":{"type":["string","null"],"description":"푸어링·과정 자유 텍스트 — 구조화하지 않는다"},
              "feedback":{"type":["string","null"],"description":"그 시도의 관찰·진단·다음 계획 — 한국어 음슴체"}
            },"required":["method","dose_g","water_ml","yield_ml","time_sec","temp_c","grind","machine","pouring","feedback"],"additionalProperties":false}""";
    private static final String BEAN_SCHEMA = """
            {"type":"object","description":"원두 1종 — 단일 원두도 요소 1개, 블렌드는 구성 원두마다 요소를 만들어 원두별 가공방식을 담는다(V-14)","properties":{
              "description":%s,
              "process":%s
            },"required":["description","process"],"additionalProperties":false}"""
            .formatted(
                    sourcedSchema("원산지·품종 등을 묶은 자유 텍스트(한국어 표기) — 품종은 알면 포함", true),
                    sourcedSchema("그 원두의 가공방식(한국어 관용 표기) — 모르면 null", true));
    private static final String BREW_SCHEMA = """
            {"type":"object","description":"회차 1개(한 번 내려 마신 단위) — recipe·tasting 중 최소 하나를 채운다(V-15)","properties":{
              "recipe":%s,
              "tasting":%s
            },"required":["recipe","tasting"],"additionalProperties":false}""".formatted(RECIPE_SCHEMA, TASTING_SCHEMA);

    private static final String PROPOSE_RECORD_SCHEMA = """
            {"type":"object","properties":{
              "coffee_name":%s,
              "roastery":%s,
              "beans":{"type":"array","description":"원두 구성 — 요소=원두 1종. 정보 전무면 빈 배열(V-14)","items":%s},
              "roast_level":%s,
              "official_notes":%s,
              "brews":{"type":"array","description":"회차 배열 — 배열 순서 = 회차 번호. 시도를 나눠 말했으면 시도마다 요소(V-15). target_date 하루의 시도만 담는다 — 다른 날짜의 시도를 이 날짜의 회차로 합치지 않는다(다중 날짜 발화는 active_date 세그먼트의 내용만 — ADR-59·61)","items":%s},
              "target_date":{"type":"string","description":"시음일 YYYY-MM-DD — 상대 날짜(\\"어제\\")는 컨텍스트의 today 기준으로 절대화해 보낸다. 발화에 시음 날짜가 2개 이상 섞여 있으면 컨텍스트의 자동 분해 세그먼트 중 active_date(가장 이른 날짜)로만 제안한다 — 세그먼트 컨텍스트가 없으면(분해 실패) 이 tool을 호출하지 말고 한 날짜씩 나눠 보내달라고 안내한다(FR-15, ADR-61)"},
              "match":{"type":"object","description":"신규/기존 판정 — 기존 노트의 시음 기록이면 existing(list_notes로 대조)","properties":{
                "type":{"type":"string","enum":["new","existing"]},
                "note_id":{"type":["integer","null"],"description":"existing일 때 대상 노트 id — list_notes 응답의 id"},
                "date":{"type":["string","null"],"description":"existing일 때 대상 날짜 YYYY-MM-DD"}
              },"required":["type","note_id","date"],"additionalProperties":false},
              "sources":{"type":"array","items":{"type":"string"},"description":"검색 참조 링크 — 동일성 가드를 통과한 출처만(AC-58)"}
            },"required":["coffee_name","roastery","beans","roast_level","official_notes","brews","target_date","match","sources"],"additionalProperties":false}"""
            .formatted(
                    sourcedSchema("커피 이름 — 기록의 정체성이자 검색 앵커(검색 보강 금지)", false),
                    sourcedSchema("로스터리", true),
                    BEAN_SCHEMA,
                    sourcedSchema("로스팅 정도", true),
                    sourcedNotesSchema("로스터리 전시 테이스팅 노트 — 로스터리 공식 출처 한정(FR-3)"),
                    BREW_SCHEMA);

    private final NoteRepository noteRepository;
    private final PendingStore pendingStore;
    private final PreviewMessenger previewMessenger;
    private final RecordProposalValidator recordValidator;
    private final FoldingChatMemory transcript;
    private final ObjectMapper mapper;
    private final Clock clock;

    ProposalTools(NoteRepository noteRepository, PendingStore pendingStore, PreviewMessenger previewMessenger,
                  RecordProposalValidator recordValidator, FoldingChatMemory transcript,
                  ObjectMapper mapper, Clock clock) {
        this.noteRepository = noteRepository;
        this.pendingStore = pendingStore;
        this.previewMessenger = previewMessenger;
        this.recordValidator = recordValidator;
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

    // 턴 원문(utterance)은 클로저 캡처로만 유입된다 — 턴 시작 시점 고정 값이라 pending처럼 재조회하지
    // 않고, 갱신 재호출도 같은 턴 클로저를 써 턴 안에서 일관된다(TΔ2b, findings-TΔ0 §C-2·C-5).
    ToolCallback proposeRecord(String userId, String channelId, TurnUserMessage utterance) {
        return new ToolCallback(
                "propose_record",
                "신규 시음 기록(또는 기존 노트에 더하는 새 시음)을 제안한다 — 검증 통과 시 확인 대기(pending)가 "
                        + "만들어지고 미리보기가 전송된다. 저장은 사용자의 [저장] 버튼만 한다. 확인 대기 중 재호출은 "
                        + "대기 내용 갱신이다(수정 발화 반영). 검증 거부는 사유를 돌려주니 정정해 재호출해라.",
                PROPOSE_RECORD_SCHEMA,
                argumentsJson -> executeProposeRecord(userId, channelId, utterance, argumentsJson));
    }

    private String executeProposeRecord(String userId, String channelId, TurnUserMessage utterance,
                                        String argumentsJson) {
        ProposeRecordArgs args = mapper.readValue(argumentsJson, ProposeRecordArgs.class);
        PendingNote pending = pendingStore.get(userId).orElse(null);
        // POLICY: 제안 tool의 서버 검증 실패는 오류 사유를 tool 결과로 반환 — 조용한 드롭·서버 대행 금지
        //         (ref: specs/coffee-note-agent/plan.md#ADR-45, AC-9).
        ToolValidation<RecordProposal> validation = recordValidator.validate(args, utterance);
        if (validation instanceof ToolValidation.Rejected<RecordProposal>(String reason)) {
            log.info("propose_record 검증 거부: user={} reason={}", userId, reason);
            return ToolSupport.errorOutput(mapper, reason);
        }
        RecordProposal proposal = ((ToolValidation.Ok<RecordProposal>) validation).value();

        // 환각 필터(get_note 미존재 오류와 같은 정신): match=existing의 대상 노트가 실존해야 커밋(upsertEntry)이
        // 유령 id로 새 노트를 만들지 않는다. 여기까지 온 note_id는 검증이 Long으로 정규화한 값이다.
        boolean existing = proposal.match().type() == MatchInfo.MatchType.EXISTING;
        if (existing && noteRepository.findById(proposal.match().noteId()).isEmpty()) {
            log.info("propose_record 검증 거부: user={} reason=미존재 match.note_id {}",
                    userId, proposal.match().noteId());
            return ToolSupport.errorOutput(mapper, ToolSupport.missingNoteReason(proposal.match().noteId()));
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        Entry entry = new Entry(proposal.targetDate(), proposal.brews(), now);
        // POLICY: draft의 식별자는 기존 노트면 그 id, 신규면 null이다 — id는 INSERT가 발급하므로 저장 전
        //         draft가 식별자를 가질 방법이 없고, 이 null이 그대로 커밋의 신규/기존 분기가 된다
        //         (ref: changes/0028 D-1, ADR-75 — 구 recordSlug 대체키 발급은 근거와 함께 소멸했다).
        Note draft = new Note(
                existing ? proposal.match().noteId() : null,
                proposal.meta().coffeeName(), proposal.meta().roastery(), proposal.meta().beans(),
                proposal.meta().roastLevel(), proposal.meta().officialNotes(),
                proposal.meta().sources(), List.of(entry), now, now);

        // FR-5 갱신 경로: 대기 중 재호출은 preview_ts·created_at을 보존해 같은 미리보기 메시지를 edit로
        // 갱신한다(재전송 아님, data-model §2.3). "같은 대상인가"는 더 이상 판정하지 않는다 — 단일 대기
        // 게이트가 0029 TΔ1에서 사라졌고, 대기 내용은 다음 제안이 그대로 대체한다(delta 0029 D-2).
        PendingNote next = pending == null
                ? new PendingNote(draft, proposal.match(), null, now)
                : new PendingNote(PendingNote.Mode.RECORD, draft, null, false, proposal.match(),
                        pending.previewTs(), pending.createdAt());

        String failure = persistAndPublish(userId, channelId, pending, next);
        if (failure != null) {
            return ToolSupport.errorOutput(mapper, failure);
        }
        // 제안 성공 = 트랜스크립트 접힘(ADR-46 규칙 ①) — 이후 문맥은 pending draft가 대신한다.
        transcript.clear(userId, FoldingChatMemory.FoldTrigger.PROPOSAL_ACCEPTED);
        log.info("propose_record 수용: user={} noteId={} match={} updated={}",
                userId, draft.id(), proposal.match().type(), pending != null);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("proposed", true);
        // 신규는 아직 id가 없다(D-1) — 없는 식별자를 null로 실어 보내지 않는다. 기존 노트 대상일 때만 싣는다.
        if (draft.id() != null) {
            result.put("note_id", draft.id());
        }
        result.put("target_date", proposal.targetDate().toString());
        result.put("updated_existing_pending", pending != null);
        return mapper.writeValueAsString(result);
    }

    // ---- pending 영속 + 미리보기 전송 ----

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
}
