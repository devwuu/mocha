package com.devwuu.mocha.web;

import com.devwuu.mocha.SingleUser;
import com.devwuu.mocha.agent.conversation.FoldingChatMemory;
import com.devwuu.mocha.agent.conversation.TranscriptTurn;
import com.devwuu.mocha.config.CommonConfig;
import com.devwuu.mocha.domain.Aliases;
import com.devwuu.mocha.domain.Cup;
import com.devwuu.mocha.domain.Entry;
import com.devwuu.mocha.domain.MatchInfo;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.NoteCandidate;
import com.devwuu.mocha.domain.NoteCursor;
import com.devwuu.mocha.domain.NoteDetail;
import com.devwuu.mocha.domain.NoteFacets;
import com.devwuu.mocha.domain.NoteFilter;
import com.devwuu.mocha.domain.NoteListItem;
import com.devwuu.mocha.domain.NoteMeta;
import com.devwuu.mocha.domain.NotePage;
import com.devwuu.mocha.domain.NotePhoto;
import com.devwuu.mocha.domain.Rating;
import com.devwuu.mocha.domain.Source;
import com.devwuu.mocha.domain.Review;
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
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
 *
 * <p>TΔ5a가 읽기 둘({@code note-list}·{@code note-detail})을, <b>TΔ5b-3이 수정·삭제 셋</b>
 * ({@code note-update})을 같은 규율로 보탰다. 수정 쪽에서 축 하나가 는다 — <b>요청에 자리가 없는 값</b>:
 * 커피명을 본문에 몰래 실어도 저장 요청에 실리는 것은 <i>저장된 값</i>이다(V-9의 1차 방어선이 검사가
 * 아니라 구조라는 것의 실행 가능한 형태). 실패는 자리로 갈린다 — {@code POST}의 대상 소실은 409,
 * {@code PATCH}·{@code DELETE}의 그것은 404다.
 */
@WebMvcTest(controllers = NoteController.class)
@Import({CommonConfig.class, NoteControllerTest.Fakes.class})
class NoteControllerTest {

    private static final String CONTRACT = "/contract/note-commit.contract.json";
    private static final String CANDIDATES_CONTRACT = "/contract/note-candidates.contract.json";
    private static final String LIST_CONTRACT = "/contract/note-list.contract.json";
    private static final String DETAIL_CONTRACT = "/contract/note-detail.contract.json";
    private static final String UPDATE_CONTRACT = "/contract/note-update.contract.json";

