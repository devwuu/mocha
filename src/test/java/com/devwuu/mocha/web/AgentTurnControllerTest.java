package com.devwuu.mocha.web;

import com.devwuu.mocha.SingleUser;
import com.devwuu.mocha.agent.AgentException;
import com.devwuu.mocha.agent.conversation.FoldingChatMemory;
import com.devwuu.mocha.agent.conversation.TranscriptTurn;
import com.devwuu.mocha.agent.turn.TurnDraft;
import com.devwuu.mocha.agent.turn.TurnResult;
import com.devwuu.mocha.agent.turn.TurnRunner;
import com.devwuu.mocha.config.CommonConfig;
import com.devwuu.mocha.json.MochaObjectMapper;
import com.devwuu.mocha.repository.RecordingPhotoStore;
import com.devwuu.mocha.service.PhotoService;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TΔ6a(changes/0029): {@code POST /api/agent/turn} — 계약 구현 가드.
 *
 * <p>대조 기준은 {@code contract/agent-turn.contract.json}이다(TΔ10이 화면에서 도출해 박은 계약). 여기서
 * 하는 검증의 핵심은 <b>왕복</b>이다: 계약 파일의 요청 본문을 그대로 POST하고, 러너가 받은 draft를 그대로
 * 제안으로 되돌리게 한 뒤, 응답의 draft 노드가 <b>보낸 것과 같은지</b>를 본다. 이 한 바퀴가
 * <ul>
 *   <li>필드명(snake_case) — 어긋나면 값이 <b>조용히 사라진다</b>. 이 델타가 없애려는 실패(§1.2)의 경로다.</li>
 *   <li>{@code aliases} 절단(TΔ10 결정) — {@link NoteBody}가 도메인 {@code Note}를 그대로 노출하지 않는다.</li>
 *   <li>중첩 전 구간(출처 표시·원두·엔트리·회차·레시피·감상)의 직렬화·역직렬화</li>
 * </ul>
 * 를 한꺼번에 답한다.
 *
 * <p>러너는 손 fake다({@code org.mockito} 미도입 방침 유지) — 이 테스트의 대상은 <b>HTTP 표면</b>이고,
 * 턴 내부는 {@code AgentConversationRouterTest}가 실물로 검증한다.
 *
 * <p>TΔ6b가 같은 컨트롤러에 {@code POST /api/agent/cancel}을 보탰다 — 계약이 <i>"본문 없음 → 204"</i>라
 * 왕복으로 볼 것이 없고, 검증 대상은 <b>부수효과</b>다: 버린 작업의 대화 문맥이 실제로 접혔는가
 * ({@code contract/agent-cancel.contract.json}).
 *
 * <p>{@link CommonConfig}를 함께 들이는 것이 필수다 — 도메인 JSON 규칙(snake_case)의 소유자가 그 빈이고,
 * 슬라이스가 그것 없이 뜨면 부트 기본 매퍼(camelCase)로 통과해 <b>계약과 다른 것을 검증</b>하게 된다.
 */
@WebMvcTest(controllers = AgentTurnController.class)
@Import({CommonConfig.class, AgentTurnControllerTest.Fakes.class})
class AgentTurnControllerTest {

    private static final String CONTRACT = "/contract/agent-turn.contract.json";

    private final JsonMapper mapper = MochaObjectMapper.create();

    @Autowired
    MockMvc mockMvc;

    @Autowired
    EchoTurnRunner turnRunner;

    @Autowired
    FoldingChatMemory transcript;

    @Autowired
    RecordingPhotoStore photoStore;

    private JsonNode contract;

    @BeforeEach
    void setUp() throws IOException {
        contract = load(CONTRACT);
        turnRunner.reset();
        photoStore.discarded.clear();
        // 컨텍스트가 테스트 메서드 간 공유되므로 문맥도 함께 되돌린다(접힘 단언이 앞 테스트에 물들지 않게).
        transcript.clear(SingleUser.ID, FoldingChatMemory.FoldTrigger.FORM_CLOSED);
    }

    @Test
    @DisplayName("TΔ6a: 계약의 턴 요청이 그대로 왕복한다 — 폼이 보낸 draft가 응답에서 한 필드도 잃지 않는다")
    void contractRequestRoundTripsThroughTheApi() throws Exception {
        JsonNode request = contract.get("request");
        turnRunner.echoDraft = true;

        String body = perform(request);

        assertThat(mapper.readTree(body).get("draft")).isEqualTo(request.get("draft"));
        // 서버가 실제로 받은 값도 확인한다 — 응답만 보면 컨트롤러가 본문을 되돌려주기만 해도 통과한다.
        assertThat(turnRunner.lastUtterance).isEqualTo(request.get("utterance").stringValue());
        assertThat(turnRunner.lastDraft).isNotNull();
        assertThat(turnRunner.lastDraft.note().roastery().value()).isEqualTo("FroB");
        assertThat(turnRunner.lastUserId).isEqualTo(SingleUser.ID);
    }

