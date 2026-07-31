package com.devwuu.mocha.agent.tool;

import com.devwuu.mocha.domain.Source;
import com.devwuu.mocha.agent.conversation.FoldingChatMemory;
import com.devwuu.mocha.agent.conversation.TranscriptTurn;
import com.devwuu.mocha.agent.tool.validation.RecordProposalValidator;
import com.devwuu.mocha.agent.turn.TurnUserMessage;
import com.devwuu.mocha.domain.Aliases;
import com.devwuu.mocha.domain.Bean;
import com.devwuu.mocha.domain.Brew;
import com.devwuu.mocha.domain.Entry;
import com.devwuu.mocha.domain.MatchInfo;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.NoteMeta;
import com.devwuu.mocha.domain.PendingNote;
import com.devwuu.mocha.domain.Rating;
import com.devwuu.mocha.domain.Sourced;
import com.devwuu.mocha.domain.Tasting;
import com.devwuu.mocha.json.MochaObjectMapper;
import com.devwuu.mocha.repository.NoteRepository;
import com.devwuu.mocha.repository.PendingStore;
import com.devwuu.mocha.slack.outbound.PreviewBlocks;
import com.devwuu.mocha.slack.outbound.PreviewMessenger;
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
 * 제안 1종(propose_record — pending 생성·갱신(FR-5)·미리보기 전송·트랜스크립트 접힘)을 결정론으로
 * 단언한다 (data-model §3, plan ADR-45·46, AC-Δ4·Δ5·Δ6).
 * 외부 호출 없음 — 협력자는 전부 fake(모듈 CLAUDE.md §5.2).
 * <p>changes/0029 TΔ1에서 {@code propose_edit}·{@code send_entry_card} 케이스군과 단일 대기 거부
 * 케이스가 대상 구조와 함께 사라졌다 — 수정·조회는 UI로 옮겨진다(delta 0029 D-1·D-2·D-5).
 */
class ToolCallbackProviderTest {

    private static final String USER = "U-dev";
    private static final String CHANNEL = "C-mocha";
    // TΔ2b 배선: 원문은 아직 판정에 쓰이지 않는다 — 기존 단언 전부 원문 실린 조립으로 검증(동작 불변).
    private static final TurnUserMessage UTTERANCE = new TurnUserMessage("7월 16일 새콤하고 좋았음", null);

    @TempDir
    Path artifactDir;

    private final ObjectMapper mapper = MochaObjectMapper.create();
    private final StubNoteRepository noteRepository = new StubNoteRepository();
    private final FakePendingStore pendingStore = new FakePendingStore();
    private final MutableClock clock = new MutableClock();
    private CapturingPreviewMessenger previewMessenger;
    private FoldingChatMemory transcript;
    private ToolCallbackProvider toolCallbackProvider;

    @BeforeEach
    void setUp() {
        previewMessenger = new CapturingPreviewMessenger();
        transcript = new FoldingChatMemory(20, Duration.ofHours(1), clock);
        // R-7 명시 예외: 실 협력자 조립 자체가 검증 대상이라 픽스처(ToolCallbackProviderFixture)를 경유하지
        // 않는다 — 위치 인자 배선이 어긋나면 픽스처 경유 테스트는 함께 눈이 멀기 때문에 원 생성자를 그대로
        // 통과시키는 지점을 하나 남긴다(TΔ4b, changes/0025).
        toolCallbackProvider = new ToolCallbackProvider(noteRepository, mapper, pendingStore,
                previewMessenger, new RecordProposalValidator(clock), transcript, clock);
    }

