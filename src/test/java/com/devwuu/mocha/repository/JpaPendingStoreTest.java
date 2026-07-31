package com.devwuu.mocha.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.devwuu.mocha.repository.entity.PendingNoteEntity;
import com.devwuu.mocha.repository.jpa.PendingNoteEntityRepository;
import com.devwuu.mocha.support.PostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Consumer;

/**
 * TΔ8 (changes/0028-rdb-storage) — {@link JpaPendingStore} (AC-Δ2, V-7, ADR-66).
 *
 * <p>구 {@code JsonFilePendingStoreTest}의 <b>계약 검증분</b>을 승계한다: 재시작 생존(AC-7/NFR-2) ·
 * TTL 만료(V-7) · 로드 무결성 관문(ADR-66 ②) · draft 위생. <b>파일 특성 검증분</b>(원자적 쓰기·
 * {@code .tmp} 필터)은 검증 대상이 소멸해 승계하지 않는다.
 *
 * <p><b>관문의 분담이 바뀐 자리를 함께 박는다</b>: {@code mode}·{@code created_at}·{@code draft} 컬럼의
 * 부재는 이제 스키마가 막으므로 애플리케이션 검사에서 빠졌다. 그 이관이 실제로 성립하는지를
 * {@link #schemaRejectsMissingMode()}·{@link #schemaRejectsMissingCreatedAt()}이 본다 — 검사만 지우고
 * 아무도 막지 않는 상태를 그린으로 넘기지 않기 위해서다.
 *
 * <p>컨텍스트를 비우는 이유는 앞선 저장소 테스트와 같다({@link #flushAndClear()}) — 비우지 않으면 조회가
 * identity map을 되돌려주고 <b>DB를 지나지 않은 채 그린</b>이 된다. pending은 노트와 달리 쓰기가
 * {@code save} 하나라 flush까지 pending으로 남으므로 clear 전에 flush를 먼저 건다.
 */
