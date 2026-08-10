package com.devwuu.mocha.agent.tool;

import com.devwuu.mocha.domain.Source;
import com.devwuu.mocha.agent.conversation.FoldingChatMemory;
import com.devwuu.mocha.agent.conversation.TranscriptTurn;
import com.devwuu.mocha.agent.tool.validation.RecordProposalValidator;
import com.devwuu.mocha.agent.turn.TurnDraft;
import com.devwuu.mocha.agent.turn.TurnProposalSink;
import com.devwuu.mocha.agent.turn.TurnUserMessage;
import com.devwuu.mocha.domain.Aliases;
import com.devwuu.mocha.domain.Bean;
import com.devwuu.mocha.domain.Cup;
import com.devwuu.mocha.domain.TastingDay;
import com.devwuu.mocha.domain.MatchInfo;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.NoteDetail;
import com.devwuu.mocha.domain.NoteMeta;
import com.devwuu.mocha.domain.Rating;
import com.devwuu.mocha.domain.Sourced;
import com.devwuu.mocha.domain.Review;
import com.devwuu.mocha.json.MochaObjectMapper;
import com.devwuu.mocha.service.NoteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TΔ5·TΔ6(changes/0018): function tool 3종 — 읽기 2종(list_notes 페이로드, get_note 미존재 오류)과
 * 제안 1종(propose_record — 제안 결과 반환·거부 경로)을 결정론으로 단언한다
 * (data-model §3, plan ADR-45·46, AC-Δ4·Δ5·Δ6).
 * 외부 호출 없음 — 협력자는 전부 fake(모듈 CLAUDE.md §5.2).
 * <p>changes/0029 TΔ1에서 {@code propose_edit}·{@code send_entry_card} 케이스군과 단일 대기 거부
 * 케이스가 대상 구조와 함께 사라졌다 — 수정·조회는 UI로 옮겨진다(delta 0029 D-1·D-2·D-5).
 * <p>TΔ4에서 <b>제안의 효과를 보는 창</b>이 바뀌었다: pending 저장소·미리보기 전송 단언이 사라지고
 * {@link TurnProposalSink}에 무엇이 실렸는지를 본다. 서버 상태가 없으니 "무엇이 저장됐나"가 아니라
 * "무엇을 돌려줬나"가 검증 대상이다(delta 0029 D-2).
 */
class ToolCallbackProviderTest {

    private static final String USER = "U-dev";
    // TΔ2b 배선: 원문은 아직 판정에 쓰이지 않는다 — 기존 단언 전부 원문 실린 조립으로 검증(동작 불변).
    private static final TurnUserMessage UTTERANCE = new TurnUserMessage("7월 16일 새콤하고 좋았음", null);

    @TempDir
    Path artifactDir;

    private final ObjectMapper mapper = MochaObjectMapper.create();
    private final StubNoteService noteService = new StubNoteService();
    private final MutableClock clock = new MutableClock();
    private TurnProposalSink proposals;
    private FoldingChatMemory transcript;
    private ToolCallbackProvider toolCallbackProvider;

    @BeforeEach
    void setUp() {
        proposals = new TurnProposalSink();
        transcript = new FoldingChatMemory(20, Duration.ofHours(1), clock);
        // R-7 명시 예외: 실 협력자 조립 자체가 검증 대상이라 픽스처(ToolCallbackProviderFixture)를 경유하지
        // 않는다 — 위치 인자 배선이 어긋나면 픽스처 경유 테스트는 함께 눈이 멀기 때문에 원 생성자를 그대로
        // 통과시키는 지점을 하나 남긴다(TΔ4b, changes/0025).
        toolCallbackProvider = new ToolCallbackProvider(noteService, mapper,
                new RecordProposalValidator(clock), clock);
    }

