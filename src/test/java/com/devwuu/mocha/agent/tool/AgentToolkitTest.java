package com.devwuu.mocha.agent.tool;

import com.devwuu.mocha.domain.Source;
import com.devwuu.mocha.agent.conversation.ConversationTranscript;
import com.devwuu.mocha.agent.conversation.TranscriptTurn;
import com.devwuu.mocha.agent.tool.validation.ProposalValidator;
import com.devwuu.mocha.agent.turn.TurnUtterance;
import com.devwuu.mocha.domain.Aliases;
import com.devwuu.mocha.domain.Bean;
import com.devwuu.mocha.domain.Brew;
import com.devwuu.mocha.domain.Entry;
import com.devwuu.mocha.domain.MatchInfo;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.NoteMeta;
import com.devwuu.mocha.domain.PendingNote;
import com.devwuu.mocha.domain.Rating;
import com.devwuu.mocha.domain.Recipe;
import com.devwuu.mocha.domain.Sourced;
import com.devwuu.mocha.domain.Tasting;
import com.devwuu.mocha.json.MochaObjectMapper;
import com.devwuu.mocha.render.NoteRenderer;
import com.devwuu.mocha.repository.NoteRepository;
import com.devwuu.mocha.repository.PendingStore;
import com.devwuu.mocha.slack.outbound.PreviewBlocks;
import com.devwuu.mocha.slack.outbound.PreviewMessenger;
import com.devwuu.mocha.slack.outbound.SlackResponder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TΔ5·TΔ6(changes/0018): function tool 5종 — 읽기·카드 3종(list_notes 페이로드, get_note 미존재 오류,
 * send_entry_card 재사용/증분 분기)과 제안 2종(propose_record/propose_edit — pending 생성·갱신(FR-5)·
 * 미리보기 전송·단일 대기 거부·충돌 경고·트랜스크립트 접힘)을 결정론으로 단언한다
 * (data-model §3, plan ADR-45·46, AC-Δ4·Δ5·Δ6). 외부 호출 없음 — 협력자는 전부 fake(모듈 CLAUDE.md §5.2).
 */
class AgentToolkitTest {

    private static final String USER = "U-dev";
    private static final String CHANNEL = "C-mocha";
    // TΔ2b 배선: 원문은 아직 판정에 쓰이지 않는다 — 기존 단언 전부 원문 실린 조립으로 검증(동작 불변).
    private static final TurnUtterance UTTERANCE = new TurnUtterance("7월 16일 새콤하고 좋았음", null);

    @TempDir
    Path artifactDir;

    private final ObjectMapper mapper = MochaObjectMapper.create();
    private final StubNoteRepository noteRepository = new StubNoteRepository();
    private final FakePendingStore pendingStore = new FakePendingStore();
    private final MutableClock clock = new MutableClock();
    private RecordingRenderer renderer;
    private RecordingResponder responder;
    private CapturingPreviewMessenger previewMessenger;
    private ConversationTranscript transcript;
    private AgentToolkit agentTools;

    @BeforeEach
    void setUp() {
        renderer = new RecordingRenderer(artifactDir);
        responder = new RecordingResponder();
        previewMessenger = new CapturingPreviewMessenger();
        transcript = new ConversationTranscript(20, Duration.ofHours(1), clock);
        agentTools = new AgentToolkit(noteRepository, renderer, responder, artifactDir, mapper,
                pendingStore, previewMessenger, new ProposalValidator(clock), transcript, clock);
    }

    @Test
    @DisplayName("plan §3/ADR-44: forTurn은 function tool 5종을 strict 스키마(additionalProperties=false)로 장착한다")
    void forTurnExposesFiveStrictTools() {
        List<AgentTool> tools = agentTools.forTurn(USER, CHANNEL, UTTERANCE);

        assertThat(tools).extracting(AgentTool::name)
                .containsExactly("list_notes", "get_note", "propose_record", "propose_edit", "send_entry_card");
        for (AgentTool tool : tools) {
            JsonNode schema = mapper.readTree(tool.parametersSchema());
            assertThat(schema.get("additionalProperties").asBoolean()).isFalse();
            // strict 계약: 선언한 전 필드가 required에 있다 (findings-TΔ0 §SDK).
            List<String> properties = new ArrayList<>(schema.get("properties").propertyNames());
            assertThat(required(schema)).containsExactlyInAnyOrderElementsOf(properties);
        }
    }

    @Test
    @DisplayName("V-9/AC-38: propose_edit patch 스키마에 coffee_name 필드 자체가 없다 — 구조 차단(strict 유지)")
    void proposeEditSchemaHasNoCoffeeName() {
        AgentTool proposeEdit = tool("propose_edit");
        JsonNode patch = mapper.readTree(proposeEdit.parametersSchema()).get("properties").get("patch");

        List<String> patchFields = new ArrayList<>(patch.get("properties").propertyNames());
        assertThat(patchFields).doesNotContain("coffee_name").contains("roastery", "new_date");
        // 중첩 객체도 strict 계약 유지 — patch의 전 필드가 required에 있다.
        assertThat(required(patch)).containsExactlyInAnyOrderElementsOf(patchFields);
        // 대조: propose_record에는 coffee_name이 있다 — 이름은 기록 생성 경로로만 들어온다.
        JsonNode recordSchema = mapper.readTree(tool("propose_record").parametersSchema());
        assertThat(new ArrayList<>(recordSchema.get("properties").propertyNames())).contains("coffee_name");
    }