    /** 계약에 없는 값(V-11 원문)이 응답으로 새면 눈에 띄게 하는 마커 — 상세는 렌더이고 원문을 쓰지 않는다. */
    private static final String LEAKED_ORIGINAL = "ORIGINAL_MUST_NOT_LEAK";

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
        transcript.clear(SingleUser.ID, FoldingChatMemory.FoldTrigger.FORM_CLOSED);
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
        assertThat(noteService.lastDraft.entries().getFirst().cups().getFirst().review().rating())
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
    @DisplayName("TΔ29a/D-14: match=edit 본문은 400 — 수정의 저장 경로는 커밋이 아니라 엔트리 PATCH다")
    void editModeBodyIsRejectedOnTheCommitPath() throws Exception {
        ObjectNode request = (ObjectNode) contract.get("request");
        ObjectNode edit = mapper.createObjectNode();
        edit.put("type", "edit");
        edit.put("note_id", 12);
        edit.put("date", "2026-07-16");
        request.set("match", edit);

        perform(request, status().isBadRequest());

        // 통과시키면 "고치려던 것이 회차 추가로 저장되는" 조합이 된다 — existing과 edit이 같은 노트를
        // 가리켜도 의도가 반대라는 것이 D-14 ②의 요점이고, 그 경계가 여기서도 서야 한다.
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

    // ────────────────────────────── GET /api/notes (TΔ5a·TΔ12) ──────────────────────────────

    @Test
    @DisplayName("TΔ5a: 목록 응답이 계약 파일과 바이트 단위로 같다 — 커서·총수·facet·썸네일 URL이 한 번에 대조된다")
    void noteListResponseMatchesTheContract() throws Exception {
        JsonNode expected = load(LIST_CONTRACT).get("response");
        // 커서는 값으로 넣는다 — 문자열을 그대로 되돌려주면 코덱을 코덱으로 검증하는 셈이다. 계약 예시의
        // 커서가 마지막 노트(note_id 3, 시음일 없음)의 정렬 키라는 사실이 여기서 실물로 확인된다.
        noteService.page = new NotePage(listItems(expected), new NoteCursor(null, 3L), 48, facets(expected));

        String body = getNotes(status().isOk());

        assertThat(mapper.readTree(body)).isEqualTo(expected);
    }

    @Test
    @DisplayName("TΔ5a: 필터 4축 + 검색어가 그대로 서비스에 닿는다 — 좁히는 일의 소유자는 아래 층이다")
    void filterAxesReachTheService() throws Exception {
        JsonNode query = load(LIST_CONTRACT).get("request_query");

        mockMvc.perform(get("/api/notes")
                        .param("q", query.get("q").stringValue())
                        .param("roastery", "모모스커피", "프릳츠")
                        .param("process", "워시드")
                        .param("origin", query.get("origin").stringValue())
                        .param("rating", "완전 내스타일", "맛있다"))
                .andExpect(status().isOk());

        NoteFilter filter = noteService.lastFilter;
        assertThat(filter.query()).isEqualTo("게뎁");
        // 다중 축은 같은 키를 반복해 실린다 — 쉼표로 이으면 값 안의 쉼표와 구분자가 충돌한다.
        assertThat(filter.roastery()).containsExactly("모모스커피", "프릳츠");
        assertThat(filter.process()).containsExactly("워시드");
        assertThat(filter.origin()).isEqualTo("에티오피아");
        // 평가는 문자열이 아니라 도메인 enum으로 받는다 — 오타가 "아무것도 안 걸리는 필터"로 수렴하지 않는다.
        assertThat(filter.rating()).containsExactly(Rating.PERFECT, Rating.GOOD);
        assertThat(noteService.lastCursor).as("첫 페이지는 커서 없이 요청한다").isNull();
    }

    @Test
    @DisplayName("TΔ5a: 파라미터 없는 요청도 200 — 갤러리 첫 진입은 좁히지 않는다")
    void unfilteredRequestIsValid() throws Exception {
        getNotes(status().isOk());

        assertThat(noteService.lastFilter.roastery()).isEmpty();
        assertThat(noteService.lastFilter.process()).isEmpty();
        assertThat(noteService.lastFilter.rating()).isEmpty();
    }

    @Test
    @DisplayName("TΔ5a: 발급한 커서가 그대로 되돌아와 같은 지점을 가리킨다 — 클라이언트는 만들지도 해석하지도 않는다")
    void issuedCursorRoundTrips() throws Exception {
        String issued = load(LIST_CONTRACT).get("request_query_next_page").get("cursor").stringValue();

        mockMvc.perform(get("/api/notes").param("cursor", issued)).andExpect(status().isOk());

        // 계약 예시의 커서가 "시음일 없는 note_id 3 다음부터"라는 뜻임이 양방향으로 박힌다.
        assertThat(noteService.lastCursor).isEqualTo(new NoteCursor(null, 3L));
    }

    @Test
    @DisplayName("TΔ5a: 해석할 수 없는 커서는 400 — 조용히 첫 페이지로 되돌리면 목록 뒤에 같은 노트가 다시 쌓인다")
    void brokenCursorIsRejected() throws Exception {
        mockMvc.perform(get("/api/notes").param("cursor", "%%%not-a-cursor%%%"))
                .andExpect(status().isBadRequest());

        assertThat(noteService.listCalls).isZero();
    }

    @Test
    @DisplayName("TΔ5a: 4범주 밖의 평가는 400 — 서버가 만들 수 없는 값으로 거르지 않는다(V-1)")
    void ratingOutsideTheDomainCategoriesIsRejected() throws Exception {
        mockMvc.perform(get("/api/notes").param("rating", "그럭저럭"))
                .andExpect(status().isBadRequest());

        assertThat(noteService.listCalls).isZero();
    }

    @Test
    @DisplayName("TΔ5a: 결과 없음은 오류가 아니라 빈 목록이다 — 필터를 좁혀 0건이 되는 것은 정상 조작이다")
    void emptyResultIsAnEmptyListNotAnError() throws Exception {
        noteService.page = NotePage.empty();

        String body = getNotes(status().isOk());

        assertThat(mapper.readTree(body)).isEqualTo(load(LIST_CONTRACT).get("response_empty"));
    }

    // ────────────────────────────── GET /api/notes/{id} (TΔ5a·TΔ13a) ──────────────────────────────

    @Test
    @DisplayName("TΔ5a: 상세 응답이 계약 파일과 바이트 단위로 같다 — 출처·엔트리별 사진·회차가 한 번에 대조된다")
    void noteDetailResponseMatchesTheContract() throws Exception {
        JsonNode expected = load(DETAIL_CONTRACT).get("response");
        noteService.detail = detailOf(expected);

        String body = mockMvc.perform(get("/api/notes/21"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(noteService.lastDetailId).isEqualTo(21L);
        assertThat(mapper.readTree(body)).isEqualTo(expected);
    }

    @Test
    @DisplayName("TΔ5a: 감상 원문은 응답에 실리지 않는다 — 저장된 값이 있어도 렌더는 my_taste만 쓴다(V-11 뒷문장)")
    void detailNeverLeaksTheOriginalReview() throws Exception {
        // 도메인에는 반드시 원문이 있다(V-11) — fake가 그것을 눈에 띄는 값으로 들고 있어야 새는지가 보인다.
        noteService.detail = detailOf(load(DETAIL_CONTRACT).get("response"));

        String body = mockMvc.perform(get("/api/notes/21")).andReturn().getResponse()
                .getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).doesNotContain(LEAKED_ORIGINAL);
        assertThat(keysAnywhere(mapper.readTree(body)))
                .doesNotContain("my_taste_original", "aliases", "created_at", "updated_at");
    }

    @Test
    @DisplayName("TΔ5a: 엔트리 없는 노트도 200 — 정상 상태이지 실패가 아니다")
    void noteWithoutEntriesIsStillANote() throws Exception {
        JsonNode expected = load(DETAIL_CONTRACT).get("response_no_entries");
        noteService.detail = detailOf(expected);

        String body = mockMvc.perform(get("/api/notes/3"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(mapper.readTree(body)).isEqualTo(expected);
    }

    @Test
    @DisplayName("TΔ5a: 없는 id는 404 — 삭제된 노트를 텅 빈 상세로 그리지 않는다")
    void missingNoteAnswersWithNotFound() throws Exception {
        noteService.detail = null;

        mockMvc.perform(get("/api/notes/999")).andExpect(status().isNotFound());

        assertThat(noteService.lastDetailId).isEqualTo(999L);
    }

    @Test
    @DisplayName("TΔ5a: 숫자가 아닌 경로는 상세로 보지 않는다 — 없는 id로 404를 받는 것보다 정확하다")
    void nonNumericPathIsNotADetailRequest() throws Exception {
        mockMvc.perform(get("/api/notes/abc")).andExpect(status().isNotFound());

        // 서비스에 닿지 않는 것이 요점이다 — 클라이언트 라우터(matchNote)와 같은 판정이다.
        assertThat(noteService.lastDetailId).isNull();
    }

    // ─────────────────────── 수정·삭제 (TΔ5b-3, 계약 note-update) ───────────────────────

    @Test
    @DisplayName("TΔ5b-3/AC-5: 메타 수정 본문이 그대로 유스케이스에 닿고, 커피명은 저장된 값이 채운다(V-9 구조 차단)")
    void metaUpdateReachesTheUseCaseWithTheStoredCoffeeName() throws Exception {
        JsonNode update = load(UPDATE_CONTRACT);
        noteService.stored = detailOf(load(DETAIL_CONTRACT).get("response")).note();
        noteService.updated = detailOf(update.get("response_after_meta"));

        patch("/api/notes/21", update.get("meta_request"), status().isOk());

        assertThat(noteService.lastMetaId).isEqualTo(21L);
        // 폼이 고친 값이 조용히 사라지지 않는다 — 커밋 왕복(TΔ6b)과 같은 축의 단언이다.
        assertThat(noteService.lastMeta.roastery().value()).isEqualTo("프릳츠커피");
        assertThat(noteService.lastMeta.beans()).hasSize(2);
        assertThat(noteService.lastMeta.officialNotes().value()).containsExactly("자몽", "베르가못", "홍차");
        assertThat(noteService.lastMeta.sources()).containsExactly("https://fritz.co.kr/products/ethiopia-gedeb");
        // 커피명은 요청에 자리가 없으므로 서버가 저장된 값을 싣는다 — 이것이 V-9의 1차 방어선이다.
        assertThat(noteService.lastMeta.coffeeName()).isEqualTo(noteService.stored.coffeeName());
    }

    @Test
    @DisplayName("TΔ5b-3: 메타 수정 응답이 계약 파일과 바이트 단위로 같다 — 갱신된 노트 전문이 새 기준선이다")
    void metaUpdateAnswersWithTheUpdatedNote() throws Exception {
        JsonNode update = load(UPDATE_CONTRACT);
        JsonNode expected = update.get("response_after_meta");
        noteService.stored = detailOf(load(DETAIL_CONTRACT).get("response")).note();
        noteService.updated = detailOf(expected);

        String body = patch("/api/notes/21", update.get("meta_request"), status().isOk());

        assertThat(mapper.readTree(body)).isEqualTo(expected);
    }

    @Test
    @DisplayName("TΔ5b-3: 본문에 커피명을 실어도 저장 요청에 실리지 않는다 — 필드가 없어 닿을 자리가 없다(V-9)")
    void metaUpdateIgnoresACoffeeNameSmuggledIntoTheBody() throws Exception {
        JsonNode update = load(UPDATE_CONTRACT);
        noteService.stored = detailOf(load(DETAIL_CONTRACT).get("response")).note();
        noteService.updated = detailOf(update.get("response_after_meta"));
        ObjectNode smuggled = (ObjectNode) update.get("meta_request");
        smuggled.set("coffee_name", update.get("meta_request").get("roastery"));

        patch("/api/notes/21", smuggled, status().isOk());

        assertThat(noteService.lastMeta.coffeeName()).isEqualTo(noteService.stored.coffeeName());
    }

    @Test
    @DisplayName("TΔ5b-3: 없는 노트의 메타 수정은 404 — 실을 커피명이 없다는 것과 고칠 노트가 없다는 것이 같은 사실이다")
    void metaUpdateOnMissingNoteIsNotFound() throws Exception {
        noteService.stored = null;

        patch("/api/notes/999", load(UPDATE_CONTRACT).get("meta_request"), status().isNotFound());

        assertThat(noteService.lastMeta).as("소실 노트에 쓰기가 나갔다").isNull();
    }

    @Test
    @DisplayName("TΔ5b-3/AC-5: 엔트리 수정 — 경로의 date가 대상이고 본문의 date가 결과다")
    void entryUpdateSeparatesTargetDateFromResultDate() throws Exception {
        JsonNode update = load(UPDATE_CONTRACT);
        noteService.updated = detailOf(update.get("response_after_meta"));

        patch("/api/notes/21/entries/2026-07-02", update.get("entry_request"), status().isOk());

        assertThat(noteService.lastEntryId).isEqualTo(21L);
        assertThat(noteService.lastTargetDate).isEqualTo(LocalDate.of(2026, 7, 2));
        assertThat(noteService.lastEntry.date()).isEqualTo(LocalDate.of(2026, 7, 2));
        assertThat(noteService.lastEntry.cups()).hasSize(2);
    }

    @Test
    @DisplayName("TΔ5b-3/D-12: 날짜 이동 요청이 그대로 닿고, 응답은 병합된 노트 전문이다")
    void entryUpdateCarriesTheDateMoveAndAnswersWithTheMergedNote() throws Exception {
        JsonNode update = load(UPDATE_CONTRACT);
        JsonNode expected = update.get("response_after_move");
        noteService.updated = detailOf(expected);

        String body = patch("/api/notes/21/entries/2026-07-02", update.get("entry_request_moved"), status().isOk());

        // 대상은 경로, 결과는 본문 — 둘이 다르다는 사실 자체가 "이동"이다(합치는 일은 아래 층의 규칙).
        assertThat(noteService.lastTargetDate).isEqualTo(LocalDate.of(2026, 7, 2));
        assertThat(noteService.lastEntry.date()).isEqualTo(LocalDate.of(2026, 6, 28));
        // 병합 결과를 화면이 따라 계산하지 않는다 — 엔트리 총수 감소·사진 URL의 날짜까지 서버가 답한다.
        assertThat(mapper.readTree(body)).isEqualTo(expected);
    }

    @Test
    @DisplayName("TΔ5b-3: 요청에 감상 원문이 없으므로 정규화본이 양쪽에 담긴다 — 수정하면 '말한 그대로'가 편집본으로 수렴한다(V-11)")
    void entryUpdateFillsTheOriginalFromTheEditedReview() throws Exception {
        JsonNode update = load(UPDATE_CONTRACT);
        noteService.updated = detailOf(update.get("response_after_meta"));

        patch("/api/notes/21/entries/2026-07-02", update.get("entry_request"), status().isOk());

        Review review = noteService.lastEntry.cups().getLast().review();
        assertThat(review.myTaste()).isEqualTo("온도 낮추니 떫은 맛이 사라졌다. 다음에도 90℃로.");
        assertThat(review.myTasteOriginal()).isEqualTo(review.myTaste());
    }

    @Test
    @DisplayName("TΔ5b-3/V-15: 회차가 하나도 남지 않는 본문은 400 — 화면에 없는 엔트리 삭제 경로를 만들지 않는다")
    void entryUpdateWithoutCupsIsRejected() throws Exception {
        ObjectNode empty = (ObjectNode) load(UPDATE_CONTRACT).get("entry_request");
        // 빈 회차(레시피도 감상도 없음)는 V-15 정규화가 드롭한다 — 그 결과가 0건이면 저장할 시음이 없다.
        empty.set("brews", mapper.createArrayNode().add(
                mapper.createObjectNode().putNull("recipe").putNull("review")));

        patch("/api/notes/21/entries/2026-07-02", empty, status().isBadRequest());

        assertThat(noteService.lastEntry).as("저장할 회차가 없는 요청이 쓰기까지 갔다").isNull();
    }

    @Test
    @DisplayName("TΔ5b-3: 대상 엔트리 소실은 404 — 고칠 것이 URL에 없다는 뜻이라 노트 소실과 같은 답이다")
    void entryUpdateOnMissingTargetIsNotFound() throws Exception {
        noteService.editFailure = new IllegalStateException("수정 대상 엔트리 소실: 21 2026-01-01");

        patch("/api/notes/21/entries/2026-01-01", load(UPDATE_CONTRACT).get("entry_request"), status().isNotFound());
    }

    @Test
    @DisplayName("TΔ5b-3/AC-6: 삭제는 본문 없는 204")
    void deleteAnswersWithNoContent() throws Exception {
        noteService.deleted = true;

        mockMvc.perform(MockMvcRequestBuilders.delete("/api/notes/21"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        assertThat(noteService.lastDeleteId).isEqualTo(21L);
    }

    @Test
    @DisplayName("TΔ5b-3: 없는 id의 삭제는 404 — hard delete라 '지웠다'와 '지울 것이 없었다'를 뭉개지 않는다")
    void deletingAMissingNoteIsNotFound() throws Exception {
        noteService.deleted = false;

        mockMvc.perform(MockMvcRequestBuilders.delete("/api/notes/999")).andExpect(status().isNotFound());

        assertThat(noteService.lastDeleteId).isEqualTo(999L);
    }

    /** PATCH 요청 한 번 — 본문은 계약 파일의 노드 그대로다(손으로 지어내지 않는 규율). */
    private String patch(String path, JsonNode request, ResultMatcher expected) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.patch(path)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(expected)
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    /** 계약 예시의 목록을 도메인으로 되돌린다 — 썸네일은 URL에서 V-4 상대 경로로 되벗긴다. */
    private static List<NoteListItem> listItems(JsonNode response) {
        return response.get("notes").valueStream().map(note -> new NoteListItem(
                note.get("note_id").asLong(),
                note.get("coffee_name").stringValue(),
                stringOrNull(note.get("roastery")),
                dateOrNull(note.get("latest_date")),
                toArchivePath(stringOrNull(note.get("thumbnail_url"))))).toList();
    }

    private static NoteFacets facets(JsonNode response) {
        JsonNode facets = response.get("facets");
        return new NoteFacets(
                facets.get("roastery").valueStream().map(JsonNode::stringValue).toList(),
                facets.get("process").valueStream().map(JsonNode::stringValue).toList());
    }

    /**
     * 계약 예시의 상세를 도메인으로 되돌린다 — <b>왕복의 거울</b>.
     *
     * <p>응답 타입({@link NoteDetailBody})으로 한 번 읽고 도메인으로 옮긴다: 손으로 필드를 짚어 만들면
     * fake가 계약과 다른 값을 지어낼 수 있고, 그러면 이 테스트가 스스로 무의미해진다. 잃는 것은 계약에
     * 없는 값 하나({@code my_taste_original})뿐이라 그 자리에 <b>새면 보이는 마커</b>를 심는다.
     */
    private NoteDetail detailOf(JsonNode response) {
        NoteDetailBody body = mapper.treeToValue(response, NoteDetailBody.class);
        List<Entry> entries = new ArrayList<>();
        List<NotePhoto> photos = new ArrayList<>();
        for (NoteDetailBody.DetailEntry entry : body.entries()) {
            entries.add(new Entry(entry.date(), entry.cups().stream().map(NoteControllerTest::toCup).toList(), null));
            entry.photos().forEach(photo -> photos.add(new NotePhoto(entry.date(), toArchivePath(photo.url()))));
        }
        Note note = new Note(body.noteId(), body.coffeeName(), body.roastery(), body.beans(),
                body.roastLevel(), body.officialNotes(), body.sources(), entries, null, null);
        return new NoteDetail(note, photos);
    }

    private static Cup toCup(NoteDetailBody.DetailCup cup) {
        NoteDetailBody.DetailReview review = cup.review();
        return new Cup(cup.recipe(), review == null ? null
                : new Review(review.myTaste(), LEAKED_ORIGINAL, review.rating()));
    }

    /** {@code /api/photos/…} → V-4 상대 경로({@code photos/…}). {@code PhotoUrl}의 역방향이다. */
    private static String toArchivePath(String url) {
        return url == null ? null : url.replaceFirst("^/api/", "");
    }

    private static String stringOrNull(JsonNode node) {
        return node.isNull() ? null : node.stringValue();
    }

    private static LocalDate dateOrNull(JsonNode node) {
        return node.isNull() ? null : LocalDate.parse(node.stringValue());
    }

    private String getNotes(ResultMatcher expected) throws Exception {
        return mockMvc.perform(get("/api/notes"))
                .andExpect(expected)
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static List<String> keysAnywhere(JsonNode node) {
        List<String> keys = new ArrayList<>();
        collectKeys(node, keys);
        return keys;
    }

    private static void collectKeys(JsonNode node, List<String> into) {
        if (node.isObject()) {
            for (String name : node.propertyNames()) {
                into.add(name);
                collectKeys(node.get(name), into);
            }
        } else if (node.isArray()) {
            node.forEach(element -> collectKeys(element, into));
        }
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

        // 0029 TΔ5a: 조회 두 축. page/detail은 테스트가 계약 예시에서 되돌린 값을 심는다.
        int listCalls;
        NoteFilter lastFilter;
        NoteCursor lastCursor;
        NotePage page = NotePage.empty();
        Long lastDetailId;
        NoteDetail detail;

        // 0029 TΔ5b-3: 수정·삭제 셋. stored는 PATCH 메타가 읽는 저장된 노트(커피명의 출처 + 404 판정)이고,
        // updated는 두 PATCH가 돌려줄 값이다. editFailure로 아래 층의 소실 예외를 세운다.
        Note stored;
        NoteDetail updated;
        RuntimeException editFailure;
        Long lastMetaId;
        NoteMeta lastMeta;
        Long lastEntryId;
        LocalDate lastTargetDate;
        Entry lastEntry;
        Long lastDeleteId;
        boolean deleted = true;

        RecordingNoteService() {
            super(null, null, null, null);
        }

        void reset() {
            nextId = 12L;
            failure = null;
            calls = 0;
            lastDraft = null;
            lastMatch = null;
            lastQuery = null;
            candidates = List.of();
            listCalls = 0;
            lastFilter = null;
            lastCursor = null;
            page = NotePage.empty();
            lastDetailId = null;
            detail = null;
            stored = null;
            updated = null;
            editFailure = null;
            lastMetaId = null;
            lastMeta = null;
            lastEntryId = null;
            lastTargetDate = null;
            lastEntry = null;
            lastDeleteId = null;
            deleted = true;
        }

        @Override
        public Optional<Note> findById(long id) {
            return Optional.ofNullable(stored);
        }

        @Override
        public NoteDetail updateMeta(long noteId, NoteMeta meta) {
            lastMetaId = noteId;
            lastMeta = meta;
            if (editFailure != null) {
                throw editFailure;
            }
            return updated;
        }

        @Override
        public NoteDetail replaceEntry(long noteId, LocalDate targetDate, Entry entry) {
            lastEntryId = noteId;
            lastTargetDate = targetDate;
            lastEntry = entry;
            if (editFailure != null) {
                throw editFailure;
            }
            return updated;
        }

        @Override
        public boolean delete(long id) {
            lastDeleteId = id;
            return deleted;
        }

        @Override
        public List<NoteCandidate> findCandidates(String query) {
            lastQuery = query;
            return candidates;
        }

        @Override
        public NotePage findNotes(NoteFilter filter, NoteCursor cursor) {
            listCalls++;
            lastFilter = filter;
            lastCursor = cursor;
            return page;
        }

        @Override
        public Optional<NoteDetail> findDetail(long id) {
            lastDetailId = id;
            return Optional.ofNullable(detail);
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