    @Test
    @DisplayName("TΔ6a: 응답에 aliases가 없다 — 내부 전용 별칭(V-13)을 노출하지 않는 것이 TΔ10의 결정이다")
    void responseCarriesNoInternalAliases() throws Exception {
        turnRunner.echoDraft = true;

        String body = perform(contract.get("request"));

        assertThat(keysAnywhere(mapper.readTree(body)))
                .as("draft를 도메인 Note 그대로 노출하면 여기가 레드다")
                .doesNotContain("aliases");
    }

    @Test
    @DisplayName("TΔ6a: 첫 턴은 draft 없이 온다 — 폼이 없으면 보낼 것이 없고, 러너는 null을 받는다")
    void firstTurnCarriesNoDraft() throws Exception {
        perform(contract.get("request_first_turn"));

        assertThat(turnRunner.lastDraft).isNull();
        assertThat(turnRunner.lastUtterance).isEqualTo("오늘 이거 마셨어");
    }

    @Test
    @DisplayName("TΔ6a: 제안 없는 턴은 응답 draft가 null — 잡담·조회 턴이 폼을 지우지 않는다")
    void turnWithoutProposalAnswersWithNullDraft() throws Exception {
        turnRunner.echoDraft = false;
        turnRunner.reply = "그 원두는 워시드 가공이에요. 어떻게 내려 드셨나요?";

        String body = perform(contract.get("request"));

        JsonNode response = mapper.readTree(body);
        assertThat(fieldNames(response)).containsExactly("reply", "draft");
        assertThat(response.get("draft").isNull()).isTrue();
        assertThat(response.get("reply").stringValue()).isEqualTo(turnRunner.reply);
    }

