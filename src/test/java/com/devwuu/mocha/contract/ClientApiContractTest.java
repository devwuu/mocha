package com.devwuu.mocha.contract;

import com.devwuu.mocha.json.MochaObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TΔ10(changes/0029): <b>캡처 화면이 도출한 REST 계약 가드</b> — 화면이 기대하는 요청·응답 본문을
 * {@code contract/}의 파일로 고정하고, 그 파일들이 서로·기존 draft 스냅샷과 어긋나지 않는지 단언한다
 * (ref: changes/0029 tasks.md Phase 2 "계약 규율" — <i>"mock은 임시방편이 아니라 계약 초안이다"</i>).
 *
 * <p><b>왜 지금 파일로 박는가</b>: 슬라이스 규율상 화면이 API보다 먼저다(D-10 ③). 그 사이 화면은 mock을
 * 상대로 돌고, mock과 실제 API가 갈라지면 "화면 먼저"가 계약 도출이 아니라 재작업이 된다. 여기서 고정한
 * 본문을 TΔ6({@code POST /api/agent/turn}·{@code POST /api/notes})이 그대로 구현한다.
 *
 * <p><b>{@code aliases} 절단은 이 테스트가 소유한다</b>(TΔ2 이월 (b)의 판단 지점, tasks.md TΔ10). draft가
 * {@code Note} 그대로라서 내부 전용 별칭(V-13)이 계약에 실려 나가고 있었는데, <b>폼이 그 값을 쓰지 않는</b>
 * 것이 이 task에서 확인됐다 — 표시도 편집도 하지 않고, 커밋의 별칭 축적은 저장된 값을 읽어 서버가 계산한다
 * (TΔ4c {@code commit(noteId, meta, entry, generated)} — {@code generated == null}이 그 신호다).
 * 그래서 계약에서 뺀다. 이 테스트는 그 결정을 <i>실행 가능한 형태</i>로 박아, TΔ6이 draft를 {@code Note}
 * 그대로 노출하면 레드가 되게 한다.
 *
 * <p>대조 기준은 {@code turn-draft.contract.json}(TΔ2 캡처본)이다 — 세 계약 파일의 draft 노드가 전부
 * "그 스냅샷에서 {@code aliases}만 뺀 것"과 같아야 한다. 필드 하나가 조용히 사라지는 경로(TΔ2 스냅샷을
 * 세운 바로 그 이유)를 계약 파일 쪽에서도 막는다.
 */
class ClientApiContractTest {

    private static final String TURN_DRAFT_SNAPSHOT = "/contract/turn-draft.contract.json";
    private static final String AGENT_TURN_CONTRACT = "/contract/agent-turn.contract.json";
    private static final String NOTE_COMMIT_CONTRACT = "/contract/note-commit.contract.json";

    private final JsonMapper mapper = MochaObjectMapper.create();

    @Test
    @DisplayName("TΔ10: 턴 요청의 draft = TΔ2 스냅샷 − aliases — 폼이 안 쓰는 내부 값은 계약에서 뺀다")
    void turnRequestDraftMatchesSnapshotWithoutAliases() throws IOException {
        JsonNode expected = draftWithoutAliases();

        assertThat(load(AGENT_TURN_CONTRACT).get("request").get("draft")).isEqualTo(expected);
    }

    @Test
    @DisplayName("TΔ10: 응답 draft·커밋 본문도 같은 draft 형태 — 이번 턴의 제안이 곧 다음 턴의 draft이고 저장 본문이다")
    void proposalAndCommitBodiesShareTheDraftShape() throws IOException {
        List<String> expectedFields = fieldNames(draftWithoutAliases().get("note"));

        JsonNode responseDraft = load(AGENT_TURN_CONTRACT).get("response").get("draft");
        JsonNode commitBody = load(NOTE_COMMIT_CONTRACT).get("request");

        assertThat(fieldNames(responseDraft.get("note"))).isEqualTo(expectedFields);
        assertThat(fieldNames(commitBody.get("note"))).isEqualTo(expectedFields);
        // 매칭 배지도 draft의 일부다 — 사용자가 배지에서 바꿀 수 있고(OQ-2 ㉢) 그대로 커밋 본문에 실린다.
        assertThat(responseDraft.get("match")).isNotNull();
        assertThat(commitBody.get("match")).isNotNull();
    }

    @Test
    @DisplayName("TΔ10: 계약 어디에도 aliases 키가 없다 — 중첩 어느 깊이에서도(V-13 내부 전용)")
    void aliasesAppearNowhereInTheClientContracts() throws IOException {
        for (String contract : List.of(AGENT_TURN_CONTRACT, NOTE_COMMIT_CONTRACT)) {
            assertThat(keysAnywhere(load(contract)))
                    .as("%s 에 내부 전용 별칭이 실려 나간다", contract)
                    .doesNotContain("aliases");
        }
    }

    @Test
    @DisplayName("TΔ10: 턴 요청 본문 = {utterance, draft}, draft는 첫 턴에 null — 폼이 없으면 보낼 것이 없다")
    void turnRequestCarriesUtteranceAndOptionalDraft() throws IOException {
        JsonNode contract = load(AGENT_TURN_CONTRACT);

        assertThat(fieldNames(contract.get("request"))).containsExactly("utterance", "draft");
        assertThat(fieldNames(contract.get("request_first_turn"))).containsExactly("utterance", "draft");
        assertThat(contract.get("request_first_turn").get("draft").isNull()).isTrue();
    }

    @Test
    @DisplayName("TΔ10: 턴 응답 본문 = {reply, draft}, 제안 없는 턴은 draft가 null — 잡담·조회 턴이 폼을 지우지 않는다")
    void turnResponseCarriesReplyAndOptionalDraft() throws IOException {
        JsonNode contract = load(AGENT_TURN_CONTRACT);

        assertThat(fieldNames(contract.get("response"))).containsExactly("reply", "draft");
        assertThat(fieldNames(contract.get("response_no_proposal"))).containsExactly("reply", "draft");
        assertThat(contract.get("response_no_proposal").get("draft").isNull()).isTrue();
        assertThat(contract.get("response_no_proposal").get("reply").isString()).isTrue();
    }

    @Test
    @DisplayName("TΔ10: 커밋 응답은 저장된 노트의 식별자를 돌려준다 — 저장 후 상세로 갈 수 있어야 한다(S2)")
    void commitResponseCarriesTheStoredNoteId() throws IOException {
        JsonNode response = load(NOTE_COMMIT_CONTRACT).get("response");

        assertThat(fieldNames(response)).containsExactly("note_id");
        assertThat(response.get("note_id").isIntegralNumber()).isTrue();
    }

    /** TΔ2 캡처본에서 {@code note.aliases}만 제거한 draft — 이 델타가 확정한 클라이언트 계약형이다. */
    private JsonNode draftWithoutAliases() throws IOException {
        ObjectNode draft = (ObjectNode) load(TURN_DRAFT_SNAPSHOT);
        ((ObjectNode) draft.get("note")).remove("aliases");
        return draft;
    }

    private JsonNode load(String resource) throws IOException {
        try (InputStream in = ClientApiContractTest.class.getResourceAsStream(resource)) {
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
}
