package com.devwuu.mocha.web;

import com.devwuu.mocha.agent.conversation.FoldingChatMemory;
import com.devwuu.mocha.agent.conversation.TranscriptTurn;
import com.devwuu.mocha.config.CommonConfig;
import com.devwuu.mocha.domain.Aliases;
import com.devwuu.mocha.domain.MatchInfo;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.NoteCandidate;
import com.devwuu.mocha.domain.Rating;
import com.devwuu.mocha.domain.Source;
import com.devwuu.mocha.json.MochaObjectMapper;
import com.devwuu.mocha.service.NoteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TΔ6b(changes/0029): {@code POST /api/notes} — 계약 구현 가드.
 *
 * <p>대조 기준은 {@code contract/note-commit.contract.json}이다(TΔ10이 화면에서 도출해 박은 계약).
 * 검증의 축은 셋이다.
 * <ul>
 *   <li><b>왕복</b> — 계약 파일의 커밋 본문을 그대로 POST하고, 서비스가 <i>실제로 받은</i> 값이 보낸 것과
 *       같은지 본다. 필드명이 어긋나면 값이 <b>조용히 사라지고</b>, 그것이 사용자가 폼에서 고친 값이
 *       되돌아가는 실패(delta §1.2)의 경로다. 왕복이 turn API와 같은 draft 형태를 지난다는 사실도 여기서
 *       확인된다 — 턴 응답이 그대로 저장 본문이 된다는 계약의 실물 증거다(AC-1 end-to-end).</li>
 *   <li><b>접힘</b> — 저장이 서면 대화 문맥이 접히고(SAVE_COMMIT), 실패하면 접히지 않는다. TΔ4에서
 *       버튼과 함께 끊긴 배선이 다시 서는 자리다.</li>
 *   <li><b>거부</b> — 결손 본문·대상 소실이 각각 다른 상태 코드로 갈린다(안내 문구의 소유자는 화면이다).</li>
 * </ul>
 *
 * <p>서비스는 손 fake다({@code org.mockito} 미도입 방침 유지) — 이 테스트의 대상은 <b>HTTP 표면</b>이고,
 * 커밋 유스케이스 내부(별칭 생성 콜·신규/기존 분기)는 {@code NoteServiceTest}가 검증한다.
 * {@link FoldingChatMemory}만 실물인데, 접힘은 값 없는 부수효과라 <i>"clear를 불렀다"</i>가 아니라
 * <b>문맥이 실제로 비었는가</b>를 보고 싶기 때문이다.
 *
 * <p>{@link CommonConfig}를 함께 들이는 것이 필수다 — 도메인 JSON 규칙(snake_case)의 소유자가 그 빈이고,
 * 슬라이스가 그것 없이 뜨면 부트 기본 매퍼(camelCase)로 통과해 <b>계약과 다른 것을 검증</b>하게 된다.
 */
@WebMvcTest(controllers = NoteController.class)
@Import({CommonConfig.class, NoteControllerTest.Fakes.class})
class NoteControllerTest {

    private static final String CONTRACT = "/contract/note-commit.contract.json";
    private static final String CANDIDATES_CONTRACT = "/contract/note-candidates.contract.json";

    private final JsonMapper mapper = MochaObjectMapper.create();

    @Autowired
    MockMvc mockMvc;

    @Autowired
    RecordingNoteService noteService;

    @Autowired
    FoldingChatMemory transcript;

    private JsonNode contract;

    @BeforeEach
    void setUp() throws IOException {
        contract = load(CONTRACT);
        noteService.reset();
        // 컨텍스트가 테스트 메서드 간 공유되므로 문맥도 함께 되돌린다(접힘 단언이 앞 테스트에 물들지 않게).
        transcript.clear(SingleUser.ID, FoldingChatMemory.FoldTrigger.CANCEL_COMMIT);
    }

    @Test
    @DisplayName("TΔ6b/AC-1: 폼이 확정한 draft가 그대로 커밋 유스케이스에 닿는다 — 고친 로스터리가 저장까지 간다")
    void contractBodyReachesTheCommitUseCase() throws Exception {
        noteService.nextId = 12L;

        String body = perform(contract.get("request"), status().isOk());

        // 사용자가 폼에서 고친 값(source=user)이 서버에 그대로 도착했는가 — 이 델타의 방어선이다.
        assertThat(noteService.lastDraft).isNotNull();
        assertThat(noteService.lastDraft.roastery().value()).isEqualTo("커피베라");
        assertThat(noteService.lastDraft.roastery().source()).isEqualTo(Source.USER);
        assertThat(noteService.lastDraft.entries()).hasSize(1);
        assertThat(noteService.lastDraft.entries().getFirst().brews().getFirst().tasting().rating())
                .isEqualTo(Rating.GOOD);
        assertThat(noteService.lastMatch.type()).isEqualTo(MatchInfo.MatchType.NEW);
        assertThat(mapper.readTree(body)).isEqualTo(contract.get("response"));
    }

