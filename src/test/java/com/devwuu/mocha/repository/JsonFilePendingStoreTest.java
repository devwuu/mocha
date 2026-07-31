package com.devwuu.mocha.repository;

import com.devwuu.mocha.domain.Bean;
import com.devwuu.mocha.domain.Brew;
import com.devwuu.mocha.domain.Entry;
import com.devwuu.mocha.domain.MatchInfo;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.PendingNote;
import com.devwuu.mocha.domain.Rating;
import com.devwuu.mocha.domain.Source;
import com.devwuu.mocha.domain.Sourced;
import com.devwuu.mocha.domain.Tasting;
import com.devwuu.mocha.json.MochaObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T1-3: JsonFilePendingStore — 임시 디렉토리 실제 파일 I/O로 검증(CLAUDE.md §5.2).
 * <p>재시작 생존(AC-7/NFR-2), TTL 만료 처리(V-7).
 */
class JsonFilePendingStoreTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Instant NOW = Instant.parse("2026-07-10T00:30:00Z");
    private static final Clock FIXED = Clock.fixed(NOW, SEOUL);
    private static final Duration TTL = Duration.ofHours(24);
    private static final String USER = "U123";

    private static PendingNote sampleDraft(OffsetDateTime createdAt) {
        Note draft = new Note(
                1L,
                new Sourced<>("커피베라 예가체프 G1", Source.USER),
                new Sourced<>("커피베라", Source.USER),
                List.of(new Bean(new Sourced<>("에티오피아 예가체프", Source.SEARCH), new Sourced<>("워시드", Source.SEARCH))),
                new Sourced<>(null, Source.SEARCH),
                new Sourced<>(List.of("자스민", "베르가못"), Source.SEARCH),
                List.of("https://example.com/coffeevera"),
                List.of(new Entry(LocalDate.of(2026, 7, 10),
                        List.of(new Brew(null, new Tasting("새콤하고 좋았다", null, Rating.GOOD))), createdAt)),
                createdAt,
                createdAt
        );
        return new PendingNote(draft, MatchInfo.newNote(), "1720570200.000100", createdAt);
    }

    @Test
    @DisplayName("AC-7/NFR-2: 저장 후 새 인스턴스로 로드해 동일 draft 복원")
    void persistsAndReloadsAcrossInstances() {
        OffsetDateTime createdAt = OffsetDateTime.now(FIXED);
        PendingNote pending = sampleDraft(createdAt);

        new JsonFilePendingStore(dataDir, MochaObjectMapper.create(), TTL, FIXED).put(USER, pending);

        // 프로세스 재시작을 흉내내 별개 인스턴스로 조회.
        PendingStore reopened = new JsonFilePendingStore(dataDir, MochaObjectMapper.create(), TTL, FIXED);
        assertThat(reopened.get(USER)).contains(pending);
    }

    @Test
    @DisplayName("V-7: TTL 초과 pending은 get에서 만료 처리(빈 Optional)")
    void expiredPendingIsNotReturned() {
        // 25시간 전 생성 → TTL(24h) 초과.
        OffsetDateTime stale = OffsetDateTime.now(FIXED).minusHours(25);
        JsonFilePendingStore store = new JsonFilePendingStore(dataDir, MochaObjectMapper.create(), TTL, FIXED);
        store.put(USER, sampleDraft(stale));

        assertThat(store.get(USER)).isEmpty();
    }

    @Test
    @DisplayName("V-7 경계: TTL 이내 pending은 정상 조회")
    void freshPendingIsReturned() {
        OffsetDateTime recent = OffsetDateTime.now(FIXED).minusHours(23);
        JsonFilePendingStore store = new JsonFilePendingStore(dataDir, MochaObjectMapper.create(), TTL, FIXED);
        PendingNote pending = sampleDraft(recent);
        store.put(USER, pending);

        assertThat(store.get(USER)).contains(pending);
    }

    // --- 0012 TΔ1: edit 모드 pending (FR-21) ---

    @Test
    @DisplayName("0012-TΔ1/AC-Δ5: edit 모드 pending은 재시작 후에도 mode·target이 보존된다")
    void editModePendingSurvivesRestart() {
        OffsetDateTime createdAt = OffsetDateTime.now(FIXED);
        PendingNote record = sampleDraft(createdAt);
        PendingNote editPending = new PendingNote(
                PendingNote.Mode.EDIT,
                record.draft(),
                new PendingNote.EditTarget(1L, LocalDate.of(2026, 7, 9)),
                null,
                record.previewTs(),
                createdAt
        );

        new JsonFilePendingStore(dataDir, MochaObjectMapper.create(), TTL, FIXED).put(USER, editPending);

        // 프로세스 재시작을 흉내내 별개 인스턴스로 조회 — 수정 draft 생존(AC-40).
        PendingStore reopened = new JsonFilePendingStore(dataDir, MochaObjectMapper.create(), TTL, FIXED);
        assertThat(reopened.get(USER)).contains(editPending);
        assertThat(reopened.get(USER).orElseThrow().mode()).isEqualTo(PendingNote.Mode.EDIT);
        assertThat(reopened.get(USER).orElseThrow().target())
                .isEqualTo(new PendingNote.EditTarget(1L, LocalDate.of(2026, 7, 9)));
    }

    // (0012 이전 pending.json 하위 호환 테스트는 제거 — 기존 데이터는 배포 전 수동 삭제로 결정, delta 비범위.)

    // --- 0025 TΔ2b-1: 로드 무결성 — 훼손 pending은 경고 로그 + 파일 정리 후 empty (ADR-66 ②, AC-Δ2 ②) ---

    @Test
    @DisplayName("0025-TΔ2b-1/AC-Δ2②: 비JSON 바이트(파싱 실패)는 empty 반환 + 파일 삭제")
    void corruptNonJsonIsDiscarded() throws Exception {
        Files.writeString(pendingFile(), "this-is-not-json{{{");
        JsonFilePendingStore store = new JsonFilePendingStore(dataDir, MochaObjectMapper.create(), TTL, FIXED);

        assertThat(store.get(USER)).isEmpty();
        assertThat(pendingFile()).doesNotExist();
    }

    @Test
    @DisplayName("0025-TΔ2b-1/AC-Δ2②: mode 누락은 empty 반환 + 파일 삭제")
    void corruptMissingModeIsDiscarded() throws Exception {
        writePendingWithout(sampleDraft(OffsetDateTime.now(FIXED)), "mode");
        JsonFilePendingStore store = new JsonFilePendingStore(dataDir, MochaObjectMapper.create(), TTL, FIXED);

        assertThat(store.get(USER)).isEmpty();
        assertThat(pendingFile()).doesNotExist();
    }

    @Test
    @DisplayName("0025-TΔ2b-1/AC-Δ2②: draft 누락은 empty 반환 + 파일 삭제")
    void corruptMissingDraftIsDiscarded() throws Exception {
        writePendingWithout(sampleDraft(OffsetDateTime.now(FIXED)), "draft");
        JsonFilePendingStore store = new JsonFilePendingStore(dataDir, MochaObjectMapper.create(), TTL, FIXED);

        assertThat(store.get(USER)).isEmpty();
        assertThat(pendingFile()).doesNotExist();
    }

    // (구 "draft.slug 공백" 케이스는 폐기 — id는 INSERT가 발급하므로 record 모드 신규 draft는 정상적으로
    //  식별자가 없다. 검사를 그대로 옮기면 신규 pending이 전부 훼손 판정된다(changes/0028 TΔ0b §4 E-5).)

    @Test
    @DisplayName("0025-TΔ2b-1/AC-Δ2②: draft 엔트리 0건은 empty 반환 + 파일 삭제")
    void corruptZeroEntriesIsDiscarded() throws Exception {
        writePendingTree(sampleDraft(OffsetDateTime.now(FIXED)),
                tree -> ((ObjectNode) tree.get("draft")).set("entries", tree.arrayNode()));
        JsonFilePendingStore store = new JsonFilePendingStore(dataDir, MochaObjectMapper.create(), TTL, FIXED);

        assertThat(store.get(USER)).isEmpty();
        assertThat(pendingFile()).doesNotExist();
    }

    @Test
    @DisplayName("0025-TΔ2b-1/ADR-66: draft.entries 필드 부재도 NPE 없이 empty로 수렴")
    void corruptNullEntriesIsDiscarded() throws Exception {
        // 도메인 생성자가 entries 부재를 빈 배열로 수렴시키고(V-3, CR25-10) 관문이 "엔트리 0건"으로 잡는다 —
        // 어느 층이 바뀌어도 이 경로가 NPE로 새지 않는지 가드한다.
        writePendingTree(sampleDraft(OffsetDateTime.now(FIXED)),
                tree -> ((ObjectNode) tree.get("draft")).remove("entries"));
        JsonFilePendingStore store = new JsonFilePendingStore(dataDir, MochaObjectMapper.create(), TTL, FIXED);

        assertThat(store.get(USER)).isEmpty();
        assertThat(pendingFile()).doesNotExist();
    }

    @Test
    @DisplayName("0025-TΔ2b-1/ADR-66: 최상위 JSON null도 NPE 없이 empty로 수렴 + 파일 삭제")
    void corruptTopLevelNullIsDiscarded() throws Exception {
        Files.writeString(pendingFile(), "null");
        JsonFilePendingStore store = new JsonFilePendingStore(dataDir, MochaObjectMapper.create(), TTL, FIXED);

        assertThat(store.get(USER)).isEmpty();
        assertThat(pendingFile()).doesNotExist();
    }

    @Test
    @DisplayName("0025-TΔ2b-1/AC-Δ2②: edit 모드인데 target 누락은 empty 반환 + 파일 삭제")
    void corruptEditWithoutTargetIsDiscarded() throws Exception {
        OffsetDateTime createdAt = OffsetDateTime.now(FIXED);
        PendingNote editPending = new PendingNote(
                PendingNote.Mode.EDIT,
                sampleDraft(createdAt).draft(),
                new PendingNote.EditTarget(1L, LocalDate.of(2026, 7, 9)),
                null, "1720570200.000100", createdAt);
        writePendingWithout(editPending, "target");
        JsonFilePendingStore store = new JsonFilePendingStore(dataDir, MochaObjectMapper.create(), TTL, FIXED);

        assertThat(store.get(USER)).isEmpty();
        assertThat(pendingFile()).doesNotExist();
    }

    @Test
    @DisplayName("0025-CR25-10/ADR-66: edit 모드 target.note_id 결손은 empty 반환 + 파일 삭제(게이트 NPE·모델 대면 null 차단)")
    void corruptEditWithMissingTargetNoteIdIsDiscarded() throws Exception {
        // note_id 결손 target이 통과하면 SinglePendingGate의 같은 대상 판정이 id 비교에서 NPE로 새고,
        // 거부 사유에는 리터럴 "null"이 노출된다(draft.coffee_name과 동일 부류).
        OffsetDateTime createdAt = OffsetDateTime.now(FIXED);
        PendingNote editPending = new PendingNote(
                PendingNote.Mode.EDIT,
                sampleDraft(createdAt).draft(),
                new PendingNote.EditTarget(1L, LocalDate.of(2026, 7, 9)),
                null, "1720570200.000100", createdAt);
        writePendingTree(editPending, tree -> ((ObjectNode) tree.get("target")).remove("note_id"));
        JsonFilePendingStore store = new JsonFilePendingStore(dataDir, MochaObjectMapper.create(), TTL, FIXED);

        assertThat(store.get(USER)).isEmpty();
        assertThat(pendingFile()).doesNotExist();
    }

    // --- 0025 TΔ2b-2 직후 리뷰(CR25-6): createdAt·coffee_name 무결성 + draft 정규화 (ADR-66 ②) ---

    @Test
    @DisplayName("0025-CR25-6/ADR-66: createdAt 누락은 isExpired NPE 대신 empty 반환 + 파일 삭제")
    void corruptMissingCreatedAtIsDiscarded() throws Exception {
        // createdAt=null이면 종전 isExpired의 Duration.between(null,…)이 NPE로 샜다 — 관문에서 막는다.
        writePendingWithout(sampleDraft(OffsetDateTime.now(FIXED)), "created_at");
        JsonFilePendingStore store = new JsonFilePendingStore(dataDir, MochaObjectMapper.create(), TTL, FIXED);

        assertThat(store.get(USER)).isEmpty();
        assertThat(pendingFile()).doesNotExist();
    }

    @Test
    @DisplayName("0025-CR25-6/ADR-66: draft.coffee_name 결손은 empty 반환 + 파일 삭제(모델 대면 null 차단)")
    void corruptBlankCoffeeNameIsDiscarded() throws Exception {
        writePendingTree(sampleDraft(OffsetDateTime.now(FIXED)),
                tree -> ((ObjectNode) tree.get("draft")).remove("coffee_name"));
        JsonFilePendingStore store = new JsonFilePendingStore(dataDir, MochaObjectMapper.create(), TTL, FIXED);

        assertThat(store.get(USER)).isEmpty();
        assertThat(pendingFile()).doesNotExist();
    }

    @Test
    @DisplayName("0025-CR25-6/ADR-66: 수기 편집 draft의 무효 beans 요소는 로드 시 드롭(노트 읽기와 동일 위생), 파일은 무변경")
    void corruptDraftBeanElementIsNormalizedOnLoad() throws Exception {
        // description 부재 bean 요소(V-14 위반)를 draft.beans에 혼입 — 로드 정규화로 드롭돼야 한다.
        writePendingTree(sampleDraft(OffsetDateTime.now(FIXED)), tree -> {
            ArrayNode beans = (ArrayNode) tree.get("draft").get("beans");
            beans.addObject().putNull("process"); // description 없는 무효 원두
        });
        JsonFilePendingStore store = new JsonFilePendingStore(dataDir, MochaObjectMapper.create(), TTL, FIXED);

        var loaded = store.get(USER);
        assertThat(loaded).isPresent();
        assertThat(loaded.orElseThrow().draft().beans())
                .as("V-14 위반 요소(무효 원두)는 드롭되고 정상 원두만 유지된다")
                .hasSize(1);
        assertThat(pendingFile()).exists(); // 로드 정규화는 파일을 다시 쓰지 않는다(메모리 정규화만)
    }

    // 유효 pending을 직렬화한 트리에서 특정 최상위 필드만 제거해 훼손 파일을 만든다(필드명 수기 실수 회피).
    private void writePendingWithout(PendingNote pending, String field) throws Exception {
        writePendingTree(pending, tree -> tree.remove(field));
    }

    // 유효 pending을 직렬화한 트리를 임의로 변형해 훼손 파일을 만든다.
    private void writePendingTree(PendingNote pending, Consumer<ObjectNode> mutator) throws Exception {
        var mapper = MochaObjectMapper.create();
        ObjectNode tree = (ObjectNode) mapper.readTree(mapper.writeValueAsBytes(pending));
        mutator.accept(tree);
        Files.write(pendingFile(), mapper.writeValueAsBytes(tree));
    }

    private Path pendingFile() {
        return dataDir.resolve("pending.json");
    }

    @Test
    @DisplayName("clear 후 조회는 빈 Optional / 부재 시에도 빈 Optional")
    void clearAndAbsent() {
        JsonFilePendingStore store = new JsonFilePendingStore(dataDir, MochaObjectMapper.create(), TTL, FIXED);
        assertThat(store.get(USER)).isEmpty();

        store.put(USER, sampleDraft(OffsetDateTime.now(FIXED)));
        assertThat(store.get(USER)).isPresent();

        store.clear(USER);
        assertThat(store.get(USER)).isEmpty();
    }

    @TempDir
    Path dataDir;
}