    @Test
    @DisplayName("AC-63/ADR-48: 턴 실패는 실패 상태로 수렴한다 — 안내를 성공 본문에 실으면 실패가 정상 응답으로 보인다")
    void turnFailureAnswersWithAnErrorStatus() throws Exception {
        turnRunner.failure = new AgentException("tool 호출 상한(8) 도달 — 턴 중단");

        mockMvc.perform(post("/api/agent/turn")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(contract.get("request_first_turn"))))
                .andExpect(status().isInternalServerError());
    }

    @Test
    @DisplayName("TΔ6a: 발화도 사진도 없으면 모델을 부르지 않는다 — 400")
    void blankUtteranceIsRejectedWithoutCallingTheModel() throws Exception {
        mockMvc.perform(post("/api/agent/turn")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"utterance\":\"   \",\"draft\":null,\"photos\":[]}"))
                .andExpect(status().isBadRequest());

        assertThat(turnRunner.calls).isZero();
    }

    @Test
    @DisplayName("TΔ8a: 첨부된 사진 이름이 그대로 턴에 실린다 — 서버가 읽을 대상을 클라이언트가 지목한다(D-11)")
    void attachedPhotoNamesReachTheTurn() throws Exception {
        perform(contract.get("request_with_photos"));

        // 스테이징에는 저장·취소 전까지 올린 사진이 누적되므로, 이번 메시지의 것이 무엇인지는 이 이름들뿐이다.
        assertThat(turnRunner.lastPhotos).containsExactly("bag-front.jpg", "bag-back.jpg");
        assertThat(turnRunner.lastUtterance).isEqualTo("이거 오늘 마셨어");
    }

    @Test
    @DisplayName("TΔ8a: 사진만 첨부하고 보내는 것도 유효한 턴이다 — 재료가 사진에서 나온다(D-11)")
    void photosOnlyTurnIsAccepted() throws Exception {
        perform(contract.get("request_photos_only"));

        assertThat(turnRunner.calls).isEqualTo(1);
        assertThat(turnRunner.lastUtterance).isEmpty();
        assertThat(turnRunner.lastPhotos).containsExactly("bag-front.jpg");
    }

    @Test
    @DisplayName("TΔ8a: photos가 없는 요청도 그대로 돈다 — 사진 없는 턴이 여전히 기본형이다")
    void turnWithoutPhotosStillRuns() throws Exception {
        perform(contract.get("request_first_turn"));

        assertThat(turnRunner.lastPhotos).isEmpty();
    }

    @Test
    @DisplayName("TΔ6b: [취소] 통지가 대화 문맥을 접는다 — 버린 작업의 문맥이 다음 커피의 첫 발화에 섞이지 않는다")
    void cancelFoldsTheTranscript() throws Exception {
        transcript.append(SingleUser.ID, new TranscriptTurn("게뎁 마셨어", "폼에 담아 뒀어요."));

        mockMvc.perform(post("/api/agent/cancel"))
                .andExpect(status().isNoContent());

        assertThat(transcript.view(SingleUser.ID)).isEmpty();
    }

    @Test
    @DisplayName("TΔ8a: [취소]는 대화 문맥과 함께 사진 스테이징도 폐기한다 — 버린 노트의 사진이 다음 턴에 남지 않는다")
    void cancelDiscardsStagedPhotos() throws Exception {
        mockMvc.perform(post("/api/agent/cancel"))
                .andExpect(status().isNoContent());

        assertThat(photoStore.discarded).containsExactly(SingleUser.ID);
    }

    @Test
    @DisplayName("TΔ6b: 접을 문맥이 없어도 취소는 성공한다 — 폼만 있고 대화가 만료된 상태가 실재한다(TTL)")
    void cancelWithoutTranscriptStillSucceeds() throws Exception {
        mockMvc.perform(post("/api/agent/cancel"))
                .andExpect(status().isNoContent());

        assertThat(transcript.view(SingleUser.ID)).isEmpty();
    }

    @Test
    @DisplayName("TΔ6b: 취소는 본문을 받지 않는다 — 무엇을 취소하는지는 서버가 안다(계약의 '본문 없음')")
    void cancelTakesNoRequestBody() throws Exception {
        String body = mockMvc.perform(post("/api/agent/cancel"))
                .andExpect(status().isNoContent())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(body).isEmpty();
    }

    private String perform(JsonNode request) throws Exception {
        return mockMvc.perform(post("/api/agent/turn")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private JsonNode load(String resource) throws IOException {
        try (InputStream in = AgentTurnControllerTest.class.getResourceAsStream(resource)) {
            assertThat(in).as("계약 리소스 %s", resource).isNotNull();
            return mapper.readTree(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private static List<String> fieldNames(JsonNode object) {
        return new ArrayList<>(object.propertyNames());
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

    @TestConfiguration
    static class Fakes {
        @Bean
        EchoTurnRunner turnRunner() {
            return new EchoTurnRunner();
        }

        @Bean
        RecordingPhotoStore photoStore() {
            return new RecordingPhotoStore();
        }

        /**
         * 실물 service를 저장소 fake 위에 세운다 — 취소가 <i>스테이징을 실제로 폐기하는가</i>가 검증
         * 대상이라 위임 한 겹을 스텁으로 가리면 그 사실이 아니라 "service를 불렀다"까지만 남는다.
         */
        @Bean
        PhotoService photoService(RecordingPhotoStore photoStore) {
            return new PhotoService(photoStore);
        }

        /**
         * 실물이다 — 접힘은 값 없는 부수효과라 스텁으로 바꾸면 <i>"clear를 불렀다"</i>까지만 확인하게 된다.
         * 확인하고 싶은 것은 그 다음, <b>문맥이 실제로 비었는가</b>다.
         */
        @Bean
        FoldingChatMemory transcript() {
            return new FoldingChatMemory(10, Duration.ofMinutes(30), Clock.systemDefaultZone());
        }
    }

    /**
     * 받은 draft를 그대로 제안으로 되돌리는 러너 — <b>왕복 검증의 거울</b>이다.
     * <p>값을 조금이라도 바꾸면 "폼에서 고친 값이 되돌아가는" 실패(delta §1.2)를 fake가 스스로 만들어
     * 이 테스트가 무의미해진다. 프론트 mock이 같은 규율을 지켰던 것과 같은 이유다(TΔ10).
     */
    static final class EchoTurnRunner extends TurnRunner {

        String reply = "폼에 담아 뒀어요. 확인해 주세요.";
        boolean echoDraft = true;
        RuntimeException failure;
        int calls;
        String lastUserId;
        String lastUtterance;
        TurnDraft lastDraft;
        List<String> lastPhotos;

        EchoTurnRunner() {
            super(null, null, null, null, null, null, null, null);
        }

        void reset() {
            reply = "폼에 담아 뒀어요. 확인해 주세요.";
            echoDraft = true;
            failure = null;
            calls = 0;
            lastUserId = null;
            lastUtterance = null;
            lastDraft = null;
            lastPhotos = null;
        }

        /**
         * 컨트롤러가 부르는 판본 — TΔ8a에서 seam이 {@code VisionExtraction}에서 <b>사진 이름</b>으로
         * 내려왔다. OCR이 턴 안으로 들어갔기 때문이고(D-11), 덕분에 이 fake가 <i>"어떤 사진이 실렸나"</i>를
         * 그대로 관측한다 — 컨트롤러가 이름을 흘리면 여기가 레드다.
         */
        @Override
        public TurnResult run(String userId, String utterance, TurnDraft draft, List<String> photoNames) {
            calls++;
            lastUserId = userId;
            lastUtterance = utterance;
            lastDraft = draft;
            lastPhotos = photoNames;
            if (failure != null) {
                throw failure;
            }
            return new TurnResult(reply, echoDraft ? draft : null);
        }
    }
}