    @Test
    @DisplayName("TΔ6b: 별칭은 클라이언트가 보내지 않는다 — 계약에 없는 값을 서버가 지어내지도 않는다(V-13 내부 전용)")
    void commitCarriesNoClientSuppliedAliases() throws Exception {
        perform(contract.get("request"), status().isOk());

        assertThat(noteService.lastDraft.aliases())
                .as("NoteBody가 도메인 Note를 그대로 받으면 여기가 흔들린다")
                .isEqualTo(Aliases.empty());
    }

    @Test
    @DisplayName("TΔ6b: 저장이 서면 대화 문맥이 접힌다(SAVE_COMMIT) — TΔ4에서 버튼과 함께 끊긴 배선이 다시 선다")
    void successfulCommitFoldsTheTranscript() throws Exception {
        transcript.append(SingleUser.ID, new TranscriptTurn("게뎁 마셨어", "폼에 담아 뒀어요."));

        perform(contract.get("request"), status().isOk());

        assertThat(transcript.view(SingleUser.ID)).isEmpty();
    }

    @Test
    @DisplayName("TΔ6b: 저장이 실패하면 접지 않는다 — 폼도 문맥도 남아야 재시도가 성립한다")
    void failedCommitLeavesTheTranscriptAlone() throws Exception {
        transcript.append(SingleUser.ID, new TranscriptTurn("게뎁 마셨어", "폼에 담아 뒀어요."));
        noteService.failure = new RuntimeException("DB 연결 실패");

        perform(contract.get("request"), status().isInternalServerError());

        assertThat(transcript.view(SingleUser.ID)).hasSize(1);
    }

    @Test
    @DisplayName("TΔ6b: match 없는 본문은 400 — 관대하게 받으면 신규 노트의 별칭 생성 콜(ADR-37)을 조용히 놓친다")
    void bodyWithoutMatchIsRejected() throws Exception {
        ObjectNode request = (ObjectNode) contract.get("request");
        request.remove("match");

        perform(request, status().isBadRequest());

        assertThat(noteService.calls).isZero();
    }

    @Test
    @DisplayName("TΔ6b: 저장할 시음이 없는 본문은 400 — 폼이 아닌 것을 저장으로 받지 않는다")
    void bodyWithoutEntriesIsRejected() throws Exception {
        ObjectNode request = (ObjectNode) contract.get("request");
        ((ObjectNode) request.get("note")).putArray("entries");
        noteService.failure = new IllegalArgumentException("저장할 시음 엔트리가 없다: noteId=null");

        perform(request, status().isBadRequest());
    }

    @Test
    @DisplayName("TΔ6b: 병합 대상 노트가 그 사이 사라졌으면 409 — 서버 고장이 아니라 상태 충돌이다")
    void missingMergeTargetAnswersWithConflict() throws Exception {
        noteService.failure = new IllegalStateException("병합 대상 노트 소실: 7");

        perform(contract.get("request"), status().isConflict());
    }

    // ────────────────────────────── GET /api/notes/candidates (TΔ7) ──────────────────────────────