    @Test
    @DisplayName("plan §3/ADR-44: forTurn은 function tool 3종을 strict 스키마(additionalProperties=false)로 장착한다")
    void forTurnExposesThreeStrictTools() {
        List<ToolCallback> tools = toolCallbackProvider.forTurn(USER, CHANNEL, UTTERANCE, null);

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
    @DisplayName("AC-Δ4/AC-2: propose_record 검증 통과 — pending(mode=record) 생성 + 미리보기 전송 + preview_ts 영속 + 접힘")
    void proposeRecordCreatesPendingAndPublishesPreview() {
        transcript.append(USER, new TranscriptTurn("어제 마신 예가체프 새콤했어", "기록할게요 멍"));

        JsonNode result = mapper.readTree(execute("propose_record",
                recordArgs("커피베라 예가체프 G1", "커피베라", "\"맛있다\"", "2026-07-16", "{\"type\":\"new\",\"note_id\":null,\"date\":null}")));

        // pending: draft·매칭·preview_ts가 모두 영속됐다 — 커밋은 여기서 일어나지 않는다(ADR-45, AC-Δ4).
        PendingNote pending = pendingStore.get(USER).orElseThrow();
        assertThat(pending.mode()).isEqualTo(PendingNote.Mode.RECORD);
        assertThat(pending.draft().coffeeName().value()).isEqualTo("커피베라 예가체프 G1");
        assertThat(pending.draft().id()).isNull(); // 신규는 아직 저장 전 — id는 INSERT가 발급한다(D-1)
        assertThat(pending.draft().entries()).hasSize(1);
        assertThat(pending.draft().entries().get(0).date()).isEqualTo(LocalDate.of(2026, 7, 16));
        assertThat(pending.draft().entries().get(0).brews().getFirst().tasting().rating()).isEqualTo(Rating.GOOD);
        assertThat(pending.match().type()).isEqualTo(MatchInfo.MatchType.NEW);
        assertThat(pending.previewTs()).isEqualTo(previewMessenger.ts);
        // 미리보기 1회 전송 + 성공 결과 + 제안 성공 접힘(ADR-46 규칙 ①, AC-Δ6).
        assertThat(previewMessenger.published).hasSize(1);
        assertThat(previewMessenger.channels).containsExactly(CHANNEL);
        assertThat(result.get("proposed").asBoolean()).isTrue();
        // 신규 제안 결과에는 식별자가 실리지 않는다 — 없는 값을 null로 실어 보내지 않는다(D-1).
        assertThat(result.has("note_id")).isFalse();
        assertThat(transcript.view(USER)).isEmpty();
    }

    @Test
    @DisplayName("FR-5/AC-5: 확인 대기 중 같은 커피 재호출 = 갱신 경로 — preview_ts·created_at 보존, 같은 미리보기를 edit")
    void proposeRecordRecallUpdatesExistingPending() {
        execute("propose_record",
                recordArgs("커피베라 예가체프 G1", "커피베라", "null", "2026-07-16", "{\"type\":\"new\",\"note_id\":null,\"date\":null}"));
        OffsetDateTime firstCreatedAt = pendingStore.get(USER).orElseThrow().createdAt();
        clock.advanceMinutes(10);

        JsonNode result = mapper.readTree(execute("propose_record",
                recordArgs("커피베라 예가체프 G1", "커피베라", "\"완전 내스타일\"", "2026-07-16", "{\"type\":\"new\",\"note_id\":null,\"date\":null}")));

        PendingNote updated = pendingStore.get(USER).orElseThrow();
        assertThat(updated.draft().entries().get(0).brews().getFirst().tasting().rating()).isEqualTo(Rating.PERFECT); // 수정 반영
        assertThat(updated.draft().id()).isNull();                                       // 저장 전 — 여전히 식별자 없음
        assertThat(updated.createdAt()).isEqualTo(firstCreatedAt);                       // TTL 기준 보존
        // 두 번째 발행은 preview_ts를 문 채로 — 재전송이 아니라 기존 미리보기 메시지 edit(data-model §2.3).
        assertThat(previewMessenger.published).hasSize(2);
        assertThat(previewMessenger.published.get(1).previewTs()).isEqualTo(previewMessenger.ts);
        assertThat(result.get("updated_existing_pending").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("0029 D-2: 단일 대기 게이트 폐기 — 대기 중 다른 커피의 제안도 거부되지 않고 대기 내용을 대체한다")
    void proposeRecordReplacesPendingWithDifferentCoffee() {
        // 구 AC-30(다른 커피 거부)의 자리다. 게이트의 동일성 키에 수정 대상 필드(roastery)가 섞여 있어
        // 로스터리 정정이 논리적으로 불가능했고(delta 0029 §1.2), 그 구조를 통째로 걷어낸 결과가 이것이다.
        execute("propose_record",
                recordArgs("커피베라 예가체프 G1", "커피베라", "null", "2026-07-16", "{\"type\":\"new\",\"note_id\":null,\"date\":null}"));

        JsonNode result = mapper.readTree(execute("propose_record",
                recordArgs("Ethiopia Chelbesa", "FroB", "null", "2026-07-16", "{\"type\":\"new\",\"note_id\":null,\"date\":null}")));

        assertThat(result.has("error")).isFalse();
        assertThat(pendingStore.get(USER).orElseThrow().draft().coffeeName().value())
                .isEqualTo("Ethiopia Chelbesa");           // 새 제안이 대기를 대체
        assertThat(previewMessenger.published).hasSize(2); // 같은 미리보기를 edit로 갱신
        assertThat(previewMessenger.published.get(1).previewTs()).isEqualTo(previewMessenger.ts);
    }

    @Test
    @DisplayName("V-1/AC-9: rating 4범주 위반은 사유와 함께 거부 — pending 미생성")
    void proposeRecordRejectsInvalidRating() {
        JsonNode result = mapper.readTree(execute("propose_record",
                recordArgs("커피베라 예가체프 G1", "커피베라", "\"다섯 개 만점\"", "2026-07-16", "{\"type\":\"new\",\"note_id\":null,\"date\":null}")));

        assertThat(result.get("error").asString()).contains("4범주");
        assertThat(pendingStore.get(USER)).isEmpty();
        assertThat(previewMessenger.published).isEmpty();
    }

    @Test
    @DisplayName("propose_record: match=existing의 유령 note_id는 거부 — 환각 필터(커밋이 유령 노트로 흐르지 않게)")
    void proposeRecordRejectsUnknownExistingMatch() {
        JsonNode result = mapper.readTree(execute("propose_record",
                recordArgs("Ethiopia Chelbesa", "FroB", "null", "2026-07-16",
                        "{\"type\":\"existing\",\"note_id\":999,\"date\":\"2026-07-16\"}")));

        assertThat(result.get("error").asString()).contains("999", "list_notes");
        assertThat(pendingStore.get(USER)).isEmpty();
        assertThat(previewMessenger.published).isEmpty();
    }

    @Test
    @DisplayName("ADR-48 정신: 미리보기 전송 실패 시 신규 pending을 남기지 않고 오류 사유 반환 — 접힘 없음")
    void proposeRecordClearsPendingWhenPreviewPublishFails() {
        transcript.append(USER, new TranscriptTurn("어제 마신 예가체프", "기록할게요 멍"));
        previewMessenger.fail = true;

        JsonNode result = mapper.readTree(execute("propose_record",
                recordArgs("커피베라 예가체프 G1", "커피베라", "null", "2026-07-16", "{\"type\":\"new\",\"note_id\":null,\"date\":null}")));

        assertThat(result.get("error").asString()).contains("미리보기");
        assertThat(pendingStore.get(USER)).isEmpty(); // "미리보기 없으면 pending 없음" 불변
        assertThat(transcript.view(USER)).hasSize(1);
    }

    // ---- 읽기·카드 tool (TΔ5) ----

    @Test
    @DisplayName("data-model §3.1/FR-14·20: list_notes 페이로드 — 메타 필드 + 통합 별칭 + 최근 시음일(snake_case)")
    void listNotesPayloadCarriesMetaAliasesAndLastTasted() {
        noteRepository.put(note(12L, "Ethiopia Chelbesa", "FroB",
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
        noteRepository.put(note(12L, "Ethiopia Chelbesa", "FroB",
                Aliases.empty(), LocalDate.of(2026, 7, 13)));

        JsonNode result = mapper.readTree(execute("get_note", "{\"note_id\":12}"));

        assertThat(result.get("id").asInt()).isEqualTo(12);
        assertThat(result.get("coffee_name").get("value").asString()).isEqualTo("Ethiopia Chelbesa");
        assertThat(result.get("entries")).hasSize(1);
        assertThat(result.get("entries").get(0).get("date").asString()).isEqualTo("2026-07-13");
        assertThat(result.get("entries").get(0).get("brews").get(0).get("tasting").get("my_taste").asString()).isEqualTo("새콤하고 좋았음");
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
        return tool(toolName).executor().execute(argumentsJson);
    }

    private ToolCallback tool(String toolName) {
        return toolCallbackProvider.forTurn(USER, CHANNEL, UTTERANCE, null).stream()
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
                  "brews": [%s],
                  "target_date": "%s",
                  "match": %s,
                  "sources": []
                }
                """.formatted(coffeeName, roastery, tastingBrewJson("새콤하고 좋았음", "새콤하고 좋았다", ratingJson),
                targetDate, matchJson);
    }

    /** 감상만 담은 회차 1개 JSON 조각 — brews 배열 요소(data-model §3.3, changes/0021). */
    private static String tastingBrewJson(String myTaste, String original, String ratingJson) {
        return """
                {"recipe": null, "tasting": {"my_taste": "%s", "my_taste_original": "%s", "rating": %s}}"""
                .formatted(myTaste, original, ratingJson);
    }

    private static List<String> required(JsonNode schema) {
        return schema.get("required").values().stream().map(JsonNode::asString).toList();
    }

    private static Note note(Long id, String coffeeName, String roastery, Aliases aliases,
                             LocalDate... entryDates) {
        List<Entry> entries = new ArrayList<>();
        for (LocalDate date : entryDates) {
            entries.add(new Entry(date,
                    List.of(new Brew(null, new Tasting("새콤하고 좋았음", null, Rating.GOOD))),
                    OffsetDateTime.parse("2026-07-14T10:00:00+09:00")));
        }
        return new Note(id, new Sourced<>(coffeeName, Source.USER), new Sourced<>(roastery, Source.USER),
                List.of(new Bean(new Sourced<>("에티오피아", Source.SEARCH), null)),
                null, new Sourced<>(List.of("자스민"), Source.SEARCH),
                aliases, List.of(), entries,
                OffsetDateTime.parse("2026-07-13T10:20:30+09:00"),
                OffsetDateTime.parse("2026-07-14T10:00:00+09:00"));
    }

    // ---- fakes (모듈 CLAUDE.md §5.2 — 외부 의존은 인터페이스 stub/fake) ----

    private static final class StubNoteRepository implements NoteRepository {
        private final Map<Long, Note> notes = new LinkedHashMap<>();

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
        public Note upsertEntry(Long noteId, NoteMeta meta, Entry entry, Aliases aliases) {
            throw new UnsupportedOperationException("제안 tool은 노트를 쓰지 않는다 — 커밋은 [저장] 버튼만(AC-Δ4)");
        }

        @Override
        public Note applyEdit(long noteId, LocalDate targetDate, Note draft) {
            throw new UnsupportedOperationException("제안 tool은 노트를 쓰지 않는다 — 커밋은 [저장] 버튼만(AC-Δ4)");
        }

        @Override
        public void delete(long id) {
            throw new UnsupportedOperationException("제안 tool은 노트를 쓰지 않는다 — 삭제는 A2 범위다");
        }
    }

    private static final class FakePendingStore implements PendingStore {
        private final Map<String, PendingNote> pendings = new LinkedHashMap<>();

        @Override
        public void put(String userId, PendingNote pending) {
            pendings.put(userId, pending);
        }

        @Override
        public Optional<PendingNote> get(String userId) {
            return Optional.ofNullable(pendings.get(userId));
        }

        @Override
        public void clear(String userId) {
            pendings.remove(userId);
        }
    }

    /** 발행된 pending을 캡처하고 preview_ts를 돌려주는 미리보기 어댑터 스텁(Slack 미접촉). */
    private static final class CapturingPreviewMessenger extends PreviewMessenger {
        final List<PendingNote> published = new ArrayList<>();
        final List<String> channels = new ArrayList<>();
        final String ts = "1720000000.000123";
        boolean fail = false;

        CapturingPreviewMessenger() {
            super(new PreviewBlocks(), null);
        }

        @Override
        public String publish(String channelId, PendingNote pending) {
            if (fail) {
                throw new IllegalStateException("전송 실패");
            }
            published.add(pending);
            channels.add(channelId);
            return ts;
        }
    }

    /** 진행을 제어할 수 있는 Asia/Seoul 시계 — created_at 보존(FR-5) 단언에 쓴다. */
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