@Transactional
class JpaPendingStoreTest extends PostgresIntegrationTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Clock FIXED = Clock.fixed(Instant.parse("2026-07-10T00:30:00Z"), SEOUL);
    private static final Duration TTL = Duration.ofHours(24);
    private static final String USER = "U123";

    @Autowired
    EntityManager em;

    @Autowired
    PendingNoteEntityRepository pendings;

    private final ObjectMapper mapper = MochaObjectMapper.create();

    private JpaPendingStore store;

    @BeforeEach
    void setUp() {
        // 협력자 하나만 주입받아 직접 조립한다 — 롤백으로 격리하려면 트랜잭션을 테스트가 열어야 하고,
        // 시계도 고정해야 TTL 판정이 실행 시각에 흔들리지 않는다(빈은 프로덕션 Clock을 쓴다).
        store = new JpaPendingStore(pendings, mapper, TTL, FIXED);
    }

    @Test
    @DisplayName("AC-7/NFR-2: 저장 후 DB 왕복으로 동일 pending 복원 — draft는 JSONB, 나머지는 컬럼(ADR-73)")
    void persistsAndReloadsThroughDatabase() {
        PendingNote pending = sample(OffsetDateTime.now(FIXED));

        store.put(USER, pending);
        flushAndClear();

        // created_at 표기만 UTC로 눕는다(POLICY, TΔ4 승계) — 인스턴트는 불변이라 TTL 판정은 영향받지 않는다.
        assertThat(store.get(USER)).contains(utcCreatedAt(pending));
        PendingNote restored = store.get(USER).orElseThrow();
        assertThat(restored.createdAt().toInstant()).isEqualTo(pending.createdAt().toInstant());
        assertThat(restored.createdAt().getOffset()).isEqualTo(ZoneOffset.UTC);
        // 3단 중첩은 정규화되지 않고 draft 안에 접혀 그대로 돌아온다(ADR-73 예외).
        assertThat(restored.draft()).isEqualTo(pending.draft());
        assertThat(restored.match()).isEqualTo(MatchInfo.newNote());
    }

    @Test
    @DisplayName("TΔ4 POLICY 승계: created_at 표기는 어느 경로로 읽어도 UTC 한 벌 — 컨텍스트 사본과 DB 왕복이 같다")
    void createdAtRepresentationIsTheSameFromContextAndFromDatabase() {
        // 위 왕복 테스트는 이 POLICY를 붙잡지 못한다 — 컨텍스트를 비우고 읽으면 드라이버가 어차피 UTC를
        // 주므로 쓰기 표기를 안 맞춰도 그린이 된다. 갈라지는 것은 **비우지 않은** 경로다: 정규화를 빼면
        // identity map이 +09:00을, DB가 Z를 줘서 같은 저장소가 두 표기를 낸다(TΔ4 실측과 같은 자리).
        PendingNote pending = sample(OffsetDateTime.now(FIXED));
        assertThat(pending.createdAt().getOffset()).isNotEqualTo(ZoneOffset.UTC); // 입력은 Asia/Seoul

        store.put(USER, pending);
        OffsetDateTime fromContext = store.get(USER).orElseThrow().createdAt();
        flushAndClear();
        OffsetDateTime fromDatabase = store.get(USER).orElseThrow().createdAt();

        assertThat(fromContext).isEqualTo(fromDatabase);
        assertThat(fromContext.getOffset()).isEqualTo(ZoneOffset.UTC);
    }

    @Test
    @DisplayName("NFR-6: user_id가 PK라 put은 덮어쓰기다 — 두 번 저장해도 행은 하나고 값은 최신이다")
    void putOverwritesTheSingleRow() {
        OffsetDateTime createdAt = OffsetDateTime.now(FIXED);
        store.put(USER, sample(createdAt));
        flushAndClear();

        store.put(USER, withCoffeeName(sample(createdAt), "커피베라 시다모"));
        flushAndClear();

        assertThat(pendings.count()).isEqualTo(1);
        assertThat(store.get(USER).orElseThrow().draft().coffeeName().value()).isEqualTo("커피베라 시다모");
    }

    @Test
    @DisplayName("V-7: TTL 초과 pending은 get에서 만료 처리(빈 Optional) — 훼손이 아니므로 행은 남는다")
    void expiredPendingIsNotReturned() {
        // 25시간 전 생성 → TTL(24h) 초과.
        store.put(USER, sample(OffsetDateTime.now(FIXED).minusHours(25)));
        flushAndClear();

        assertThat(store.get(USER)).isEmpty();
        // 만료 정리는 다음 put이 덮어쓰며 한다 — 훼손 정리(행 삭제)와 갈리는 지점이다.
        assertThat(pendings.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("V-7 경계: TTL 이내 pending은 정상 조회")
    void freshPendingIsReturned() {
        PendingNote pending = sample(OffsetDateTime.now(FIXED).minusHours(23));
        store.put(USER, pending);
        flushAndClear();

        assertThat(store.get(USER)).contains(utcCreatedAt(pending));
    }

    @Test
    @DisplayName("0012-TΔ1/AC-Δ5: edit 모드 pending은 재시작 후에도 mode·target이 보존된다")
    void editModePendingSurvivesRestart() {
        PendingNote editPending = editSample(OffsetDateTime.now(FIXED));

        store.put(USER, editPending);
        flushAndClear();

        PendingNote restored = store.get(USER).orElseThrow();
        assertThat(restored.mode()).isEqualTo(PendingNote.Mode.EDIT);
        assertThat(restored.target()).isEqualTo(new PendingNote.EditTarget(1L, LocalDate.of(2026, 7, 9)));
        // record 모드 전용 필드는 비어 돌아온다 — match_type이 판별 컬럼이라 부재가 null로 되살아난다.
        assertThat(restored.match()).isNull();
    }

    @Test
    @DisplayName("D-1/TΔ0b E-5: 저장 전 draft는 id가 없는 것이 정상 — 신규 record pending이 훼손 판정되지 않는다")
    void newRecordDraftWithoutIdIsNotCorrupt() {
        // 구 "draft.slug 공백" 검사를 그대로 옮겼다면 여기서 전부 훼손으로 떨어졌다 — id는 INSERT가
        // 발급하므로 propose 시점의 draft에는 식별자가 없다(ADR-74).
        PendingNote pending = sample(OffsetDateTime.now(FIXED));
        assertThat(pending.draft().id()).isNull();

        store.put(USER, pending);
        flushAndClear();

        assertThat(store.get(USER)).isPresent();
        assertThat(store.get(USER).orElseThrow().draft().id()).isNull();
    }

    // --- 로드 무결성: 훼손 pending은 경고 로그 + 행 정리 후 empty (ADR-66 ②, 구 0025 TΔ2b-1 승계) ---

    @Test
    @DisplayName("ADR-66: draft가 JSON null이면 empty 반환 + 행 삭제 — jsonb NOT NULL이 통과시키는 갈래")
    void corruptNullDraftIsDiscarded() {
        seedCorruptDraft("null");

        assertThat(store.get(USER)).isEmpty();
        assertThat(rowCountAfterFlush()).isZero();
    }

    @Test
    @DisplayName("ADR-66: draft가 Note 형태가 아니면(파싱 실패) empty 반환 + 행 삭제")
    void corruptNonNoteDraftIsDiscarded() {
        // jsonb로는 멀쩡한 값이라 컬럼이 막지 못한다 — 형태 판정은 애플리케이션 몫이다.
        seedCorruptDraft("\"not-a-note\"");

        assertThat(store.get(USER)).isEmpty();
        assertThat(rowCountAfterFlush()).isZero();
    }

    @Test
    @DisplayName("0025-CR25-6/ADR-66: draft.coffee_name 결손은 empty 반환 + 행 삭제(모델 대면 null 차단)")
    void corruptBlankCoffeeNameIsDiscarded() {
        seedCorruptDraft(draftTree(sample(OffsetDateTime.now(FIXED)), tree -> tree.remove("coffee_name")));

        assertThat(store.get(USER)).isEmpty();
        assertThat(rowCountAfterFlush()).isZero();
    }

    @Test
    @DisplayName("0025-TΔ2b-1/AC-Δ2②: draft 엔트리 0건은 empty 반환 + 행 삭제")
    void corruptZeroEntriesIsDiscarded() {
        seedCorruptDraft(draftTree(sample(OffsetDateTime.now(FIXED)),
                tree -> tree.set("entries", tree.arrayNode())));

        assertThat(store.get(USER)).isEmpty();
        assertThat(rowCountAfterFlush()).isZero();
    }

    @Test
    @DisplayName("0025-TΔ2b-1/ADR-66: draft.entries 필드 부재도 NPE 없이 empty로 수렴")
    void corruptNullEntriesIsDiscarded() {
        // 도메인 생성자가 entries 부재를 빈 배열로 수렴시키고(V-3, CR25-10) 관문이 "엔트리 0건"으로 잡는다 —
        // 어느 층이 바뀌어도 이 경로가 NPE로 새지 않는지 가드한다.
        seedCorruptDraft(draftTree(sample(OffsetDateTime.now(FIXED)), tree -> tree.remove("entries")));

        assertThat(store.get(USER)).isEmpty();
        assertThat(rowCountAfterFlush()).isZero();
    }

    @Test
    @DisplayName("0025-TΔ2b-1/AC-Δ2②: edit 모드인데 target 결손은 empty 반환 + 행 삭제")
    void corruptEditWithoutTargetIsDiscarded() {
        // record 모드는 target이 없는 것이 정상이라 컬럼을 NOT NULL로 못 만든다 — 조건부 필수라 관문에 남는다.
        PendingNote editPending = editSample(OffsetDateTime.now(FIXED));
        seedRow(entityOf(editPending, mapper.writeValueAsString(editPending.draft()), null, null));

        assertThat(store.get(USER)).isEmpty();
        assertThat(rowCountAfterFlush()).isZero();
    }

    @Test
    @DisplayName("0025-CR25-10/ADR-66: edit 모드 target.note_id 결손은 empty 반환 + 행 삭제(게이트 NPE·모델 대면 null 차단)")
    void corruptEditWithMissingTargetNoteIdIsDiscarded() {
        // note_id 결손 target이 통과하면 SinglePendingGate의 같은 대상 판정이 id 비교에서 NPE로 새고,
        // 거부 사유에는 리터럴 "null"이 노출된다(draft.coffee_name과 동일 부류).
        PendingNote editPending = editSample(OffsetDateTime.now(FIXED));
        seedRow(entityOf(editPending, mapper.writeValueAsString(editPending.draft()),
                null, LocalDate.of(2026, 7, 9)));

        assertThat(store.get(USER)).isEmpty();
        assertThat(rowCountAfterFlush()).isZero();
    }

    @Test
    @DisplayName("0025-CR25-6/ADR-66: 수기 편집 draft의 무효 beans 요소는 로드 시 드롭(노트 읽기와 동일 위생), 행은 무변경")
    void corruptDraftBeanElementIsNormalizedOnLoad() {
        // description 부재 bean 요소(V-14 위반)를 draft.beans에 혼입 — 로드 정규화로 드롭돼야 한다.
        seedCorruptDraft(draftTree(sample(OffsetDateTime.now(FIXED)), tree -> {
            ArrayNode beans = (ArrayNode) tree.get("beans");
            beans.addObject().putNull("process"); // description 없는 무효 원두
        }));

        PendingNote loaded = store.get(USER).orElseThrow();
        assertThat(loaded.draft().beans())
                .as("V-14 위반 요소(무효 원두)는 드롭되고 정상 원두만 유지된다")
                .hasSize(1);
        assertThat(rowCountAfterFlush()).isEqualTo(1); // 로드 정규화는 행을 다시 쓰지 않는다(메모리 정규화만)
    }

    // --- 관문의 이관분: 컬럼으로 표현되는 부재는 스키마가 막는다(클래스 javadoc 표) ---

    @Test
    @DisplayName("TΔ8: mode 부재는 애플리케이션이 아니라 스키마가 막는다(NOT NULL)")
    void schemaRejectsMissingMode() {
        PendingNote pending = sample(OffsetDateTime.now(FIXED));
        PendingNoteEntity row = new PendingNoteEntity(USER, null, mapper.writeValueAsString(pending.draft()),
                null, null, false, MatchInfo.MatchType.NEW, null, null, "1720570200.000100",
                pending.createdAt());

        assertThatThrownBy(() -> pendings.saveAndFlush(row))
                .as("mode NOT NULL — 검사를 코드에서 뺀 자리를 실제로 DB가 받는다")
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("TΔ8: created_at 부재도 스키마가 막는다(NOT NULL) — TTL 판정의 필수 입력")
    void schemaRejectsMissingCreatedAt() {
        PendingNote pending = sample(OffsetDateTime.now(FIXED));
        PendingNoteEntity row = new PendingNoteEntity(USER, PendingNote.Mode.RECORD,
                mapper.writeValueAsString(pending.draft()), null, null, false,
                MatchInfo.MatchType.NEW, null, null, "1720570200.000100", null);

        assertThatThrownBy(() -> pendings.saveAndFlush(row))
                .as("created_at NOT NULL — 결손이면 isExpired가 NPE로 샜을 자리다")
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("clear 후 조회는 빈 Optional / 부재 시에도 빈 Optional")
    void clearAndAbsent() {
        assertThat(store.get(USER)).isEmpty();

        store.put(USER, sample(OffsetDateTime.now(FIXED)));
        flushAndClear();
        assertThat(store.get(USER)).isPresent();

        store.clear(USER);
        flushAndClear();
        assertThat(store.get(USER)).isEmpty();
        // 없는 행 clear도 조용히 지나간다 — [취소]·만료 정리가 "이미 없음"으로 실패하지 않는다.
        store.clear(USER);
    }

    // ────────────────────────────── 표본·헬퍼 ──────────────────────────────

    private static PendingNote sample(OffsetDateTime createdAt) {
        Note draft = new Note(
                null, // 저장 전 draft는 식별자가 없다(D-1)
                new Sourced<>("커피베라 예가체프 G1", Source.USER),
                new Sourced<>("커피베라", Source.USER),
                List.of(new Bean(new Sourced<>("에티오피아 예가체프", Source.SEARCH),
                        new Sourced<>("워시드", Source.SEARCH))),
                new Sourced<>(null, Source.SEARCH),
                new Sourced<>(List.of("자스민", "베르가못"), Source.SEARCH),
                List.of("https://example.com/coffeevera"),
                List.of(new Entry(LocalDate.of(2026, 7, 10),
                        List.of(new Brew(null, new Tasting("새콤하고 좋았다", null, Rating.GOOD))), createdAt)),
                createdAt,
                createdAt);
        return new PendingNote(draft, MatchInfo.newNote(), "1720570200.000100", createdAt);
    }

    private static PendingNote editSample(OffsetDateTime createdAt) {
        return new PendingNote(PendingNote.Mode.EDIT, sample(createdAt).draft(),
                new PendingNote.EditTarget(1L, LocalDate.of(2026, 7, 9)), null,
                "1720570200.000100", createdAt);
    }

    /** 저장이 눕히는 표기(UTC)까지 반영한 기대값 — 인스턴트는 그대로다(POLICY, TΔ4 승계). */
    private static PendingNote utcCreatedAt(PendingNote pending) {
        return new PendingNote(pending.mode(), pending.draft(), pending.target(), pending.dateConflict(),
                pending.match(), pending.previewTs(),
                pending.createdAt().withOffsetSameInstant(ZoneOffset.UTC));
    }

    private PendingNote withCoffeeName(PendingNote pending, String coffeeName) {
        Note draft = pending.draft();
        return pending.withDraft(new Note(draft.id(), new Sourced<>(coffeeName, Source.USER), draft.roastery(),
                draft.beans(), draft.roastLevel(), draft.officialNotes(), draft.aliases(), draft.sources(),
                draft.entries(), draft.createdAt(), draft.updatedAt()));
    }

    /** 유효 draft를 직렬화한 트리를 임의로 변형해 훼손 JSON을 만든다(필드명 수기 실수 회피). */
    private String draftTree(PendingNote pending, Consumer<ObjectNode> mutator) {
        ObjectNode tree = (ObjectNode) mapper.readTree(mapper.writeValueAsString(pending.draft()));
        mutator.accept(tree);
        return mapper.writeValueAsString(tree);
    }

    /** 저장소를 지나지 않고 훼손 draft를 행으로 직접 심는다 — psql 직접 편집이 만드는 상태의 재현이다. */
    private void seedCorruptDraft(String draftJson) {
        seedRow(entityOf(sample(OffsetDateTime.now(FIXED)), draftJson, null, null));
    }

    private void seedRow(PendingNoteEntity row) {
        pendings.save(row);
        flushAndClear();
    }

    private PendingNoteEntity entityOf(PendingNote pending, String draftJson, Long targetNoteId,
                                       LocalDate targetDate) {
        MatchInfo match = pending.match();
        return new PendingNoteEntity(USER, pending.mode(), draftJson, targetNoteId, targetDate,
                pending.dateConflict(),
                match == null ? null : match.type(),
                match == null ? null : match.noteId(),
                match == null ? null : match.date(),
                pending.previewTs(),
                pending.createdAt().withOffsetSameInstant(ZoneOffset.UTC));
    }

    private long rowCountAfterFlush() {
        flushAndClear();
        return pendings.count();
    }

    /**
     * 실 커밋이 하는 일을 테스트 안에서 재현한다 — flush로 쓰기를 내보내고 clear로 identity map을 비운다.
     * <p>clear만 부르면 아직 나가지 않은 쓰기가 버려지고, flush만 걸면 조회가 DB를 지나지 않는다(TΔ5c 선례).
     */
    private void flushAndClear() {
        em.flush();
        em.clear();
    }
}