    @Test
    @DisplayName("plan §3/ADR-44: forTurn은 function tool 3종을 strict 스키마(additionalProperties=false)로 장착한다")
    void forTurnExposesThreeStrictTools() {
        List<ToolCallback> tools = toolCallbackProvider.forTurn(USER, UTTERANCE, null, proposals);

        // 0029 TΔ1: propose_edit(수정은 UI 전용)·send_entry_card(갤러리 대체)가 폐기돼 5종 → 3종이다.
        assertThat(tools).extracting(ToolCallback::name)
                .containsExactly("list_notes", "get_note", "propose_record");
        for (ToolCallback tool : tools) {
            JsonNode schema = mapper.readTree(tool.parametersSchema());
            assertThat(schema.get("additionalProperties").asBoolean()).isFalse();
            // strict 계약: 선언한 전 필드가 required에 있다 (findings-TΔ0 §SDK).
            List<String> properties = new ArrayList<>(schema.get("properties").propertyNames());
            assertThat(required(schema)).containsExactlyInAnyOrderElementsOf(properties);
        }
    }

    // ---- propose_record (TΔ6) ----

    @Test
    @DisplayName("0029 TΔ4/AC-Δ4: propose_record 검증 통과 — 제안 결과를 수거함에 싣는다(서버 상태 미변경)")
    void proposeRecordDeliversProposalToSink() {
        transcript.append(USER, new TranscriptTurn("어제 마신 예가체프 새콤했어", "기록할게요 멍"));

        JsonNode result = mapper.readTree(execute("propose_record",
                recordArgs("커피베라 예가체프 G1", "커피베라", "\"맛있다\"", "2026-07-16", "{\"type\":\"new\",\"note_id\":null,\"date\":null}")));

        // 제안 내용·매칭이 수거함에 실렸다 — 커밋은 여기서 일어나지 않고(ADR-45), 저장소에도 쓰지 않는다.
        TurnDraft proposed = proposals.proposal().orElseThrow();
        assertThat(proposed.note().coffeeName().value()).isEqualTo("커피베라 예가체프 G1");
        assertThat(proposed.note().id()).isNull(); // 신규는 아직 저장 전 — id는 INSERT가 발급한다(D-1)
        assertThat(proposed.note().tastingDays()).hasSize(1);
        assertThat(proposed.note().tastingDays().get(0).date()).isEqualTo(LocalDate.of(2026, 7, 16));
        assertThat(proposed.note().tastingDays().get(0).cups().getFirst().review().rating()).isEqualTo(Rating.GOOD);
        assertThat(proposed.match().type()).isEqualTo(MatchInfo.MatchType.NEW);
        assertThat(result.get("proposed").asBoolean()).isTrue();
        // 신규 제안 결과에는 식별자가 실리지 않는다 — 없는 값을 null로 실어 보내지 않는다(D-1).
        assertThat(result.has("note_id")).isFalse();
        // draft 없이 시작한 턴이므로 "폼을 고친 것"이 아니다 — 모델의 안내 문구가 갈리는 지점(TΔ4).
        assertThat(result.get("updated_draft").asBoolean()).isFalse();
        // 0029 TΔ3: 제안 성공 접힘이 폐기돼 tool은 트랜스크립트를 건드리지 않는다.
        assertThat(transcript.view(USER)).hasSize(1);   // 제안 전 문맥이 그대로 살아 있다(구 규칙 ① 폐기)
    }