    // ---- propose_record (TΔ6) ----

    @Test
    @DisplayName("AC-Δ4/AC-2: propose_record 검증 통과 — pending(mode=record) 생성 + 미리보기 전송 + preview_ts 영속 + 접힘")
    void proposeRecordCreatesPendingAndPublishesPreview() {
        transcript.append(USER, new TranscriptTurn("어제 마신 예가체프 새콤했어", "기록할게요 멍"));

        JsonNode result = mapper.readTree(execute("propose_record",
                recordArgs("커피베라 예가체프 G1", "커피베라", "\"맛있다\"", "2026-07-16", "{\"type\":\"new\",\"slug\":null,\"date\":null}")));

        // pending: draft·매칭·preview_ts가 모두 영속됐다 — 커밋은 여기서 일어나지 않는다(ADR-45, AC-Δ4).
        PendingNote pending = pendingStore.get(USER).orElseThrow();
        assertThat(pending.mode()).isEqualTo(PendingNote.Mode.RECORD);
        assertThat(pending.draft().coffeeName().value()).isEqualTo("커피베라 예가체프 G1");
        assertThat(pending.draft().slug()).isEqualTo("2026-07-16-102030"); // 시음일 + 생성 시각(V-2)
        assertThat(pending.draft().entries()).hasSize(1);
        assertThat(pending.draft().entries().get(0).date()).isEqualTo(LocalDate.of(2026, 7, 16));
        assertThat(pending.draft().entries().get(0).brews().getFirst().tasting().rating()).isEqualTo(Rating.GOOD);
        assertThat(pending.match().type()).isEqualTo(MatchInfo.MatchType.NEW);
        assertThat(pending.previewTs()).isEqualTo(previewMessenger.ts);
        // 미리보기 1회 전송 + 성공 결과 + 제안 성공 접힘(ADR-46 규칙 ①, AC-Δ6).
        assertThat(previewMessenger.published).hasSize(1);
        assertThat(previewMessenger.channels).containsExactly(CHANNEL);
        assertThat(result.get("proposed").asBoolean()).isTrue();
        assertThat(result.get("slug").asString()).isEqualTo("2026-07-16-102030");
        assertThat(transcript.view(USER)).isEmpty();
    }