    @Test
    @DisplayName("TΔ7: 후보 응답이 계약 파일과 바이트 단위로 같다 — 시트가 읽는 필드명이 여기서 정해진다")
    void candidateResponseMatchesTheContract() throws Exception {
        JsonNode candidates = load(CANDIDATES_CONTRACT).get("response").get("candidates");
        noteService.candidates = candidates.valueStream().map(NoteControllerTest::toCandidate).toList();

        String body = mockMvc.perform(get("/api/notes/candidates").param("q", "게뎁"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        // note_id·coffee_name·roastery·latest_date의 snake_case와 null 표현이 한 번에 대조된다 —
        // LocalDate가 배열([2026,7,28])로 나가는 흔한 사고도 여기서 걸린다.
        assertThat(mapper.readTree(body)).isEqualTo(load(CANDIDATES_CONTRACT).get("response"));
    }

    @Test
    @DisplayName("TΔ7: q가 그대로 서비스에 닿는다 — 좁히는 일의 소유자는 서버다")
    void queryReachesTheService() throws Exception {
        mockMvc.perform(get("/api/notes/candidates").param("q", "예가체프 지1"))
                .andExpect(status().isOk());

        // 정규화도 대조도 전부 아래에서 일어난다 — 컨트롤러가 손대면 별칭 축이 그만큼 좁아진다.
        assertThat(noteService.lastQuery).isEqualTo("예가체프 지1");
    }

    @Test
    @DisplayName("TΔ7: q 없는 요청도 200 — 커피명이 아직 안 뽑힌 상태에서 시트가 열린다")
    void missingQueryIsAValidRequest() throws Exception {
        String body = mockMvc.perform(get("/api/notes/candidates"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(noteService.lastQuery).isNull();
        assertThat(mapper.readTree(body).get("candidates")).isEmpty();
    }

    @Test
    @DisplayName("TΔ7: 후보 없음은 404가 아니라 빈 목록이다 — 그 자리에 [새 노트로 등록]이 있어야 한다")
    void noCandidatesIsAnEmptyListNotAnError() throws Exception {
        noteService.candidates = List.of();

        String body = mockMvc.perform(get("/api/notes/candidates").param("q", "케냐"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(mapper.readTree(body)).isEqualTo(load(CANDIDATES_CONTRACT).get("response_empty"));
    }

    /** 계약 파일의 후보 1건을 도메인으로 되돌린다 — fake가 값을 지어내지 않게 하는 규율(왕복의 거울). */
    private static NoteCandidate toCandidate(JsonNode node) {
        JsonNode roastery = node.get("roastery");
        JsonNode latestDate = node.get("latest_date");
        return new NoteCandidate(
                node.get("note_id").asLong(),
                node.get("coffee_name").stringValue(),
                roastery.isNull() ? null : roastery.stringValue(),
                latestDate.isNull() ? null : LocalDate.parse(latestDate.stringValue()));
    }

    private String perform(JsonNode request, ResultMatcher expected) throws Exception {
        return mockMvc.perform(post("/api/notes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(expected)
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private JsonNode load(String resource) throws IOException {
        try (InputStream in = NoteControllerTest.class.getResourceAsStream(resource)) {
            assertThat(in).as("계약 리소스 %s", resource).isNotNull();
            return mapper.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    @TestConfiguration
    static class Fakes {
        @Bean
        RecordingNoteService noteService() {
            return new RecordingNoteService();
        }

        @Bean
        FoldingChatMemory transcript() {
            return new FoldingChatMemory(10, Duration.ofMinutes(30), Clock.systemDefaultZone());
        }
    }

    /**
     * 받은 draft를 기록하고 id만 박아 되돌리는 서비스 — <b>왕복 검증의 거울</b>이다.
     * <p>값을 조금이라도 바꾸면 "폼에서 고친 값이 되돌아가는" 실패(delta §1.2)를 fake가 스스로 만들어
     * 이 테스트가 무의미해진다({@code EchoTurnRunner}·프론트 mock과 같은 규율).
     */
    static final class RecordingNoteService extends NoteService {

        long nextId = 12L;
        RuntimeException failure;
        int calls;
        Note lastDraft;
        MatchInfo lastMatch;
        String lastQuery;
        List<NoteCandidate> candidates = List.of();

        RecordingNoteService() {
            super(null, null);
        }

        void reset() {
            nextId = 12L;
            failure = null;
            calls = 0;
            lastDraft = null;
            lastMatch = null;
            lastQuery = null;
            candidates = List.of();
        }

        @Override
        public List<NoteCandidate> findCandidates(String query) {
            lastQuery = query;
            return candidates;
        }

        @Override
        public Note commit(Note draft, MatchInfo match) {
            calls++;
            lastDraft = draft;
            lastMatch = match;
            if (failure != null) {
                throw failure;
            }
            return withId(draft, nextId);
        }

        private static Note withId(Note draft, long id) {
            return new Note(id, draft.coffeeName(), draft.roastery(), draft.beans(), draft.roastLevel(),
                    draft.officialNotes(), draft.aliases(), draft.sources(), draft.entries(),
                    draft.createdAt() == null ? OffsetDateTime.now() : draft.createdAt(),
                    OffsetDateTime.now());
        }
    }
}