    @Test
    @DisplayName("0029 TΔ2·TΔ4: draft 위 재제안 — updated_draft=true, 수거함은 마지막 제안으로 대체된다")
    void proposeRecordOnDraftReportsUpdateAndReplacesSink() {
        execute("propose_record",
                recordArgs("커피베라 예가체프 G1", "커피베라", "null", "2026-07-16", "{\"type\":\"new\",\"note_id\":null,\"date\":null}"));
        // 앞 턴의 제안 결과가 곧 다음 턴의 draft다 — 클라이언트가 폼으로 받아 되돌려 보내는 값과 같은 형태.
        TurnDraft draft = proposals.proposal().orElseThrow();
        clock.advanceMinutes(10);

        JsonNode result = mapper.readTree(execute("propose_record", draft,
                recordArgs("커피베라 예가체프 G1", "커피베라", "\"완전 내스타일\"", "2026-07-16", "{\"type\":\"new\",\"note_id\":null,\"date\":null}")));

        TurnDraft updated = proposals.proposal().orElseThrow();
        assertThat(updated.note().tastingDays().get(0).cups().getFirst().review().rating()).isEqualTo(Rating.PERFECT); // 수정 반영
        assertThat(updated.note().id()).isNull();                     // 저장 전 — 여전히 식별자 없음
        assertThat(result.get("updated_draft").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("0029 D-2: 단일 대기 게이트 폐기 — 작성 중 다른 커피의 제안도 거부되지 않고 수거함을 대체한다")
    void proposeRecordReplacesProposalWithDifferentCoffee() {
        // 구 AC-30(다른 커피 거부)의 자리다. 게이트의 동일성 키에 수정 대상 필드(roastery)가 섞여 있어
        // 로스터리 정정이 논리적으로 불가능했고(delta 0029 §1.2), 그 구조를 통째로 걷어낸 결과가 이것이다.
        execute("propose_record",
                recordArgs("커피베라 예가체프 G1", "커피베라", "null", "2026-07-16", "{\"type\":\"new\",\"note_id\":null,\"date\":null}"));

        JsonNode result = mapper.readTree(execute("propose_record",
                recordArgs("Ethiopia Chelbesa", "FroB", "null", "2026-07-16", "{\"type\":\"new\",\"note_id\":null,\"date\":null}")));

        assertThat(result.has("error")).isFalse();
        assertThat(proposals.proposal().orElseThrow().note().coffeeName().value())
                .isEqualTo("Ethiopia Chelbesa");           // 새 제안이 앞의 것을 대체
    }

    @Test
    @DisplayName("V-1/AC-9: rating 4범주 위반은 사유와 함께 거부 — 제안 결과 미생성")
    void proposeRecordRejectsInvalidRating() {
        JsonNode result = mapper.readTree(execute("propose_record",
                recordArgs("커피베라 예가체프 G1", "커피베라", "\"다섯 개 만점\"", "2026-07-16", "{\"type\":\"new\",\"note_id\":null,\"date\":null}")));

        assertThat(result.get("error").asString()).contains("4범주");
        assertThat(proposals.proposal()).isEmpty();
    }

    @Test
    @DisplayName("propose_record: match=existing의 유령 note_id는 거부 — 환각 필터(커밋이 유령 노트로 흐르지 않게)")
    void proposeRecordRejectsUnknownExistingMatch() {
        JsonNode result = mapper.readTree(execute("propose_record",
                recordArgs("Ethiopia Chelbesa", "FroB", "null", "2026-07-16",
                        "{\"type\":\"existing\",\"note_id\":999,\"date\":\"2026-07-16\"}")));

        assertThat(result.get("error").asString()).contains("999", "list_notes");
        assertThat(proposals.proposal()).isEmpty();
    }

    // ---- match=edit (TΔ29a, delta 0029 D-14) ----

    @Test
    @DisplayName("TΔ29a: match=edit 통과 — 대상 노트·엔트리가 실존하면 수정 모드 draft가 수거함에 실린다(D-14)")
    void proposeRecordAcceptsEditMatch() {
        noteService.put(note(12L, "Ethiopia Chelbesa", "FroB", Aliases.empty(), LocalDate.of(2026, 7, 13)));

        JsonNode result = mapper.readTree(execute("propose_record",
                recordArgs("Ethiopia Chelbesa", "FroB", "\"맛은 있는데 내스타일은 아님\"", "2026-07-13",
                        "{\"type\":\"edit\",\"note_id\":12,\"date\":\"2026-07-13\"}")));

        assertThat(result.has("error")).isFalse();
        TurnDraft proposed = proposals.proposal().orElseThrow();
        // 모드는 응답이 정하고 폼은 따른다 — 클라이언트가 "이건 수정인가"를 추론하지 않는다(TΔ28a).
        assertThat(proposed.match()).isEqualTo(MatchInfo.edit(12L, LocalDate.of(2026, 7, 13)));
        // 대상 노트의 id를 딛는다 — existing과 갈리는 것은 저장 경로이지 식별자가 아니다.
        assertThat(proposed.note().id()).isEqualTo(12L);
        assertThat(result.get("note_id").asLong()).isEqualTo(12L);
    }

    @Test
    @DisplayName("TΔ29a: match=edit의 유령 note_id는 거부 — existing과 같은 환각 필터를 지난다")
    void proposeRecordRejectsUnknownEditNote() {
        JsonNode result = mapper.readTree(execute("propose_record",
                recordArgs("Ethiopia Chelbesa", "FroB", "null", "2026-07-13",
                        "{\"type\":\"edit\",\"note_id\":999,\"date\":\"2026-07-13\"}")));

        assertThat(result.get("error").asString()).contains("999", "list_notes");
        assertThat(proposals.proposal()).isEmpty();
    }

    @Test
    @DisplayName("TΔ29a: 노트는 있어도 그 날짜 엔트리가 없으면 거부 — 없는 기록을 고치는 폼이 서지 않는다")
    void proposeRecordRejectsEditOnMissingTastingDay() {
        noteService.put(note(12L, "Ethiopia Chelbesa", "FroB", Aliases.empty(), LocalDate.of(2026, 7, 13)));

        JsonNode result = mapper.readTree(execute("propose_record",
                recordArgs("Ethiopia Chelbesa", "FroB", "null", "2026-07-20",
                        "{\"type\":\"edit\",\"note_id\":12,\"date\":\"2026-07-20\"}")));

        // 여기서 막지 않으면 실패가 [저장] 이후의 404로 미뤄진다 — 그때는 루프 안에서 정정할 자리가 없다.
        assertThat(result.get("error").asString()).contains("2026-07-20", "get_note", "existing");
        assertThat(proposals.proposal()).isEmpty();
    }

    @Test
    @DisplayName("V-9/AC-14: 수정 대상의 커피명을 바꾼 제안은 거부 — 폼의 잠금 뒤에 선 서버 최종 방어(TΔ29a)")
    void proposeRecordRejectsEditRenamingCoffee() {
        noteService.put(note(12L, "Ethiopia Chelbesa", "FroB", Aliases.empty(), LocalDate.of(2026, 7, 13)));

        JsonNode result = mapper.readTree(execute("propose_record",
                recordArgs("Ethiopia Chelbesaa", "FroB", "null", "2026-07-13",
                        "{\"type\":\"edit\",\"note_id\":12,\"date\":\"2026-07-13\"}")));

        // 폼이 커피명을 잠그지만(TΔ28a) 요청은 조작 가능하다 — 오타 정정도 예외가 아니다(V-9).
        assertThat(result.get("error").asString()).contains("V-9", "Ethiopia Chelbesa");
        assertThat(proposals.proposal()).isEmpty();
    }

    // ---- 읽기·카드 tool (TΔ5) ----

    @Test
    @DisplayName("data-model §3.1/FR-14·20: list_notes 페이로드 — 메타 필드 + 통합 별칭 + 최근 시음일(snake_case)")
    void listNotesPayloadCarriesMetaAliasesAndLastTasted() {
        noteService.put(note(12L, "Ethiopia Chelbesa", "FroB",
                new Aliases(List.of("에티오피아 첼베사"), List.of("프롭")),
                LocalDate.of(2026, 7, 13), LocalDate.of(2026, 7, 14)));

        JsonNode result = mapper.readTree(execute("list_notes", "{}"));

        assertThat(result.get("notes")).hasSize(1);
        JsonNode summary = result.get("notes").get(0);
        assertThat(summary.get("id").asInt()).isEqualTo(12);
        assertThat(summary.get("coffee_name").asString()).isEqualTo("Ethiopia Chelbesa");
        assertThat(summary.get("roastery").asString()).isEqualTo("FroB");
        // 별칭은 커피명·로스터리 통합 목록 — 표기 비일관 흡수 재료(plan ADR-37, AC-53).
        assertThat(summary.get("aliases").values()).extracting(JsonNode::asString)
                .containsExactly("에티오피아 첼베사", "프롭");
        assertThat(summary.get("origin").asString()).isEqualTo("에티오피아");
        assertThat(summary.get("official_notes").values()).extracting(JsonNode::asString)
                .containsExactly("자스민");
        assertThat(summary.get("last_tasted").asString()).isEqualTo("2026-07-14");
    }

    @Test
    @DisplayName("data-model §3.2: get_note는 노트 전체(엔트리 포함)를 돌려준다")
    void getNoteReturnsWholeNote() {
        noteService.put(note(12L, "Ethiopia Chelbesa", "FroB",
                Aliases.empty(), LocalDate.of(2026, 7, 13)));

        JsonNode result = mapper.readTree(execute("get_note", "{\"note_id\":12}"));

        assertThat(result.get("id").asInt()).isEqualTo(12);
        assertThat(result.get("coffee_name").get("value").asString()).isEqualTo("Ethiopia Chelbesa");
        assertThat(result.get("tasting_days")).hasSize(1);
        assertThat(result.get("tasting_days").get(0).get("date").asString()).isEqualTo("2026-07-13");
        assertThat(result.get("tasting_days").get(0).get("cups").get(0).get("review").get("my_taste").asString()).isEqualTo("새콤하고 좋았음");
    }

    @Test
    @DisplayName("data-model §3.2: 미존재 note_id·비숫자 note_id는 오류 사유 반환 — 환각 필터")
    void getNoteRejectsUnknownNoteId() {
        JsonNode unknownId = mapper.readTree(execute("get_note", "{\"note_id\":999}"));
        // E-6: 스키마 위반(비숫자)도 미존재 id와 같은 부류로 수렴한다.
        JsonNode notAnId = mapper.readTree(execute("get_note", "{\"note_id\":\"ghost-note\"}"));

        assertThat(unknownId.get("error").asString()).contains("999", "list_notes");
        assertThat(notAnId.get("error").asString()).contains("ghost-note", "list_notes");
    }

    // ---- 헬퍼 ----

    private String execute(String toolName, String argumentsJson) {
        return execute(toolName, null, argumentsJson);
    }

    /** draft를 실은 턴 — 추가 발화(폼이 떠 있는 상태)의 재제안 경로다(0029 TΔ2). */
    private String execute(String toolName, TurnDraft draft, String argumentsJson) {
        return tool(toolName, draft).executor().execute(argumentsJson);
    }

    private ToolCallback tool(String toolName, TurnDraft draft) {
        return toolCallbackProvider.forTurn(USER, UTTERANCE, draft, proposals).stream()
                .filter(tool -> tool.name().equals(toolName))
                .findFirst().orElseThrow();
    }

    /** propose_record 인자 JSON — rating·match는 JSON 조각으로 받아 위반 값·매칭 변형을 그대로 실험한다. */
    private static String recordArgs(String coffeeName, String roastery, String ratingJson,
                                     String targetDate, String matchJson) {
        return """
                {
                  "coffee_name": {"value": "%s", "source": "user"},
                  "roastery": {"value": "%s", "source": "user"},
                  "beans": [], "roast_level": null, "official_notes": null,
                  "cups": [%s],
                  "target_date": "%s",
                  "match": %s,
                  "sources": []
                }
                """.formatted(coffeeName, roastery, reviewCupJson("새콤하고 좋았음", "새콤하고 좋았다", ratingJson),
                targetDate, matchJson);
    }

    /** 감상만 담은 회차 1개 JSON 조각 — cups 배열 요소(data-model §3.3, changes/0021). */
    private static String reviewCupJson(String myTaste, String original, String ratingJson) {
        return """
                {"recipe": null, "review": {"my_taste": "%s", "my_taste_original": "%s", "rating": %s}}"""
                .formatted(myTaste, original, ratingJson);
    }

    private static List<String> required(JsonNode schema) {
        return schema.get("required").values().stream().map(JsonNode::asString).toList();
    }

    private static Note note(Long id, String coffeeName, String roastery, Aliases aliases,
                             LocalDate... tastingDayDates) {
        List<TastingDay> tastingDays = new ArrayList<>();
        for (LocalDate date : tastingDayDates) {
            tastingDays.add(new TastingDay(date,
                    List.of(new Cup(null, new Review("새콤하고 좋았음", null, Rating.GOOD))),
                    OffsetDateTime.parse("2026-07-14T10:00:00+09:00")));
        }
        return new Note(id, new Sourced<>(coffeeName, Source.USER), new Sourced<>(roastery, Source.USER),
                List.of(new Bean(new Sourced<>("에티오피아", Source.SEARCH), null)),
                null, new Sourced<>(List.of("자스민"), Source.SEARCH),
                aliases, List.of(), tastingDays,
                OffsetDateTime.parse("2026-07-13T10:20:30+09:00"),
                OffsetDateTime.parse("2026-07-14T10:00:00+09:00"));
    }

    // ---- fakes (모듈 CLAUDE.md §5.2 — 외부 의존은 인터페이스 stub/fake) ----

    // 0029 TΔ4a: tool 계층이 저장소 포트 대신 NoteService를 잡게 되며 seam도 여기로 옮겼다. 쓰기 경로를
    // 오버라이드해 막는 것이 이 fake의 본체다 — "제안 tool은 아무것도 쓰지 않는다"(AC-Δ4)가 검증 대상이라,
    // 상속된 구현이 null 저장소로 NPE를 내는 것에 기대지 않고 의도를 사유로 남긴다.
    private static final class StubNoteService extends NoteService {
        private final Map<Long, Note> notes = new LinkedHashMap<>();

        StubNoteService() {
            super(null, null, null, null);
        }

        void put(Note note) {
            notes.put(note.id(), note);
        }

        @Override
        public List<Note> findAll() {
            return List.copyOf(notes.values());
        }

        @Override
        public Optional<Note> findById(long id) {
            return Optional.ofNullable(notes.get(id));
        }

        @Override
        public Note commit(Note draft, MatchInfo match) {
            throw new UnsupportedOperationException("제안 tool은 노트를 쓰지 않는다 — 커밋은 사용자 확정만(AC-Δ4)");
        }

        @Override
        public NoteDetail updateMeta(long noteId, NoteMeta meta) {
            throw new UnsupportedOperationException("제안 tool은 노트를 쓰지 않는다 — 수정은 UI 전용(D-1)");
        }

        @Override
        public NoteDetail replaceTastingDay(long noteId, LocalDate targetDate, TastingDay tastingDay) {
            throw new UnsupportedOperationException("제안 tool은 노트를 쓰지 않는다 — 수정은 UI 전용(D-1)");
        }

        @Override
        public boolean delete(long id) {
            throw new UnsupportedOperationException("제안 tool은 노트를 쓰지 않는다 — 삭제는 UI 전용이다");
        }
    }

    /** 진행을 제어할 수 있는 Asia/Seoul 시계 — 턴 사이 시간 경과가 제안 내용을 흔들지 않음을 본다. */
    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-07-17T01:20:30Z"); // Seoul 10:20:30

        @Override
        public ZoneId getZone() {
            return ZoneId.of("Asia/Seoul");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        void advanceMinutes(long minutes) {
            instant = instant.plus(Duration.ofMinutes(minutes));
        }
    }
}
