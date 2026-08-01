package com.devwuu.mocha.contract;

import com.devwuu.mocha.domain.Rating;
import com.devwuu.mocha.domain.Source;
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
import java.util.Arrays;
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
 * <p>TΔ12가 {@code note-list.contract.json}을 보탠다 — 갤러리 화면이 도출한 {@code GET /api/notes}의
 * 필터·정렬·페이징 형태이고, <b>구현은 TΔ5a</b>다. S2 슬라이스에서 화면이 API보다 먼저인 것도 같은
 * 규율이고, 여기 박힌 것이 그 사이 mock({@code frontend/src/api/mock.ts})과 갈리지 않게 하는 닻이다.
 *
 * <p>TΔ13a가 {@code note-detail.contract.json}을 보탠다 — 상세 화면이 도출한 {@code GET /api/notes/&#123;id&#125;}의
 * 응답 형태이고 구현은 같은 TΔ5a다. 목록이 <i>고르는</i> 화면의 납작한 사영이었다면 이쪽은 <i>읽는</i> 화면의
 * 전문이라, 두 계약이 <b>같은 노트를 다른 깊이로</b> 말한다. 화면이 실제로 보여주는 것만 실린 결과가 세
 * 절단이고({@code aliases}·감사 컬럼·{@code my_taste_original}) 아래 테스트가 그것을 박는다.
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
    private static final String PHOTO_UPLOAD_CONTRACT = "/contract/photo-upload.contract.json";
    private static final String NOTE_LIST_CONTRACT = "/contract/note-list.contract.json";
    private static final String NOTE_DETAIL_CONTRACT = "/contract/note-detail.contract.json";

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
    @DisplayName("TΔ10: 턴 요청 본문 = {utterance, draft, photos}, draft는 첫 턴에 null — 폼이 없으면 보낼 것이 없다")
    void turnRequestCarriesUtteranceAndOptionalDraft() throws IOException {
        JsonNode contract = load(AGENT_TURN_CONTRACT);

        assertThat(fieldNames(contract.get("request"))).containsExactly("utterance", "draft", "photos");
        assertThat(fieldNames(contract.get("request_first_turn"))).containsExactly("utterance", "draft", "photos");
        assertThat(contract.get("request_first_turn").get("draft").isNull()).isTrue();
    }

    @Test
    @DisplayName("TΔ8a: 턴에 실리는 사진은 바이트가 아니라 이름이다 — 업로드 응답이 준 값을 그대로 되싣는다(D-11)")
    void turnCarriesPhotoNamesNotBytes() throws IOException {
        JsonNode turn = load(AGENT_TURN_CONTRACT);
        List<String> uploaded = load(PHOTO_UPLOAD_CONTRACT).get("response").get("photos").valueStream()
                .map(photo -> photo.get("name").stringValue()).toList();

        JsonNode withPhotos = turn.get("request_with_photos");
        assertThat(fieldNames(withPhotos)).containsExactly("utterance", "draft", "photos");
        // 두 계약이 같은 값을 주고받는지가 핵심이다 — 여기가 어긋나면 클라이언트가 서버에 없는 이름을 싣는다.
        assertThat(withPhotos.get("photos").valueStream().map(JsonNode::stringValue))
                .containsExactlyElementsOf(uploaded);
        withPhotos.get("photos").forEach(name ->
                assertThat(name.isString()).as("사진은 이름으로 실린다 — 바이트를 실으면 턴이 multipart가 된다").isTrue());
    }

    @Test
    @DisplayName("TΔ8a: 사진만 첨부한 턴은 utterance가 빈 문자열이다 — 재료가 사진에서 나오는 유효한 턴이다")
    void photosOnlyTurnHasBlankUtterance() throws IOException {
        JsonNode request = load(AGENT_TURN_CONTRACT).get("request_photos_only");

        assertThat(request.get("utterance").stringValue()).isEmpty();
        assertThat(request.get("photos")).isNotEmpty();
    }

    @Test
    @DisplayName("TΔ8a: 업로드 응답은 스테이징 파일명뿐이다 — 경로도 URL도 주지 않는다(V-4의 정신)")
    void uploadResponseCarriesStagedNamesOnly() throws IOException {
        JsonNode contract = load(PHOTO_UPLOAD_CONTRACT);

        assertThat(fieldNames(contract.get("response"))).containsExactly("photos");
        assertThat(contract.get("response").get("photos")).isNotEmpty();
        contract.get("response").get("photos").forEach(photo -> {
            assertThat(fieldNames(photo)).containsExactly("name");
            assertThat(photo.get("name").isString()).isTrue();
        });
        // POLICY: 수용 포맷은 JPEG/PNG뿐이고, 한 장이라도 거부되면 아무것도 스테이징되지 않는다
        //         (ref: changes/0029 tasks.md TΔ8a — 계약에 부분 성공을 표현할 자리가 없다).
        assertThat(contract.get("accepted_formats").valueStream().map(JsonNode::stringValue))
                .containsExactly("image/jpeg", "image/png");
        assertThat(contract.get("rejected_status").intValue()).isEqualTo(400);
        assertThat(contract.get("request_content_type").stringValue()).isEqualTo("multipart/form-data");
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
    @DisplayName("TΔ12: 그리드 한 칸 = {note_id, coffee_name, roastery, latest_date, thumbnail_url} — 상세의 3단 중첩을 쓰지 않는다")
    void galleryItemIsAFlatProjection() throws IOException {
        JsonNode notes = load(NOTE_LIST_CONTRACT).get("response").get("notes");

        assertThat(notes).isNotEmpty();
        notes.forEach(note -> {
            assertThat(fieldNames(note))
                    .containsExactly("note_id", "coffee_name", "roastery", "latest_date", "thumbnail_url");
            assertThat(note.get("note_id").isIntegralNumber()).isTrue();
            assertThat(note.get("coffee_name").isString()).isTrue();
        });
        // 갤러리는 노트를 *고르는* 화면이라 entries·brews를 한 줄도 쓰지 않는다 — 실으면 자식 질의가
        // 노트마다 따라오고 결과의 대부분이 버려진다(TΔ7 NoteCandidate와 같은 판단).
        assertThat(keysAnywhere(load(NOTE_LIST_CONTRACT)))
                .as("목록 계약에 3단 중첩이 실려 나간다")
                .doesNotContain("entries", "brews", "recipe", "tasting", "aliases");
    }

    @Test
    @DisplayName("TΔ12: 로스터리·최근 시음일·썸네일은 모두 nullable — 사진 없이 발화만으로 기록한 노트가 정상이다")
    void galleryItemAllowsMissingRoasteryDateAndPhoto() throws IOException {
        JsonNode notes = load(NOTE_LIST_CONTRACT).get("response").get("notes");

        // nullable 지점을 예시로 박지 않으면 클라이언트가 non-null을 가정하고 그리드가 통째로 깨진다.
        assertThat(notes).anySatisfy(note -> assertThat(note.get("roastery").isNull()).isTrue());
        assertThat(notes).anySatisfy(note -> assertThat(note.get("latest_date").isNull()).isTrue());
        assertThat(notes)
                .as("사진 없는 노트가 예시에 있어야 한다 — 그때 시안의 사선 패턴이 칸을 채운다")
                .anySatisfy(note -> assertThat(note.get("thumbnail_url").isNull()).isTrue());
    }

    @Test
    @DisplayName("TΔ12: 썸네일은 서버가 만든 완성 URL이다 — 클라이언트가 아카이브 경로 규칙을 알지 않는다")
    void thumbnailIsAServerBuiltUrl() throws IOException {
        JsonNode contract = load(NOTE_LIST_CONTRACT);
        String prefix = contract.get("thumbnail_url_prefix").stringValue();

        assertThat(prefix).isEqualTo("/api/photos/");
        // POLICY: 폴더 접미는 생성 시점 스냅샷이라(ADR-75) 클라이언트가 재계산할 수 있는 값이 아니다.
        //         V-4 상대 경로(photos/…)를 프론트가 조립하면 같은 규칙이 양쪽에 이중화된다.
        contract.get("response").get("notes").forEach(note -> {
            JsonNode url = note.get("thumbnail_url");
            if (!url.isNull()) {
                assertThat(url.stringValue()).startsWith(prefix);
            }
        });
    }

    @Test
    @DisplayName("TΔ12: 필터 4축 — 로스터리·가공방식·평가는 다중(같은 축 OR), 원산지·검색어는 단일(축 간 AND)")
    void filterAxesAreFourAndCombineOrWithinAndBetween() throws IOException {
        JsonNode contract = load(NOTE_LIST_CONTRACT);

        assertThat(contract.get("multi_value_axes").valueStream().map(JsonNode::stringValue))
                .containsExactly("roastery", "process", "rating");
        assertThat(contract.get("single_value_axes").valueStream().map(JsonNode::stringValue))
                .containsExactly("q", "origin");
        assertThat(contract.get("axis_combination").get("within_axis").stringValue()).isEqualTo("or");
        assertThat(contract.get("axis_combination").get("between_axes").stringValue()).isEqualTo("and");

        // 요청 예시가 선언한 다중성을 따라야 한다 — 계약이 두 가지를 말하면 구현하는 쪽은 예시를 먼저 본다.
        JsonNode query = contract.get("request_query");
        assertThat(fieldNames(query)).containsExactly("q", "roastery", "process", "origin", "rating", "cursor");
        contract.get("multi_value_axes").forEach(axis ->
                assertThat(query.get(axis.stringValue()).isArray())
                        .as("다중 축 %s 가 예시에서 배열이 아니다", axis.stringValue()).isTrue());
        contract.get("single_value_axes").forEach(axis ->
                assertThat(query.get(axis.stringValue()).isString())
                        .as("단일 축 %s 가 예시에서 문자열이 아니다", axis.stringValue()).isTrue());
        // POLICY: 날짜 범위 축은 두지 않는다 — 필요가 관측되면 확장한다(open-questions.md 검색 절).
        assertThat(fieldNames(query)).doesNotContain("from", "to", "date", "sort");
    }

    @Test
    @DisplayName("TΔ12: 평가 축의 값은 도메인 4범주와 같다 — 칩이 만든 값이 그대로 질의가 된다(V-1)")
    void ratingFilterUsesTheDomainCategories() throws IOException {
        List<String> filterable = load(NOTE_LIST_CONTRACT).get("request_query").get("rating")
                .valueStream().map(JsonNode::stringValue).toList();

        assertThat(Arrays.stream(Rating.values()).map(Rating::label))
                .as("계약의 평가 값이 도메인 enum 밖이면 서버가 거부한다")
                .containsAll(filterable);
    }

    @Test
    @DisplayName("TΔ12: 칩 선택지는 응답에 동봉된다 — 로스터리·가공방식만, 평가·원산지는 아니다")
    void facetsCoverOnlyTheAxesThatMustBeEnumerated() throws IOException {
        JsonNode contract = load(NOTE_LIST_CONTRACT);
        JsonNode facets = contract.get("response").get("facets");

        assertThat(contract.get("faceted_axes").valueStream().map(JsonNode::stringValue))
                .containsExactly("roastery", "process");
        // 평가는 고정 4범주(V-1)라 클라이언트 상수가 소유하고, 원산지는 자유 텍스트라 열거할 집합이 없다.
        assertThat(fieldNames(facets)).containsExactly("roastery", "process");

        // 목록에 보이는 값을 필터에서 고를 수 없으면 안 된다 — facet이 페이지가 아니라 저장분 전체를
        // 훑는다는 것의 최소 표현이다.
        List<String> selectable = facets.get("roastery").valueStream().map(JsonNode::stringValue).toList();
        contract.get("response").get("notes").forEach(note -> {
            JsonNode roastery = note.get("roastery");
            if (!roastery.isNull()) {
                assertThat(selectable)
                        .as("목록에 보이는 로스터리 %s 를 필터에서 고를 수 없다", roastery.stringValue())
                        .contains(roastery.stringValue());
            }
        });
    }

    @Test
    @DisplayName("TΔ12: 페이징은 불투명 커서다 — 마지막 페이지의 next_cursor가 null이고 그것이 '더 없다'의 유일한 신호다")
    void pagingIsAnOpaqueCursor() throws IOException {
        JsonNode contract = load(NOTE_LIST_CONTRACT);

        // 클라이언트는 커서를 만들지 않는다 — 받은 값을 그대로 되싣는다. 그래야 정렬 축이 바뀌어도
        // 클라이언트가 따라 바뀌지 않는다(사용자 확정 2026-08-01 — 무한 스크롤).
        String issued = contract.get("response").get("next_cursor").stringValue();
        assertThat(contract.get("request_query_next_page").get("cursor").stringValue()).isEqualTo(issued);
        assertThat(contract.get("request_query_blank").get("cursor").isNull())
                .as("첫 페이지는 커서 없이 요청한다").isTrue();

        assertThat(contract.get("response_last_page").get("next_cursor").isNull()).isTrue();
        // page·total_pages·offset이 없는 것 자체가 계약이다 — 생기면 화면이 페이지네이션으로 돌아간다.
        assertThat(fieldNames(contract.get("response")))
                .containsExactly("notes", "next_cursor", "total", "facets");
        // total은 필터가 적용된 총 건수다 — 헤더의 "N편의 기록"이 그 값이다.
        assertThat(contract.get("response").get("total").isIntegralNumber()).isTrue();
        // 결과 없음은 오류가 아니라 빈 목록이다(TΔ11 후보 시트와 같은 판단).
        assertThat(contract.get("response_empty").get("notes")).isEmpty();
        assertThat(contract.get("response_empty").get("total").intValue()).isZero();
    }

    @Test
    @DisplayName("TΔ12: 정렬은 최근 시음일 내림차순(NULLS LAST) → note_id 내림차순이고, 예시가 그 순서로 있다")
    void galleryIsSortedByRecencyThenId() throws IOException {
        JsonNode contract = load(NOTE_LIST_CONTRACT);

        // POLICY: 정렬 선택기를 두지 않는다 — 델타 확정 사항에 정렬 축이 없고, 축을 늘리는 것은
        //         spec에 없는 동작을 더하는 일이다(루트 §3). 시안의 `최신순 ▾`는 이식하지 않았다.
        assertThat(contract.get("sort_axes").valueStream().map(JsonNode::stringValue))
                .containsExactly("latest_date desc nulls last", "note_id desc");

        List<JsonNode> notes = contract.get("response").get("notes").valueStream().toList();
        for (int i = 1; i < notes.size(); i++) {
            assertThat(listSortKey(notes.get(i - 1)))
                    .as("계약 예시 %d번이 선언한 정렬 축을 어긴다", i)
                    .isLessThan(listSortKey(notes.get(i)));
        }
        // 축 ②가 실물로 박혀 있어야 한다 — 같은 날 마신 노트가 둘이면 id가 순서를 가른다.
        List<String> dates = notes.stream()
                .map(note -> note.get("latest_date").isNull() ? "" : note.get("latest_date").stringValue()).toList();
        assertThat(dates.stream().distinct().count())
                .as("같은 날짜 쌍이 예시에 있어야 축 ②가 실물로 드러난다 — 날짜들: %s", dates)
                .isLessThan(dates.size());
    }

    /** 목록 정렬 키 — 날짜 내림차순(null은 뒤), 그다음 note_id 내림차순. 둘 다 내림이라 보수를 취한다. */
    private static String listSortKey(JsonNode note) {
        String date = note.get("latest_date").isNull() ? "99999999"
                : String.valueOf(99999999 - Integer.parseInt(note.get("latest_date").stringValue().replace("-", "")));
        return date + ' ' + String.format("%09d", 999999999 - note.get("note_id").intValue());
    }

    @Test
    @DisplayName("TΔ13a: 상세 = 노트 전문 + 날짜별 사진 — 사진이 노트가 아니라 엔트리에 붙는다(참조 축 (note_id, tasted_on))")
    void detailCarriesTheWholeNoteWithPhotosUnderEachEntry() throws IOException {
        JsonNode contract = load(NOTE_DETAIL_CONTRACT);

        for (String example : List.of("response", "response_minimal", "response_no_entries")) {
            JsonNode detail = contract.get(example);
            assertThat(fieldNames(detail))
                    .as("상세 예시 %s", example)
                    .containsExactly("note_id", "coffee_name", "roastery", "beans", "roast_level",
                            "official_notes", "sources", "entries");
            detail.get("entries").forEach(entry ->
                    // POLICY: 사진은 note_photo의 참조 축을 그대로 따라 엔트리에 붙는다(TΔ8b, 사용자 확정
                    //         2026-08-01). 노트 레벨 배열을 따로 두면 히어로와 날짜 섹션이 같은 사진을 두
                    //         자리에서 실어 나른다 — 화면은 이 한 벌에서 둘 다 만든다.
                    assertThat(fieldNames(entry)).containsExactly("date", "brews", "photos"));
        }
        assertThat(contract.get("missing_status").intValue())
                .as("없는 id는 빈 노트가 아니라 404다 — 삭제된 노트를 텅 빈 상세로 그리지 않는다").isEqualTo(404);
    }

    @Test
    @DisplayName("TΔ13a: 저장된 노트의 커피명은 non-null이고 출처를 달고 온다 — draft와 갈리는 지점이다(V-9·V-5)")
    void detailKeepsSourcesAndAlwaysHasACoffeeName() throws IOException {
        JsonNode contract = load(NOTE_DETAIL_CONTRACT);
        List<String> sourceValues = Arrays.stream(Source.values()).map(Source::json).toList();

        for (String example : List.of("response", "response_minimal", "response_no_entries")) {
            JsonNode coffeeName = contract.get(example).get("coffee_name");
            // 작성 중인 노트는 커피명이 아직 없을 수 있지만(draft는 nullable) 저장된 노트에는 반드시 있다.
            assertThat(coffeeName.isNull()).as("상세 예시 %s 의 커피명이 null이다", example).isFalse();
            assertThat(fieldNames(coffeeName)).containsExactly("value", "source");
            // V-5: coffee_name은 검색 보강 대상이 아니다 — 정체성이자 검색 앵커라 source ∈ {user, photo}.
            assertThat(coffeeName.get("source").stringValue()).isIn("user", "photo");
        }
        // POLICY: 출처를 상세에도 싣는다(사용자 확정 2026-08-01) — ① 화면이 "이 값은 사진에서 읽은 것"을
        //         캡처 폼과 같은 어휘로 보여주고 ② TΔ13b 수정 폼이 이 응답을 그대로 딛는다. 버리면
        //         로스터리만 고쳐도 나머지 필드의 출처가 user로 덮인다.
        JsonNode full = contract.get("response");
        for (String field : List.of("roastery", "roast_level", "official_notes")) {
            assertThat(fieldNames(full.get(field)))
                    .as("%s 이 출처 표시 필드가 아니다", field).containsExactly("value", "source");
            assertThat(sourceValues)
                    .as("%s 의 출처가 도메인 Source 밖이면 서버가 만들 수 없는 값이다", field)
                    .contains(full.get(field).get("source").stringValue());
        }
        full.get("beans").forEach(bean -> assertThat(fieldNames(bean)).containsExactly("description", "process"));
    }

    @Test
    @DisplayName("TΔ13a: 상세도 aliases·감사 컬럼·감상 원문을 싣지 않는다 — 화면이 쓰지 않는 값은 계약에 없다")
    void detailDropsInternalAuditAndUnrenderedFields() throws IOException {
        List<String> keys = keysAnywhere(load(NOTE_DETAIL_CONTRACT));

        // V-13 내부 전용 별칭은 목록·턴 계약과 같은 이유로 없다.
        assertThat(keys).doesNotContain("aliases");
        // V-11이 원문을 함께 저장하게 하지만 같은 규칙의 뒷문장이 "렌더는 my_taste만 사용"이고, 상세도 렌더다.
        assertThat(keys)
                .as("감상 원문이 계약에 실려 나간다 — 화면이 보여주지 않는 값이다")
                .doesNotContain("my_taste_original");
        // 감사 컬럼은 화면에 자리가 없다(TΔ10의 aliases 절단과 같은 판단).
        assertThat(keys).doesNotContain("created_at", "updated_at");
    }

    @Test
    @DisplayName("TΔ13a: 상세 사진과 갤러리 썸네일은 같은 접두의 완성 URL이다 — 같은 자원의 두 표면이다")
    void detailPhotosShareTheGalleryUrlPrefix() throws IOException {
        JsonNode contract = load(NOTE_DETAIL_CONTRACT);
        String prefix = contract.get("photo_url_prefix").stringValue();

        // 접두가 갈리면 갤러리에서 보던 사진과 상세의 히어로가 다른 규칙으로 만들어진다 — 히어로는
        // 목록의 thumbnail_url이 고르는 바로 그 사진(가장 최근 날짜의 첫 장)이다.
        assertThat(prefix).isEqualTo(load(NOTE_LIST_CONTRACT).get("thumbnail_url_prefix").stringValue());

        List<String> urls = new ArrayList<>();
        contract.get("response").get("entries").forEach(entry -> entry.get("photos").forEach(photo -> {
            assertThat(fieldNames(photo)).containsExactly("url");
            urls.add(photo.get("url").stringValue());
        }));
        assertThat(urls).as("사진 있는 엔트리가 예시에 있어야 히어로 경로가 계약에 드러난다").isNotEmpty();
        urls.forEach(url -> assertThat(url).startsWith(prefix));
    }

    @Test
    @DisplayName("TΔ13a: 엔트리는 날짜 오름차순이고 날짜가 유일 키다(V-3) — 히어로는 마지막 엔트리에서 나온다")
    void detailEntriesAreDateAscendingAndUnique() throws IOException {
        JsonNode contract = load(NOTE_DETAIL_CONTRACT);

        // POLICY: 목록은 최근순인데 상세는 오름차순이다 — 목록은 *고르는* 자리라 방금 마신 것이 위여야 하고,
        //         상세는 *읽는* 자리라 "처음 마셨을 때 → 지금"이 노트의 독서 방향이다. 최신 회차는 상단
        //         히어로 사진으로 이미 위에 있다(도메인의 "날짜 오름차순 유지"와도 같은 방향).
        assertThat(contract.get("entry_order").stringValue()).isEqualTo("date asc");

        List<String> dates = contract.get("response").get("entries").valueStream()
                .map(entry -> entry.get("date").stringValue()).toList();
        assertThat(dates).as("계약 예시가 선언한 순서를 어긴다 — 화면은 예시를 먼저 본다").isSorted();
        assertThat(dates.stream().distinct().count()).isEqualTo(dates.size());
        assertThat(dates).as("날짜가 둘 이상 있어야 정렬 축이 실물로 드러난다").hasSizeGreaterThan(1);
    }

    @Test
    @DisplayName("TΔ13a: 회차는 레시피·감상 중 최소 하나가 있고 평가는 도메인 4범주다(V-15·V-1)")
    void detailBrewsCarryAtLeastOneSideAndDomainRatings() throws IOException {
        List<String> ratings = Arrays.stream(Rating.values()).map(Rating::label).toList();

        load(NOTE_DETAIL_CONTRACT).get("response").get("entries").forEach(entry -> entry.get("brews").forEach(brew -> {
            assertThat(fieldNames(brew)).containsExactly("recipe", "tasting");
            assertThat(brew.get("recipe").isNull() && brew.get("tasting").isNull())
                    .as("둘 다 null인 회차는 저장되지 않는다(V-15) — 예시에 있으면 화면이 없는 상태를 그리게 된다")
                    .isFalse();
            JsonNode tasting = brew.get("tasting");
            if (!tasting.isNull()) {
                // 원문 절단의 결과 — 저장된 감상은 {my_taste, rating} 둘뿐이다.
                assertThat(fieldNames(tasting)).containsExactly("my_taste", "rating");
                if (!tasting.get("rating").isNull()) {
                    assertThat(ratings).contains(tasting.get("rating").stringValue());
                }
            }
        }));
    }

    @Test
    @DisplayName("TΔ13a: nullable 지점이 예시에 있다 — 로스터리 없는 노트, 사진 없는 날, 엔트리 없는 노트")
    void detailNullablePointsAreInTheExamples() throws IOException {
        JsonNode contract = load(NOTE_DETAIL_CONTRACT);

        // 최소형 — 저장된 노트의 메타는 대부분 비어 있을 수 있다(발화 한 줄로 남긴 기록).
        JsonNode minimal = contract.get("response_minimal");
        assertThat(minimal.get("beans")).isEmpty();
        assertThat(minimal.get("roast_level").isNull()).isTrue();
        assertThat(minimal.get("official_notes").isNull()).isTrue();
        assertThat(minimal.get("sources")).isEmpty();

        // 엔트리 없는 노트 — 정상 상태다(저장 후 엔트리가 지워진 노트). 화면은 빈 목록을 그린다.
        JsonNode noEntries = contract.get("response_no_entries");
        assertThat(noEntries.get("roastery").isNull())
                .as("로스터리를 모르는 노트가 예시에 있어야 한다").isTrue();
        assertThat(noEntries.get("entries")).isEmpty();

        // 같은 노트 안에서 사진 있는 날과 없는 날이 섞인다 — 히어로 선택이 "첫 엔트리"가 아닌 이유다.
        assertThat(contract.get("response").get("entries"))
                .anySatisfy(entry -> assertThat(entry.get("photos")).isEmpty())
                .anySatisfy(entry -> assertThat(entry.get("photos")).isNotEmpty());
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
