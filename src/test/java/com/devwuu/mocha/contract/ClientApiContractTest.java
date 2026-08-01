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
 * <p>TΔ11이 같은 규율로 {@code note-candidates.contract.json}을 보탰다 — 매칭 변경 시트가 도출한
 * {@code GET /api/notes/candidates?q=}의 응답 형태이고, 구현은 TΔ7이다.
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
    private static final String NOTE_CANDIDATES_CONTRACT = "/contract/note-candidates.contract.json";
    private static final String AGENT_CANCEL_CONTRACT = "/contract/agent-cancel.contract.json";

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

    @Test
    @DisplayName("TΔ11: 후보 1건 = {note_id, coffee_name, roastery, latest_date} — 로스터리가 동명 후보를 가른다")
    void candidateCarriesTheFieldsThatDistinguishSameNamedCoffees() throws IOException {
        JsonNode candidates = load(NOTE_CANDIDATES_CONTRACT).get("response").get("candidates");

        assertThat(candidates).isNotEmpty();
        candidates.forEach(candidate ->
                assertThat(fieldNames(candidate)).containsExactly("note_id", "coffee_name", "roastery", "latest_date"));
        // POLICY: 로스터리는 선택 필드가 아니다 — 싱글 오리진은 커피명이 산지·농장·품종에서 오므로
        //         이름이 같은 다른 커피가 흔하고, 그것을 가를 축이 로스터리뿐이다(사용자 확정 2026-08-01).
        assertThat(candidates)
                .as("동명 다른 로스터리가 계약 예시에 있어야 한다 — 시트가 실제로 마주하는 형태다")
                .anySatisfy(candidate -> assertThat(candidate.get("roastery").isString()).isTrue());
    }

    @Test
    @DisplayName("TΔ11: 로스터리·최근 시음일은 nullable — 모르는 노트, 엔트리 없는 노트가 있다")
    void candidateAllowsMissingRoasteryAndDate() throws IOException {
        JsonNode candidates = load(NOTE_CANDIDATES_CONTRACT).get("response").get("candidates");

        assertThat(candidates)
                .as("nullable 지점을 예시로 박지 않으면 클라이언트가 non-null을 가정한다")
                .anySatisfy(candidate -> {
                    assertThat(candidate.get("roastery").isNull()).isTrue();
                    assertThat(candidate.get("latest_date").isNull()).isTrue();
                });
        candidates.forEach(candidate -> {
            assertThat(candidate.get("note_id").isIntegralNumber()).isTrue();
            assertThat(candidate.get("coffee_name").isString()).isTrue();
        });
    }

    @Test
    @DisplayName("TΔ11: 빈 q도 유효한 요청이다 — 커피명이 아직 없어도 시트가 전체를 보여준다")
    void candidateQueryMayBeBlank() throws IOException {
        JsonNode contract = load(NOTE_CANDIDATES_CONTRACT);

        assertThat(fieldNames(contract.get("request_query"))).containsExactly("q");
        assertThat(fieldNames(contract.get("request_query_blank"))).containsExactly("q");
        assertThat(contract.get("request_query_blank").get("q").stringValue()).isEmpty();
        // 후보 없음은 오류가 아니라 빈 목록이다 — 그때 사용자의 다음 행동이 [새 노트로 등록]이다.
        assertThat(contract.get("response_empty").get("candidates")).isEmpty();
    }

    @Test
    @DisplayName("TΔ7: 정렬 축은 커피명 → 로스터리 → 최근 시음일 내림차순이고, 예시가 그 순서로 있다")
    void candidatesAreSortedByTheDeclaredAxes() throws IOException {
        JsonNode contract = load(NOTE_CANDIDATES_CONTRACT);

        // POLICY: 관련도 순위를 두지 않는다(사용자 확정 2026-08-01, TΔ7) — 이 스키마에 그것을 표현할
        //         수단이 없고(임베딩·pg_trgm·tsvector 부재), SQL로 흉내 내면 이름만 관련도이고 실질은
        //         문자열 매칭인 코드가 남아 임베딩 도입 시 걷어내야 한다.
        //         재론 트리거: 관련도가 실제로 필요해지면 단계를 늘리지 말고 임베딩으로 간다(루트 §4).
        assertThat(contract.get("sort_axes").valueStream().map(JsonNode::stringValue))
                .containsExactly("coffee_name", "roastery", "latest_date desc");

        // 예시가 규칙을 어기면 계약이 두 가지를 말하게 된다 — 시트를 만드는 쪽은 예시를 먼저 본다.
        List<JsonNode> candidates = contract.get("response").get("candidates").valueStream().toList();
        for (int i = 1; i < candidates.size(); i++) {
            assertThat(sortKey(candidates.get(i - 1)))
                    .as("계약 예시 %d번이 선언한 정렬 축을 어긴다", i)
                    .isLessThan(sortKey(candidates.get(i)));
        }
        // 축 ②·③이 실물로 박혀 있어야 한다 — 동명 다른 로스터리는 싱글 오리진에서 흔한 형태이고(TΔ11),
        // 그 쌍이 예시에 없으면 로스터리 축은 문장으로만 존재한다.
        List<String> names = candidates.stream().map(node -> node.get("coffee_name").stringValue()).toList();
        assertThat(names.stream().distinct().count())
                .as("동명 후보 쌍이 예시에 있어야 축 ②가 실물로 드러난다 — 이름들: %s", names)
                .isLessThan(names.size());
    }

    /**
     * 계약 예시의 정렬 키 — {@code Aliases.normalize}(소문자 + 공백 제거)를 흉내 내고 날짜는 내림차순이라
     * 보수를 취한다. 표본이 한글로만 돼 있어 Java 문자열 순서와 Postgres collation이 갈리지 않는다.
     */
    private static String sortKey(JsonNode candidate) {
        String coffeeName = normalize(candidate.get("coffee_name").stringValue());
        String roastery = candidate.get("roastery").isNull() ? "￿"
                : normalize(candidate.get("roastery").stringValue());
        // null latest_date도 뒤로(NULLS LAST) — 내림차순이라 "작을수록 뒤"의 방향이 뒤집힌다.
        String date = candidate.get("latest_date").isNull() ? ""
                : String.valueOf(99999999 - Integer.parseInt(
                        candidate.get("latest_date").stringValue().replace("-", "")));
        return coffeeName + ' ' + roastery + ' ' + date;
    }

    private static String normalize(String value) {
        return value.toLowerCase().replaceAll("\\s+", "");
    }

    @Test
    @DisplayName("TΔ6b: 취소 통지는 본문이 없다 — 무엇을 취소하는지는 서버가 안다(사용자당 트랜스크립트 1건)")
    void cancelCarriesNoBodyInEitherDirection() throws IOException {
        JsonNode contract = load(AGENT_CANCEL_CONTRACT);

        // '본문 없음'이 곧 계약이다 — 본문이 생기면 이 단언이 레드가 되어 계약 파일부터 고치게 한다.
        assertThat(contract.get("request").isNull()).isTrue();
        assertThat(contract.get("response").isNull()).isTrue();
        assertThat(contract.get("response_status").intValue()).isEqualTo(204);
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