    @Test
    @DisplayName("FR-5/AC-5: 확인 대기 중 같은 커피 재호출 = 갱신 경로 — slug·preview_ts·created_at 보존, 같은 미리보기를 edit")
    void proposeRecordRecallUpdatesExistingPending() {
        execute("propose_record",
                recordArgs("커피베라 예가체프 G1", "커피베라", "null", "2026-07-16", "{\"type\":\"new\",\"slug\":null,\"date\":null}"));
        OffsetDateTime firstCreatedAt = pendingStore.get(USER).orElseThrow().createdAt();
        clock.advanceMinutes(10);

        JsonNode result = mapper.readTree(execute("propose_record",
                recordArgs("커피베라 예가체프 G1", "커피베라", "\"완전 내스타일\"", "2026-07-16", "{\"type\":\"new\",\"slug\":null,\"date\":null}")));

        PendingNote updated = pendingStore.get(USER).orElseThrow();
        assertThat(updated.draft().entries().get(0).brews().getFirst().tasting().rating()).isEqualTo(Rating.PERFECT); // 수정 반영
        assertThat(updated.draft().slug()).isEqualTo("2026-07-16-102030");               // slug 불변
        assertThat(updated.createdAt()).isEqualTo(firstCreatedAt);                       // TTL 기준 보존
        // 두 번째 발행은 preview_ts를 문 채로 — 재전송이 아니라 기존 미리보기 메시지 edit(data-model §2.3).
        assertThat(previewMessenger.published).hasSize(2);
        assertThat(previewMessenger.published.get(1).previewTs()).isEqualTo(previewMessenger.ts);
        assertThat(result.get("updated_existing_pending").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("AC-30/AC-Δ5: 확인 대기 중 다른 커피의 새 기록 제안은 거부 — pending 무변화·미리보기 미전송·접힘 없음")
    void proposeRecordRejectsDifferentCoffeeWhilePending() {
        execute("propose_record",
                recordArgs("커피베라 예가체프 G1", "커피베라", "null", "2026-07-16", "{\"type\":\"new\",\"slug\":null,\"date\":null}"));
        transcript.append(USER, new TranscriptTurn("이번엔 첼베사", "네 멍"));

        JsonNode result = mapper.readTree(execute("propose_record",
                recordArgs("Ethiopia Chelbesa", "FroB", "null", "2026-07-16", "{\"type\":\"new\",\"slug\":null,\"date\":null}")));

        assertThat(result.get("error").asString()).contains("단일 대기");
        assertThat(pendingStore.get(USER).orElseThrow().draft().coffeeName().value())
                .isEqualTo("커피베라 예가체프 G1"); // 대기 불변
        assertThat(previewMessenger.published).hasSize(1); // 거부 턴엔 미전송
        assertThat(transcript.view(USER)).hasSize(1);      // 제안 실패 — 접힘 없음
    }

    @Test
    @DisplayName("V-1/AC-9: rating 4범주 위반은 사유와 함께 거부 — pending 미생성")
    void proposeRecordRejectsInvalidRating() {
        JsonNode result = mapper.readTree(execute("propose_record",
                recordArgs("커피베라 예가체프 G1", "커피베라", "\"다섯 개 만점\"", "2026-07-16", "{\"type\":\"new\",\"slug\":null,\"date\":null}")));

        assertThat(result.get("error").asString()).contains("4범주");
        assertThat(pendingStore.get(USER)).isEmpty();
        assertThat(previewMessenger.published).isEmpty();
    }

    @Test
    @DisplayName("propose_record: match=existing의 유령 slug는 거부 — 환각 필터(커밋이 유령 노트로 흐르지 않게)")
    void proposeRecordRejectsUnknownExistingMatch() {
        JsonNode result = mapper.readTree(execute("propose_record",
                recordArgs("Ethiopia Chelbesa", "FroB", "null", "2026-07-16",
                        "{\"type\":\"existing\",\"slug\":\"ghost-note\",\"date\":\"2026-07-16\"}")));

        assertThat(result.get("error").asString()).contains("ghost-note", "list_notes");
        assertThat(pendingStore.get(USER)).isEmpty();
        assertThat(previewMessenger.published).isEmpty();
    }

    @Test
    @DisplayName("ADR-48 정신: 미리보기 전송 실패 시 신규 pending을 남기지 않고 오류 사유 반환 — 접힘 없음")
    void proposeRecordClearsPendingWhenPreviewPublishFails() {
        transcript.append(USER, new TranscriptTurn("어제 마신 예가체프", "기록할게요 멍"));
        previewMessenger.fail = true;

        JsonNode result = mapper.readTree(execute("propose_record",
                recordArgs("커피베라 예가체프 G1", "커피베라", "null", "2026-07-16", "{\"type\":\"new\",\"slug\":null,\"date\":null}")));

        assertThat(result.get("error").asString()).contains("미리보기");
        assertThat(pendingStore.get(USER)).isEmpty(); // "미리보기 없으면 pending 없음" 불변
        assertThat(transcript.view(USER)).hasSize(1);
    }

    // ---- propose_edit (TΔ6) ----

    @Test
    @DisplayName("AC-Δ4/FR-21: propose_edit 검증 통과 — pending(mode=edit) 생성 + patch 적용 + ✏️ 미리보기 + 접힘")
    void proposeEditCreatesEditPendingAndPublishesPreview() {
        noteRepository.put(note("2026-07-13-102030", "Ethiopia Chelbesa", "FroB",
                Aliases.empty(), LocalDate.of(2026, 7, 13)));
        transcript.append(USER, new TranscriptTurn("첼베사 평가 바꿔줘", "네 멍"));

        JsonNode result = mapper.readTree(execute("propose_edit",
                editArgs("2026-07-13-102030", "2026-07-13",
                        brewsPatchJson("더 새콤했음", "더 새콤했다", "\"맛있다\""))));

        PendingNote pending = pendingStore.get(USER).orElseThrow();
        assertThat(pending.mode()).isEqualTo(PendingNote.Mode.EDIT);
        assertThat(pending.target()).isEqualTo(
                new PendingNote.EditTarget("2026-07-13-102030", LocalDate.of(2026, 7, 13)));
        assertThat(pending.draft().entries()).hasSize(1);
        // brews는 통째 교체(§3.4) — 에이전트가 rating까지 반영한 전체 배열을 구성해 보낸다(ADR-59).
        assertThat(pending.draft().entries().get(0).brews().getFirst().tasting().myTaste()).isEqualTo("더 새콤했음");
        assertThat(pending.draft().entries().get(0).brews().getFirst().tasting().rating()).isEqualTo(Rating.GOOD);
        assertThat(pending.dateConflict()).isFalse();
        assertThat(pending.previewTs()).isEqualTo(previewMessenger.ts);
        assertThat(previewMessenger.published).hasSize(1);
        assertThat(result.get("proposed").asBoolean()).isTrue();
        assertThat(transcript.view(USER)).isEmpty(); // 제안 성공 접힘(AC-Δ6)
    }

    @Test
    @DisplayName("data-model §3.4/ADR-59: patch의 brews는 통째 교체 — 회차 수가 다른 배열(회차 append 반영)로도 그대로 교체된다")
    void proposeEditReplacesBrewsWholesale() {
        noteRepository.put(note("2026-07-13-102030", "Ethiopia Chelbesa", "FroB",
                Aliases.empty(), LocalDate.of(2026, 7, 13)));

        // 회차를 하나 더 기록하는 수정 발화 — 에이전트가 기존 회차를 포함해 append한 전체 배열을 구성한다(V-15).
        execute("propose_edit", editArgs("2026-07-13-102030", "2026-07-13",
                "\"brews\": [%s, %s]".formatted(
                        tastingBrewJson("새콤하고 좋았음", "새콤하고 좋았다", "\"맛있다\""),
                        tastingBrewJson("식으니까 더 맛있음", "식으니까 더 맛있네", "\"완전 내스타일\""))));

        // 서버는 회차 단위 병합 없이 배열을 신뢰해 통째 교체한다 — 배열 순서 = 회차 번호 유지(ADR-59).
        Entry draftEntry = pendingStore.get(USER).orElseThrow().draft().entries().get(0);
        assertThat(draftEntry.brews()).hasSize(2);
        assertThat(draftEntry.brews())
                .extracting(brew -> brew.tasting().myTaste())
                .containsExactly("새콤하고 좋았음", "식으니까 더 맛있음");
        assertThat(draftEntry.brews().get(1).tasting().rating()).isEqualTo(Rating.PERFECT);
    }

    @Test
    @DisplayName("data-model §3.4: brews 없는 patch(null=유지)는 기존 회차를 건드리지 않는다")
    void proposeEditKeepsBrewsWhenPatchOmitsThem() {
        noteRepository.put(note("2026-07-13-102030", "Ethiopia Chelbesa", "FroB",
                Aliases.empty(), LocalDate.of(2026, 7, 13)));

        execute("propose_edit", editArgs("2026-07-13-102030", "2026-07-13",
                "\"roastery\": {\"value\": \"프롭\", \"source\": \"user\"}"));

        PendingNote pending = pendingStore.get(USER).orElseThrow();
        assertThat(pending.draft().roastery().value()).isEqualTo("프롭"); // 지정 필드만 반영
        Entry draftEntry = pending.draft().entries().get(0);
        assertThat(draftEntry.brews()).hasSize(1); // 회차 유지 — brews 부재는 교체 아님
        assertThat(draftEntry.brews().getFirst().tasting().myTaste()).isEqualTo("새콤하고 좋았음");
        assertThat(draftEntry.brews().getFirst().tasting().rating()).isEqualTo(Rating.GOOD);
    }

    @Test
    @DisplayName("V-10/AC-39: 날짜 이동처에 기존 엔트리가 있으면 date_conflict를 서버가 계산해 pending에 싣는다 — 경고 표기 근거")
    void proposeEditFlagsDateMoveConflict() {
        noteRepository.put(note("2026-07-13-102030", "Ethiopia Chelbesa", "FroB",
                Aliases.empty(), LocalDate.of(2026, 7, 10), LocalDate.of(2026, 7, 13)));

        JsonNode result = mapper.readTree(execute("propose_edit",
                editArgs("2026-07-13-102030", "2026-07-13", "\"new_date\":\"2026-07-10\"")));

        PendingNote pending = pendingStore.get(USER).orElseThrow();
        assertThat(pending.draft().entries().get(0).date()).isEqualTo(LocalDate.of(2026, 7, 10));
        assertThat(pending.dateConflict()).isTrue(); // PreviewBlocks가 이 플래그로 덮어쓰기 경고 섹션을 그린다
        assertThat(result.get("date_conflict").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("V-10: 이동처에 기존 엔트리가 없으면 date_conflict=false — 자유 날짜 이동은 경고 없이 제안된다")
    void proposeEditDateMoveWithoutConflict() {
        noteRepository.put(note("2026-07-13-102030", "Ethiopia Chelbesa", "FroB",
                Aliases.empty(), LocalDate.of(2026, 7, 13)));

        JsonNode result = mapper.readTree(execute("propose_edit",
                editArgs("2026-07-13-102030", "2026-07-13", "\"new_date\":\"2026-07-20\"")));

        PendingNote pending = pendingStore.get(USER).orElseThrow();
        assertThat(pending.draft().entries().get(0).date()).isEqualTo(LocalDate.of(2026, 7, 20));
        assertThat(pending.dateConflict()).isFalse();
        assertThat(result.get("date_conflict").asBoolean()).isFalse();
    }

    @Test
    @DisplayName("FR-5: 같은 대상 재호출 = 누적 반영 — 직전 변경 유지 + preview_ts·created_at 보존 + 이동 충돌 경고 유지")
    void proposeEditRecallAccumulatesOnPendingDraft() {
        noteRepository.put(note("2026-07-13-102030", "Ethiopia Chelbesa", "FroB",
                Aliases.empty(), LocalDate.of(2026, 7, 10), LocalDate.of(2026, 7, 13)));
        execute("propose_edit", editArgs("2026-07-13-102030", "2026-07-13",
                brewsPatchJson("더 새콤했음", "더 새콤했다", "null") + ",\"new_date\":\"2026-07-10\""));
        OffsetDateTime firstCreatedAt = pendingStore.get(USER).orElseThrow().createdAt();
        clock.advanceMinutes(10);

        // rating만 고치는 발화 — 에이전트는 직전 감상을 포함한 전체 brews 배열을 다시 구성한다(§3.4 통째 교체).
        execute("propose_edit", editArgs("2026-07-13-102030", "2026-07-13",
                brewsPatchJson("더 새콤했음", "더 새콤했다", "\"완전 내스타일\"")));

        PendingNote updated = pendingStore.get(USER).orElseThrow();
        assertThat(updated.draft().entries().get(0).brews().getFirst().tasting().myTaste()).isEqualTo("더 새콤했음"); // 직전 변경 유지
        assertThat(updated.draft().entries().get(0).brews().getFirst().tasting().rating()).isEqualTo(Rating.PERFECT); // 이번 변경 반영
        assertThat(updated.draft().entries().get(0).date()).isEqualTo(LocalDate.of(2026, 7, 10)); // 이동 유지
        assertThat(updated.dateConflict()).isTrue(); // new_date 없는 재호출에도 충돌 경고 유지(V-10)
        assertThat(updated.createdAt()).isEqualTo(firstCreatedAt);
        assertThat(previewMessenger.published).hasSize(2);
        assertThat(previewMessenger.published.get(1).previewTs()).isEqualTo(previewMessenger.ts);
    }

    @Test
    @DisplayName("propose_edit: 확인 대기 중 다른 대상의 수정 제안은 거부(단일 대기 준용) — 대기·미리보기 무변화")
    void proposeEditRejectsDifferentTargetWhilePending() {
        noteRepository.put(note("2026-07-13-102030", "Ethiopia Chelbesa", "FroB",
                Aliases.empty(), LocalDate.of(2026, 7, 10), LocalDate.of(2026, 7, 13)));
        execute("propose_edit", editArgs("2026-07-13-102030", "2026-07-13",
                brewsPatchJson("새콤했음", "새콤했다", "\"맛있다\"")));

        JsonNode result = mapper.readTree(execute("propose_edit",
                editArgs("2026-07-13-102030", "2026-07-10",
                        brewsPatchJson("밍밍했음", "밍밍했다", "\"맛이 없다\""))));

        assertThat(result.get("error").asString()).contains("단일 대기");
        assertThat(pendingStore.get(USER).orElseThrow().target().date()).isEqualTo(LocalDate.of(2026, 7, 13));
        assertThat(previewMessenger.published).hasSize(1);
    }

    @Test
    @DisplayName("data-model §3.4: 미존재 slug·미존재 엔트리 날짜의 수정 제안은 오류 사유 반환 — 환각 필터")
    void proposeEditRejectsUnknownTargets() {
        noteRepository.put(note("2026-07-13-102030", "Ethiopia Chelbesa", "FroB",
                Aliases.empty(), LocalDate.of(2026, 7, 13)));

        JsonNode unknownSlug = mapper.readTree(execute("propose_edit",
                editArgs("ghost-note", "2026-07-13", brewsPatchJson("새콤했음", "새콤했다", "\"맛있다\""))));
        JsonNode unknownDate = mapper.readTree(execute("propose_edit",
                editArgs("2026-07-13-102030", "2026-07-01", brewsPatchJson("새콤했음", "새콤했다", "\"맛있다\""))));

        assertThat(unknownSlug.get("error").asString()).contains("ghost-note", "list_notes");
        assertThat(unknownDate.get("error").asString()).contains("2026-07-01", "get_note");
        assertThat(pendingStore.get(USER)).isEmpty();
        assertThat(previewMessenger.published).isEmpty();
    }

    // ---- 읽기·카드 tool (TΔ5) ----

    @Test
    @DisplayName("data-model §3.1/FR-14·20: list_notes 페이로드 — 메타 필드 + 통합 별칭 + 최근 시음일(snake_case)")
    void listNotesPayloadCarriesMetaAliasesAndLastTasted() {
        noteRepository.put(note("2026-07-13-102030", "Ethiopia Chelbesa", "FroB",
                new Aliases(List.of("에티오피아 첼베사"), List.of("프롭")),
                LocalDate.of(2026, 7, 13), LocalDate.of(2026, 7, 14)));

        JsonNode result = mapper.readTree(execute("list_notes", "{}"));

        assertThat(result.get("notes")).hasSize(1);
        JsonNode summary = result.get("notes").get(0);
        assertThat(summary.get("slug").asString()).isEqualTo("2026-07-13-102030");
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
        noteRepository.put(note("2026-07-13-102030", "Ethiopia Chelbesa", "FroB",
                Aliases.empty(), LocalDate.of(2026, 7, 13)));

        JsonNode result = mapper.readTree(execute("get_note", "{\"slug\":\"2026-07-13-102030\"}"));

        assertThat(result.get("slug").asString()).isEqualTo("2026-07-13-102030");
        assertThat(result.get("coffee_name").get("value").asString()).isEqualTo("Ethiopia Chelbesa");
        assertThat(result.get("entries")).hasSize(1);
        assertThat(result.get("entries").get(0).get("date").asString()).isEqualTo("2026-07-13");
        assertThat(result.get("entries").get(0).get("brews").get(0).get("tasting").get("my_taste").asString()).isEqualTo("새콤하고 좋았음");
    }

    @Test
    @DisplayName("data-model §3.2: 미존재 slug는 오류 사유 반환 — 환각 필터")
    void getNoteRejectsUnknownSlug() {
        JsonNode result = mapper.readTree(execute("get_note", "{\"slug\":\"ghost-note\"}"));

        assertThat(result.get("error").asString()).contains("ghost-note", "list_notes");
    }

    @Test
    @DisplayName("data-model §3.5/AC-31: 그 엔트리의 회차 카드가 전부 있으면 그대로 postImage — 새 렌더 없음(파생물 재사용)")
    void sendEntryCardReusesExistingCard() throws IOException {
        noteRepository.put(note("2026-07-13-102030", "Ethiopia Chelbesa", "FroB",
                Aliases.empty(), LocalDate.of(2026, 7, 13)));
        // 픽스처 엔트리 = 감상 회차 1개 → 기대 카드 집합 = <date>-taste-1.jpg 1장(changes/0021 TΔ5a).
        Path existingCard = artifactDir.resolve("cards/2026-07-13-102030/2026-07-13-taste-1.jpg");
        Files.createDirectories(existingCard.getParent());
        Files.write(existingCard, new byte[]{1});

        JsonNode result = mapper.readTree(execute("send_entry_card",
                "{\"slug\":\"2026-07-13-102030\",\"date\":\"2026-07-13\"}"));

        assertThat(renderer.rendered).isEmpty();
        assertThat(responder.postedImages).hasSize(1);
        assertThat(responder.postedImages.get(0).path()).isEqualTo(existingCard);
        assertThat(responder.postedImages.get(0).channelId()).isEqualTo(CHANNEL);
        assertThat(result.get("sent").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("data-model §3.5: 카드 파일 부재 시에만 그 엔트리만 증분 렌더 후 전송")
    void sendEntryCardRendersIncrementallyWhenCardMissing() {
        noteRepository.put(note("2026-07-13-102030", "Ethiopia Chelbesa", "FroB",
                Aliases.empty(), LocalDate.of(2026, 7, 13)));

        JsonNode result = mapper.readTree(execute("send_entry_card",
                "{\"slug\":\"2026-07-13-102030\",\"date\":\"2026-07-13\"}"));

        assertThat(renderer.rendered).containsExactly("2026-07-13-102030/2026-07-13");
        assertThat(responder.postedImages).hasSize(1);
        assertThat(responder.postedImages.get(0).path())
                .isEqualTo(artifactDir.resolve("cards/2026-07-13-102030/2026-07-13-taste-1.jpg"));
        assertThat(result.get("sent").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("send_entry_card: 미존재 slug·미존재 엔트리 날짜는 오류 사유 반환 — 전송·렌더 없음(환각 필터)")
    void sendEntryCardRejectsUnknownTargets() {
        noteRepository.put(note("2026-07-13-102030", "Ethiopia Chelbesa", "FroB",
                Aliases.empty(), LocalDate.of(2026, 7, 13)));

        JsonNode unknownSlug = mapper.readTree(execute("send_entry_card",
                "{\"slug\":\"ghost-note\",\"date\":\"2026-07-13\"}"));
        JsonNode unknownDate = mapper.readTree(execute("send_entry_card",
                "{\"slug\":\"2026-07-13-102030\",\"date\":\"2026-07-01\"}"));
        JsonNode badDate = mapper.readTree(execute("send_entry_card",
                "{\"slug\":\"2026-07-13-102030\",\"date\":\"엊그제\"}"));

        assertThat(unknownSlug.get("error").asString()).contains("ghost-note");
        assertThat(unknownDate.get("error").asString()).contains("2026-07-01", "get_note");
        assertThat(badDate.get("error").asString()).contains("YYYY-MM-DD");
        assertThat(renderer.rendered).isEmpty();
        assertThat(responder.postedImages).isEmpty();
    }

    @Test
    @DisplayName("FR-20/AC-Δ6: 회차 2개 엔트리는 회차 카드 4장 전부를 순서대로 재전송한다 — 캡션은 첫 카드에만(TΔ5b)")
    void sendEntryCardSendsAllBrewCards() throws IOException {
        noteRepository.put(twoBrewNote("2026-07-18-102030", LocalDate.of(2026, 7, 18)));
        List<Path> existing = seedCards("2026-07-18-102030",
                "2026-07-18-taste-1.jpg", "2026-07-18-recipe-1.jpg",
                "2026-07-18-taste-2.jpg", "2026-07-18-recipe-2.jpg");

        JsonNode result = mapper.readTree(execute("send_entry_card",
                "{\"slug\":\"2026-07-18-102030\",\"date\":\"2026-07-18\"}"));

        assertThat(renderer.rendered).isEmpty(); // 전부 존재 → 재사용(§3.5)
        assertThat(responder.postedImages).extracting(PostedImage::path)
                .containsExactlyElementsOf(existing); // 회차 오름차순, 회차 안에서 감상 → 레시피
        assertThat(responder.postedImages.get(0).caption()).isEqualTo(NoteLookupTools.CARD_CAPTION);
        assertThat(responder.postedImages).filteredOn(img -> img.caption() != null).hasSize(1);
        assertThat(result.get("sent").asBoolean()).isTrue();
        assertThat(result.get("cards_sent").asInt()).isEqualTo(4);
        assertThat(result.has("cards_failed")).isFalse();
    }

    @Test
    @DisplayName("plan §7: 일부 카드 전송 실패 → 성공분은 배달하고 실패분을 결과에 명시한다(부분 폴백)")
    void sendEntryCardReportsPartialFailure() throws IOException {
        noteRepository.put(twoBrewNote("2026-07-18-102030", LocalDate.of(2026, 7, 18)));
        seedCards("2026-07-18-102030",
                "2026-07-18-taste-1.jpg", "2026-07-18-recipe-1.jpg",
                "2026-07-18-taste-2.jpg", "2026-07-18-recipe-2.jpg");
        responder.failFilenames.add("2026-07-18-recipe-1.jpg");

        JsonNode result = mapper.readTree(execute("send_entry_card",
                "{\"slug\":\"2026-07-18-102030\",\"date\":\"2026-07-18\"}"));

        assertThat(responder.postedImages).hasSize(3);
        assertThat(result.get("sent").asBoolean()).isTrue();
        assertThat(result.get("cards_sent").asInt()).isEqualTo(3);
        assertThat(result.get("cards_failed")).hasSize(1);
        assertThat(result.get("cards_failed").get(0).asString()).isEqualTo("2026-07-18-recipe-1.jpg");
    }

    @Test
    @DisplayName("plan §7: 카드 전송 전부 실패 → 오류 사유 반환(에이전트가 미도착을 안내할 근거)")
    void sendEntryCardReturnsErrorWhenAllSendsFail() throws IOException {
        noteRepository.put(note("2026-07-13-102030", "Ethiopia Chelbesa", "FroB",
                Aliases.empty(), LocalDate.of(2026, 7, 13)));
        seedCards("2026-07-13-102030", "2026-07-13-taste-1.jpg");
        responder.failFilenames.add("2026-07-13-taste-1.jpg");

        JsonNode result = mapper.readTree(execute("send_entry_card",
                "{\"slug\":\"2026-07-13-102030\",\"date\":\"2026-07-13\"}"));

        assertThat(responder.postedImages).isEmpty();
        assertThat(result.get("error").asString()).contains("카드");
    }

    // ---- 헬퍼 ----

    private String execute(String toolName, String argumentsJson) {
        return tool(toolName).executor().execute(argumentsJson);
    }

    private AgentTool tool(String toolName) {
        return agentTools.forTurn(USER, CHANNEL, UTTERANCE).stream()
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

    /** propose_edit 인자 JSON — patch에 바꿀 필드 조각만 싣는다(strict 전 필드 전송은 SDK 몫, null=유지 계약 검증). */
    private static String editArgs(String slug, String date, String patchFieldsJson) {
        return """
                {"slug": "%s", "date": "%s", "patch": {%s}}
                """.formatted(slug, date, patchFieldsJson);
    }

    /** 감상만 담은 회차 1개 JSON 조각 — brews 배열 요소(data-model §3.3, changes/0021). */
    private static String tastingBrewJson(String myTaste, String original, String ratingJson) {
        return """
                {"recipe": null, "tasting": {"my_taste": "%s", "my_taste_original": "%s", "rating": %s}}"""
                .formatted(myTaste, original, ratingJson);
    }

    /** patch의 brews 통째 교체 조각 — 특정 필드만 고치는 발화도 에이전트가 전체 배열을 구성한다(§3.4). */
    private static String brewsPatchJson(String myTaste, String original, String ratingJson) {
        return "\"brews\": [%s]".formatted(tastingBrewJson(myTaste, original, ratingJson));
    }

    private static List<String> required(JsonNode schema) {
        return schema.get("required").values().stream().map(JsonNode::asString).toList();
    }

    private static Note note(String slug, String coffeeName, String roastery, Aliases aliases,
                             LocalDate... entryDates) {
        List<Entry> entries = new ArrayList<>();
        for (LocalDate date : entryDates) {
            entries.add(new Entry(date,
                    List.of(new Brew(null, new Tasting("새콤하고 좋았음", null, Rating.GOOD))),
                    OffsetDateTime.parse("2026-07-14T10:00:00+09:00")));
        }
        return new Note(slug, Sourced.user(coffeeName), Sourced.user(roastery),
                List.of(new Bean(new Sourced<>("에티오피아", Source.SEARCH), null)),
                null, new Sourced<>(List.of("자스민"), Source.SEARCH),
                aliases, List.of(), entries,
                OffsetDateTime.parse("2026-07-13T10:20:30+09:00"),
                OffsetDateTime.parse("2026-07-14T10:00:00+09:00"));
    }

    /** 회차 2개(각각 recipe+tasting) 엔트리 1건 노트 — 기대 카드 4장(taste·recipe × 회차 2)의 픽스처(AC-Δ6). */
    private static Note twoBrewNote(String slug, LocalDate date) {
        Entry entry = new Entry(date, List.of(
                new Brew(new Recipe(null, 15.0, 250.0, null, null, null, "210클릭 (매버릭 2.0)", null, null, null), new Tasting("새콤하고 좋았음", null, Rating.GOOD)),
                new Brew(new Recipe(null, 15.0, 250.0, null, null, null, "205클릭 (매버릭 2.0)", null, null, null), new Tasting("더 새콤했음", null, Rating.PERFECT))),
                OffsetDateTime.parse("2026-07-18T10:00:00+09:00"));
        return new Note(slug, Sourced.user("Ethiopia Chelbesa"), Sourced.user("FroB"),
                List.of(new Bean(new Sourced<>("에티오피아", Source.SEARCH), null)),
                null, new Sourced<>(List.of("자스민"), Source.SEARCH),
                Aliases.empty(), List.of(), List.of(entry),
                OffsetDateTime.parse("2026-07-18T10:00:00+09:00"),
                OffsetDateTime.parse("2026-07-18T10:00:00+09:00"));
    }

    /** artifact/cards/<slug>/ 아래에 기존 카드 파일을 심는다 — 재사용(§3.5) 분기 픽스처. */
    private List<Path> seedCards(String slug, String... filenames) throws IOException {
        List<Path> cards = new ArrayList<>();
        for (String filename : filenames) {
            Path card = artifactDir.resolve("cards").resolve(slug).resolve(filename);
            Files.createDirectories(card.getParent());
            Files.write(card, new byte[]{1});
            cards.add(card);
        }
        return cards;
    }

    // ---- fakes (모듈 CLAUDE.md §5.2 — 외부 의존은 인터페이스 stub/fake) ----

    private static final class StubNoteRepository implements NoteRepository {
        private final Map<String, Note> notes = new LinkedHashMap<>();

        void put(Note note) {
            notes.put(note.slug(), note);
        }

        @Override
        public List<Note> findAll() {
            return List.copyOf(notes.values());
        }

        @Override
        public Optional<Note> findBySlug(String slug) {
            return Optional.ofNullable(notes.get(slug));
        }

        @Override
        public String nextAvailableSlug(String base) {
            return base; // 충돌 접미 규칙(V-2)은 저장소 구현 테스트의 몫 — 여기선 base 그대로.
        }

        @Override
        public Note upsertEntry(String slug, NoteMeta meta, Entry entry, Aliases aliases) {
            throw new UnsupportedOperationException("제안 tool은 노트를 쓰지 않는다 — 커밋은 [저장] 버튼만(AC-Δ4)");
        }

        @Override
        public Note applyEdit(String slug, LocalDate targetDate, Note draft) {
            throw new UnsupportedOperationException("제안 tool은 노트를 쓰지 않는다 — 커밋은 [저장] 버튼만(AC-Δ4)");
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

    private static final class RecordingRenderer implements NoteRenderer {
        private final Path artifactDir;
        private final List<String> rendered = new ArrayList<>();

        private RecordingRenderer(Path artifactDir) {
            this.artifactDir = artifactDir;
        }

        @Override
        public void renderAll() {
            throw new UnsupportedOperationException("카드 재전송은 전체 리렌더를 부르지 않는다");
        }

        @Override
        public List<Path> renderEntryCard(String slug, LocalDate date) {
            rendered.add(slug + "/" + date);
            // 회차화(changes/0021 TΔ5a) — 픽스처 엔트리(감상 회차 1개)의 산출 형태.
            Path card = artifactDir.resolve("cards").resolve(slug).resolve(date + "-taste-1.jpg");
            try {
                Files.createDirectories(card.getParent());
                Files.write(card, new byte[]{1});
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return List.of(card);
        }

        @Override
        public void removeEntryCard(String slug, LocalDate date) {
            throw new UnsupportedOperationException("카드 재전송은 파생물을 지우지 않는다");
        }
    }

    private record PostedImage(String channelId, Path path, String caption) {
    }

    private static final class RecordingResponder implements SlackResponder {
        private final List<PostedImage> postedImages = new ArrayList<>();
        private final Set<String> failFilenames = new HashSet<>(); // 파일명 단위 업로드 실패 주입(부분 폴백 검증)

        @Override
        public void post(String channelId, String text) {
            throw new UnsupportedOperationException("읽기·카드·제안 tool은 평문 통지를 보내지 않는다");
        }

        @Override
        public void postImage(String channelId, Path imagePath, String caption) {
            if (failFilenames.contains(imagePath.getFileName().toString())) {
                throw new IllegalStateException("files.upload 실패: " + imagePath.getFileName());
            }
            postedImages.add(new PostedImage(channelId, imagePath, caption));
        }

        @Override
        public void finalizePreview(String channelId, PendingNote pending, String statusText) {
            throw new UnsupportedOperationException("읽기·카드·제안 tool은 버튼 소진을 만지지 않는다");
        }
    }
}
