package com.devwuu.mocha.agent.tool;

import com.devwuu.mocha.agent.tool.validation.RecordProposalValidator;
import com.devwuu.mocha.agent.tool.validation.ToolValidation;
import com.devwuu.mocha.agent.turn.TurnDraft;
import com.devwuu.mocha.agent.turn.TurnProposalSink;
import com.devwuu.mocha.agent.turn.TurnUserMessage;
import com.devwuu.mocha.domain.Entry;
import com.devwuu.mocha.domain.MatchInfo;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.Sourced;
import com.devwuu.mocha.service.NoteService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

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
 * <p>TΔ29a에서 <b>대화 수정이 돌아왔지만 tool은 늘지 않았다</b>(delta 0029 D-14): {@code match}에
 * {@code edit} 갈래가 늘 뿐이고, 자연어가 하는 일은 <i>"어느 기록인가"</i>를 지목하고 초안을 채우는 데까지다
 * — <i>"어떤 필드를 무슨 값으로"</i>는 여전히 폼이 소유한다. {@code propose_edit}을 되살렸다면 검증 진입점이
 * 다시 둘이 되어 백로그 R-3이 되살아난다.
 * <p>TΔ4에서 제안의 <b>효과</b>가 바뀌었다: 종전에는 서버의 pending 행을 쓰고 Slack 미리보기를 보냈으나,
 * 이제는 {@link TurnProposalSink}에 결과를 실어 <b>응답으로 돌려주는</b> 데까지다 — 작성 중 데이터는
 * 클라이언트(폼)가 소유하고 서버는 기억하지 않는다(OQ-1, delta 0029 D-2).
 * <p>POLICY: 이 tool은 어떤 상태도 쓰지 않는다 — 노트 쓰기는 사용자 확정([저장])만 하고, 제안은 값을
 * 돌려줄 뿐이다. 조회 tool·최종 텍스트도 무변화다 (ref: specs/coffee-note-agent/plan.md#ADR-45, AC-59).
 */
class ProposalTools {

    private static final Logger log = LoggerFactory.getLogger(ProposalTools.class);

    // 제안 tool 공통 인자 조각 — strict 계약(전 필드 required, additionalProperties=false)을 조각 단위로 지킨다.
    private static final String RATING_SCHEMA = """
            {"type":["string","null"],"enum":["완전 내스타일","맛있다","맛은 있는데 내스타일은 아님","맛이 없다",null],
             "description":"4범주 평가 — 미언급이면 null(V-1)"}""";
    private static final String REVIEW_SCHEMA = """
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
            {"type":"object","description":"회차 1개(한 번 내려 마신 단위) — recipe·review 중 최소 하나를 채운다(V-15)","properties":{
              "recipe":%s,
              "review":%s
            },"required":["recipe","review"],"additionalProperties":false}""".formatted(RECIPE_SCHEMA, REVIEW_SCHEMA);

    private static final String PROPOSE_RECORD_SCHEMA = """
            {"type":"object","properties":{
              "coffee_name":%s,
              "roastery":%s,
              "beans":{"type":"array","description":"원두 구성 — 요소=원두 1종. 정보 전무면 빈 배열(V-14)","items":%s},
              "roast_level":%s,
              "official_notes":%s,
              "brews":{"type":"array","description":"회차 배열 — 배열 순서 = 회차 번호. 시도를 나눠 말했으면 시도마다 요소(V-15). target_date 하루의 시도만 담는다 — 다른 날짜의 시도를 이 날짜의 회차로 합치지 않는다(다중 날짜 발화는 active_date 세그먼트의 내용만 — ADR-59·61)","items":%s},
              "target_date":{"type":"string","description":"시음일 YYYY-MM-DD — 상대 날짜(\\"어제\\")는 컨텍스트의 today 기준으로 절대화해 보낸다. 발화에 시음 날짜가 2개 이상 섞여 있으면 컨텍스트의 자동 분해 세그먼트 중 active_date(가장 이른 날짜)로만 제안한다 — 세그먼트 컨텍스트가 없으면(분해 실패) 이 tool을 호출하지 말고 한 날짜씩 나눠 보내달라고 안내한다(FR-15, ADR-61)"},
              "match":{"type":"object","description":"이 제안이 무엇인지 — new(새 커피) · existing(기존 노트를 또 마신 새 시음 = 회차 추가) · edit(이미 저장된 기록을 고침 = 그 날짜 엔트리 교체). existing과 edit은 인자 모양이 같지만 뜻이 반대다: \\"또 마셨다\\"면 existing, \\"그때 기록이 틀렸다\\"면 edit. 기존 노트 대조는 list_notes로 한다","properties":{
                "type":{"type":"string","enum":["new","existing","edit"]},
                "note_id":{"type":["integer","null"],"description":"existing·edit일 때 대상 노트 id — list_notes 응답의 id"},
                "date":{"type":["string","null"],"description":"existing·edit일 때 대상 날짜 YYYY-MM-DD. edit에서는 고칠 엔트리를 가리키는 키라 실제로 있는 날짜여야 한다(get_note로 확인) — 날짜를 바꾸려면 여기는 원래 날짜를 두고 target_date에 새 날짜를 넣어라"}
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

    private final NoteService noteService;
    private final RecordProposalValidator recordValidator;
    private final ObjectMapper mapper;
    private final Clock clock;

    ProposalTools(NoteService noteService, RecordProposalValidator recordValidator,
                  ObjectMapper mapper, Clock clock) {
        this.noteService = noteService;
        this.recordValidator = recordValidator;
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

    // 턴 원문(utterance)·draft는 클로저 캡처로만 유입된다 — 턴 시작 시점 고정 값이라 pending처럼 재조회하지
    // 않고, 갱신 재호출도 같은 턴 클로저를 써 턴 안에서 일관된다(TΔ2b, findings-TΔ0 §C-2·C-5).
    // draft는 컨텍스트 조립기가 모델에게 보여준 것과 같은 값이다 — 모델이 본 draft와 게이트가 대조하는
    // draft가 갈리면 거부 사유가 모델 입장에서 근거 없는 말이 된다(§C-5 드리프트 방지, 0029 TΔ2).
    ToolCallback proposeRecord(String userId, TurnUserMessage utterance, TurnDraft draft, TurnProposalSink sink) {
        return new ToolCallback(
                "propose_record",
                "시음 기록을 제안한다 — 신규(new) · 기존 노트에 더하는 새 시음(existing) · 이미 저장된 기록의 "
                        + "수정(edit)이고, 어느 것인지는 match가 말한다. 검증 통과 시 제안 내용이 사용자 "
                        + "화면의 작성 폼으로 돌아간다. 저장은 사용자의 [저장] 확정만 한다. 작성 중인 draft가 있으면 "
                        + "재호출은 그 draft 위에 이번 발화를 반영한 전체 내용이어야 한다 — 건드리지 않은 필드도 draft "
                        + "값을 그대로 다시 실어라. edit은 get_note로 읽은 그 날짜 엔트리의 회차를 전부 실어야 한다 "
                        + "— 저장이 회차 배열을 통째로 교체하므로 빠뜨린 회차는 사라진다. "
                        + "검증 거부는 사유를 돌려주니 정정해 재호출해라.",
                PROPOSE_RECORD_SCHEMA,
                argumentsJson -> executeProposeRecord(userId, utterance, draft, sink, argumentsJson));
    }

    private String executeProposeRecord(String userId, TurnUserMessage utterance,
                                        TurnDraft draft, TurnProposalSink sink, String argumentsJson) {
        ProposeRecordArgs args = mapper.readValue(argumentsJson, ProposeRecordArgs.class);
        // POLICY: 제안 tool의 서버 검증 실패는 오류 사유를 tool 결과로 반환 — 조용한 드롭·서버 대행 금지
        //         (ref: specs/coffee-note-agent/plan.md#ADR-45, AC-9).
        ToolValidation<RecordProposal> validation = recordValidator.validate(args, utterance, draft);
        if (validation instanceof ToolValidation.Rejected<RecordProposal>(String reason)) {
            log.info("propose_record 검증 거부: user={} reason={}", userId, reason);
            return ToolSupport.errorOutput(mapper, reason);
        }
        RecordProposal proposal = ((ToolValidation.Ok<RecordProposal>) validation).value();

        // 환각 필터(get_note 미존재 오류와 같은 정신): existing·edit의 대상 노트가 실존해야 커밋이 유령
        // id로 새 노트를 만들지 않는다. 여기까지 온 note_id는 검증이 Long으로 정규화한 값이다.
        MatchInfo match = proposal.match();
        Note target = null;
        if (match.noteId() != null) {
            Optional<Note> found = noteService.findById(match.noteId());
            if (found.isEmpty()) {
                log.info("propose_record 검증 거부: user={} reason=미존재 match.note_id {}", userId, match.noteId());
                return ToolSupport.errorOutput(mapper, ToolSupport.missingNoteReason(match.noteId()));
            }
            target = found.get();
        }
        if (match.type() == MatchInfo.MatchType.EDIT) {
            String rejection = editTargetRejection(target, match, proposal);
            if (rejection != null) {
                log.info("propose_record 검증 거부: user={} reason={}", userId, rejection);
                return ToolSupport.errorOutput(mapper, rejection);
            }
        }

        OffsetDateTime now = OffsetDateTime.now(clock);
        Entry entry = new Entry(proposal.targetDate(), proposal.brews(), now);
        // POLICY: 제안 노트의 식별자는 기존 노트면 그 id, 신규면 null이다 — id는 INSERT가 발급하므로 저장 전
        //         draft가 식별자를 가질 방법이 없고, 이 null이 그대로 커밋의 신규/기존 분기가 된다
        //         (ref: changes/0028 D-1, ADR-75 — 구 recordSlug 대체키 발급은 근거와 함께 소멸했다).
        // 이름이 proposedNote인 이유: 이 메서드의 draft는 턴 입력(TurnDraft)이 이미 쓰고 있다(0029 TΔ2).
        // existing·edit은 둘 다 대상 노트의 id를 그대로 딛는다 — 갈리는 것은 저장 경로이지 식별자가 아니다.
        Note proposedNote = new Note(
                match.noteId(),
                proposal.meta().coffeeName(), proposal.meta().roastery(), proposal.meta().beans(),
                proposal.meta().roastLevel(), proposal.meta().officialNotes(),
                proposal.meta().sources(), List.of(entry), now, now);

        // POLICY: 제안의 효과는 결과를 수거함에 싣는 것까지다 — 서버 상태(노트·pending)를 쓰지 않는다.
        //         저장(커밋)은 사용자 확정만 하고, 작성 중 데이터는 클라이언트 폼이 소유한다
        //         (ref: plan.md#ADR-45·#ADR-3, changes/0029 tasks TΔ4, delta 0029 D-2).
        // 실을 값이 곧 다음 턴의 draft 형태인 이유는 TurnProposalSink javadoc에 있다. "같은 대상인가"는
        // 판정하지 않는다 — 단일 대기 게이트가 TΔ1에서 사라졌고, 폼 내용은 다음 제안이 그대로 대체한다.
        sink.accept(new TurnDraft(proposedNote, proposal.match()));

        // 0029 TΔ3: 제안 성공 접힘(구 ADR-46 규칙 ①) 폐기 — 제안 tool은 트랜스크립트를 건드리지 않는다.
        // 접힘은 커밋·TTL만 남았고, 문맥 축적은 라우터의 턴 완결 지점이 단독으로 소유한다.
        log.info("propose_record 수용: user={} noteId={} match={} draftBased={}",
                userId, proposedNote.id(), proposal.match().type(), draft != null);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("proposed", true);
        // 신규는 아직 id가 없다(D-1) — 없는 식별자를 null로 실어 보내지 않는다. 기존 노트 대상일 때만 싣는다.
        if (proposedNote.id() != null) {
            result.put("note_id", proposedNote.id());
        }
        result.put("target_date", proposal.targetDate().toString());
        // 새로 만든 제안인지 작성 중이던 폼을 고친 것인지 — 모델의 최종 안내 문구가 갈리는 지점이다.
        result.put("updated_draft", draft != null);
        return mapper.writeValueAsString(result);
    }

    /**
     * {@code match=edit}의 대상 검사 — 통과면 null, 아니면 <b>루프 안에서 정정 가능한 사유</b>
     * (ref: changes/0029 tasks.md TΔ29a, delta.md#D-14).
     *
     * <p><b>왜 검증기가 아니라 여기인가</b>: 저장된 노트를 읽어야 답이 나오는 검사라
     * {@link RecordProposalValidator}(순수 도메인 계층)에 둘 수 없다. 같은 이유로 존재하던 환각 필터
     * 바로 옆에 둬 <b>진입점을 늘리지 않는다</b>(백로그 R-3이 닫아 둔 자리).
     */
    // POLICY: V-9(커피명 불변)의 최종 방어선 — 폼이 커피명을 잠그지만(TΔ28a) 요청은 조작 가능하다.
    //         저장된 값과 대조하는 것은 NoteTxService.updateMeta가 쓰기 직전에 하는 것과 같은 검사이고,
    //         여기서 막으면 애초에 폼이 다른 커피명을 달고 서지 않는다
    //         (ref: specs/coffee-note-agent/data-model.md#V-9, changes/0029 tasks.md TΔ29a).
    private static String editTargetRejection(Note target, MatchInfo match, RecordProposal proposal) {
        // 대상 엔트리가 실존해야 한다 — 없는 날짜를 고치겠다는 제안은 PATCH 단계에서 404로 죽고, 그때는
        // 사용자가 [저장]을 누른 뒤라 정정할 자리가 없다. 루프 안에서 되돌린다.
        boolean hasEntry = target.entries().stream().anyMatch(entry -> match.date().equals(entry.date()));
        if (!hasEntry) {
            return "노트 " + match.noteId() + "에 " + match.date() + " 기록이 없다 — get_note로 실제 엔트리 "
                    + "날짜를 확인하고 고칠 날짜를 다시 지목해라. 새로 마신 것을 더하는 것이라면 "
                    + "match.type=existing이다.";
        }
        String stored = Sourced.valueOrNull(target.coffeeName());
        String proposed = Sourced.valueOrNull(proposal.meta().coffeeName());
        if (!Objects.equals(stored, proposed)) {
            return "coffee_name은 노트 생성 후 불변이다(V-9) — 노트 " + match.noteId() + "는 '" + stored
                    + "'인데 제안이 '" + proposed + "'로 바꿨다. 오타 정정도 예외가 아니다. 이름을 그대로 두고 "
                    + "다시 제안하고, 다른 커피의 기록이라면 match.type=new로 새 노트를 만들어라.";
        }
        return null;
    }
}
