package com.devwuu.mocha.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.devwuu.mocha.domain.Source;
import com.devwuu.mocha.repository.entity.NoteEntity;
import com.devwuu.mocha.repository.entity.SourcedValue;
import com.devwuu.mocha.support.PostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

/**
 * TΔ10a (changes/0028-rdb-storage) — 저장소 통합 테스트 기반이 실제로 서는지 (AC-Δ2).
 *
 * <p>이 기반의 실패 모드는 조용하다: 프로파일이 안 붙거나 {@code default_schema}가 빠지면 테스트가
 * <b>개발 데이터가 사는 public 스키마</b>에 그대로 붙어 그린으로 흐른다. 그래서 "Flyway가 test 스키마에
 * V1을 올렸다"와 "엔티티가 그 스키마에 쓴다"를 각각 단언한다.
 */
@Transactional
class SchemaMigrationTest extends PostgresIntegrationTest {

    /** TΔ2 §스키마의 10개 테이블. 복수형 → 단수형 개정(사용자 확정 2026-07-31)이 반영된 이름이다. */
    // 0029 TΔ4: V2가 pending_note를 드롭했다 — 작성 중 데이터는 클라이언트 폼이 소유한다(delta 0029 D-2).
    // 0029 TΔ8b: V3가 note_photo를 들였다 — 노트↔사진 연결이 폴더 규약에서 행으로 옮겨왔다(ADR-79).
    // 0030 TΔ1: V4가 tasting을 review로 개명했다 — 테이블 수는 그대로고 이름만 옮겨갔다(ADR-85, AC-Δ1).
    // 0030 TΔ3: V5가 brew를 cup으로 개명했다 — 자식의 조인 컬럼 brew_id도 cup_id로 함께 옮겨갔다.
    // 0030 TΔ5a: V6가 entry를 tasting_day로 개명했다 — cup.entry_id도 tasting_day_id로 함께 옮겨갔다.
    private static final List<String> TABLES = List.of(
            "note", "note_bean", "note_official_note", "note_alias", "note_source",
            "tasting_day", "cup", "recipe", "review", "note_photo");

    /** 0030 개명이 걷어낸 이름 — 「이름만 옮긴다」는 «신 이름이 있다»와 «구 이름이 없다»가 함께 서야 성립한다. */
    private static final List<String> RENAMED_AWAY = List.of("tasting", "brew", "entry");

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    EntityManager em;

    @Test
    @DisplayName("AC-Δ2: Flyway가 test 스키마에 마이그레이션 전량을 적용한다")
    void flywayAppliesInitialSchema() {
        // version IS NULL 행은 스키마 생성 마커다 — clean 후 migrate라 test 스키마를 Flyway가 직접 만든다.
        List<String> versions = jdbc.queryForList(
                "SELECT version FROM test.flyway_schema_history"
                        + " WHERE success = true AND version IS NOT NULL ORDER BY installed_rank",
                String.class);

        assertThat(versions).containsExactly("1", "2", "3", "4", "5", "6", "7");
    }

    @Test
    @DisplayName("AC-Δ2: 마이그레이션 산출이 스키마 정의 그대로다 — 테이블 10개(TΔ4 pending 드롭, TΔ8b note_photo 신설)")
    void schemaHasAllTables() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables"
                        + " WHERE table_schema = 'test' AND table_name <> 'flyway_schema_history'",
                String.class);

        assertThat(tables).containsExactlyInAnyOrderElementsOf(TABLES);
        assertThat(tables).doesNotContainAnyElementsOf(RENAMED_AWAY);
    }

    @Test
    @DisplayName("AC-Δ1(TΔ1): tasting → review 개명 — 컬럼·제약은 그대로 따라왔다")
    void reviewTableCarriesTheRenamedColumns() {
        // 개명은 이름만 옮긴다. 컬럼 4종이 그대로 따라오지 않았다면 rename이 아니라 재생성이 일어난 것이다.
        List<String> columns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns"
                        + " WHERE table_schema = 'test' AND table_name = 'review'",
                String.class);

        assertThat(columns).containsExactlyInAnyOrder("cup_id", "my_taste", "my_taste_original", "rating");

        // V-1 4범주 CHECK가 살아 있다 — 제약 이름(tasting_rating_check)은 rename 대상이 아니라 그대로다.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid"
                        + " JOIN pg_namespace n ON n.oid = c.connamespace"
                        + " WHERE n.nspname = 'test' AND t.relname = 'review' AND c.contype = 'c'",
                Long.class)).isEqualTo(1L);
    }

    @Test
    @DisplayName("AC-Δ1(TΔ3): brew → cup 개명 — 회차 행의 컬럼·UNIQUE가 그대로 따라왔다")
    void cupTableCarriesTheRenamedColumns() {
        // entry_id는 V6(TΔ5a)가 tasting_day_id로 옮겼다 — 이 테스트는 그 뒤의 상태를 본다.
        assertThat(columnsOf("cup")).containsExactlyInAnyOrder("id", "tasting_day_id", "seq");

        // UNIQUE(tasting_day_id, seq)가 살아 있다 — 회차 번호의 유일성이 rename을 건너뛰지 않았다는 증거다.
        // 제약 이름(brew_entry_id_seq_key)은 테이블·컬럼 rename을 따라오지 않아 그대로이므로 개수로 센다.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid"
                        + " JOIN pg_namespace n ON n.oid = c.connamespace"
                        + " WHERE n.nspname = 'test' AND t.relname = 'cup' AND c.contype = 'u'",
                Long.class)).isEqualTo(1L);
    }

    @Test
    @DisplayName("AC-Δ1(TΔ3): 1:1 자식의 조인 컬럼이 brew_id → cup_id로 함께 옮겨갔다")
    void oneToOneChildrenJoinOnCupId() {
        // recipe·review는 조인 컬럼이 곧 PK다(ADR-75 — FK 없는 1:1 표현). 한쪽만 옮겨가면 조립이 조용히
        // 깨지므로 «신 이름이 있다»와 «구 이름이 없다»를 두 테이블 모두에 건다.
        assertThat(columnsOf("recipe")).contains("cup_id").doesNotContain("brew_id");
        assertThat(columnsOf("review")).contains("cup_id").doesNotContain("brew_id");

        assertThat(jdbc.queryForList(
                "SELECT a.attname FROM pg_index i JOIN pg_class t ON t.oid = i.indrelid"
                        + " JOIN pg_namespace n ON n.oid = t.relnamespace"
                        + " JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = ANY (i.indkey)"
                        + " WHERE n.nspname = 'test' AND t.relname = 'recipe' AND i.indisprimary",
                String.class)).containsExactly("cup_id");
    }

    @Test
    @DisplayName("AC-Δ1·Δ16(TΔ5a): entry → tasting_day 개명 — 컬럼·UNIQUE·인덱스가 그대로 따라왔다")
    void tastingDayTableCarriesTheRenamedColumns() {
        // 감사 컬럼 4종이 함께 따라온다 — 개명이 재생성이었다면 여기서 빠진다(delta §감사 컬럼).
        assertThat(columnsOf("tasting_day")).containsExactlyInAnyOrder(
                "id", "note_id", "tasted_on", "created_at", "created_by", "modified_at", "modified_by");

        // AC-Δ16: UNIQUE(note_id, tasted_on) = 「하루 시음일 1건」이 DB 제약으로 살아 있다(V-3·V-10).
        // 병합안을 택했다면 사라졌을 자리라 개수가 아니라 «어느 컬럼에 걸렸는가»까지 본다.
        assertThat(jdbc.queryForList(
                "SELECT a.attname FROM pg_constraint c JOIN pg_class t ON t.oid = c.conrelid"
                        + " JOIN pg_namespace n ON n.oid = c.connamespace"
                        + " JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = ANY (c.conkey)"
                        + " WHERE n.nspname = 'test' AND t.relname = 'tasting_day' AND c.contype = 'u'",
                String.class)).containsExactlyInAnyOrder("note_id", "tasted_on");

        // 인덱스는 이름이 표시되는 자리라(psql \d, 실행계획) 제약과 달리 명시적으로 옮겼다 — V6가 그 문장이다.
        assertThat(jdbc.queryForList(
                "SELECT indexname FROM pg_indexes WHERE schemaname = 'test' AND tablename = 'tasting_day'",
                String.class)).contains("idx_tasting_day_tasted_on").doesNotContain("idx_entry_tasted_on");
    }

    @Test
    @DisplayName("AC-Δ1(TΔ5a): 회차의 조인 컬럼이 entry_id → tasting_day_id로 함께 옮겨갔다")
    void cupsJoinOnTastingDayId() {
        // 테이블만 옮기고 조인 컬럼을 두면 매핑이 조용히 어긋난다 — «신 이름이 있다»와 «구 이름이 없다» 짝.
        assertThat(columnsOf("cup")).contains("tasting_day_id").doesNotContain("entry_id");
    }

    @Test
    @DisplayName("AC-Δ8(TΔ9): 레시피 필드 재편 — machine·pouring이 사라지고 detail·grind(numeric)·grinder가 들어왔다")
    void recipeTableCarriesTheRebuiltFields() {
        assertThat(columnsOf("recipe")).containsExactlyInAnyOrder(
                "cup_id", "method", "dose_g", "water_ml", "yield_ml", "time_sec", "temp_c",
                "detail", "feedback", "grind", "grinder");

        // grind는 rename이 아니라 DROP 후 재생성이다(D-4·D-8) — 이름이 같아 컬럼 목록만으로는 재편이
        // 서지 않는다. 타입이 numeric인 것이 「수치 6종에 편입됐다」의 증거이고, TEXT로 남아 있었다면
        // 위 단언은 그린인 채 V-8 검사만 조용히 비켜갔을 자리다.
        assertThat(jdbc.queryForObject(
                "SELECT data_type FROM information_schema.columns"
                        + " WHERE table_schema = 'test' AND table_name = 'recipe' AND column_name = 'grind'",
                String.class)).isEqualTo("numeric");
    }

    @Test
    @DisplayName("AC-Δ6(TΔ9): grind CHECK가 0·음수·Infinity·NaN을 거부한다 — V-8이 DB 제약으로도 선다")
    void grindCheckRejectsNonPositiveAndNonFinite() {
        // numeric은 double과 달리 Infinity·NaN을 값으로 받고 둘 다 0보다 크다고 판정되므로
        // `> 0`만으로는 부족하다 — CHECK가 `< 'Infinity'`까지 보는 이유다(V1 주석 승계).
        for (String violating : List.of("0", "-1", "Infinity", "NaN")) {
            // 위반 삽입은 트랜잭션 전체를 abort시킨다(Postgres) — 세이브포인트로 되감지 않으면
            // 두 번째 시도부터 "current transaction is aborted"라는 «다른 이유»로 실패해 그린을 가장한다.
            jdbc.execute("SAVEPOINT before_violation");
            assertThatThrownBy(() -> jdbc.update(
                    "INSERT INTO test.recipe (cup_id, grind) VALUES (999, ?::NUMERIC)", violating))
                    .hasStackTraceContaining("recipe_grind_check");
            jdbc.execute("ROLLBACK TO SAVEPOINT before_violation");
        }

        // AC-Δ5의 DB 쪽 절반 — 분쇄값과 그라인더명이 갈린 두 컬럼에 각각 앉는다.
        jdbc.update("INSERT INTO test.recipe (cup_id, grind, grinder) VALUES (999, 210, ?)", "매버릭 2.0");
        // V-8 위반은 «값이 있는데 유효하지 않은 것»뿐이다 — 미언급 필드(null)는 CHECK를 통과한다.
        jdbc.update("INSERT INTO test.recipe (cup_id, grind) VALUES (998, NULL)");
    }

    private List<String> columnsOf(String table) {
        return jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns"
                        + " WHERE table_schema = 'test' AND table_name = ?",
                String.class, table);
    }

    @Test
    @DisplayName("AC-Δ2·Δ3: 제약이 실제로 올라왔다 — 스키마 소유자가 Hibernate가 아니라 Flyway다")
    void schemaCarriesConstraints() {
        // 엔티티에는 제약 선언이 0건이라 ddl-auto가 만든 스키마였다면 CHECK·UNIQUE가 통째로 비어 있다.
        // 개수는 TΔ2 판정이 psql로 확인한 값과 같아야 한다(FK 0 = ADR-75).
        // 0029 TΔ4: pending_note 드롭으로 pk 10 → 9, ck 15 → 13(mode·match_type CHECK 2종이 함께 사라졌다).
        // 0029 TΔ8b: note_photo 신설로 pk 9 → 10, uq 6 → 7(UNIQUE(note_id, tasted_on, seq)). CHECK는 없다.
        // 0030 TΔ9: V7이 grind를 numeric으로 재생성하며 CHECK가 딸려 ck 13 → 14. 개명 3종(V4~V6)은
        //           제약을 건드리지 않아 이 숫자에 영향이 없었다 — 형태가 바뀐 이번이 처음이다.
        Map<String, Object> byType = jdbc.queryForMap(
                "SELECT count(*) FILTER (WHERE contype = 'p') AS pk,"
                        + " count(*) FILTER (WHERE contype = 'u') AS uq,"
                        + " count(*) FILTER (WHERE contype = 'c') AS ck,"
                        + " count(*) FILTER (WHERE contype = 'f') AS fk"
                        + " FROM pg_constraint c JOIN pg_namespace n ON n.oid = c.connamespace"
                        + " JOIN pg_class t ON t.oid = c.conrelid"
                        + " WHERE n.nspname = 'test' AND t.relname <> 'flyway_schema_history'");

        assertThat(byType).containsEntry("pk", 10L).containsEntry("uq", 7L)
                .containsEntry("ck", 14L).containsEntry("fk", 0L);
    }

    @Test
    @DisplayName("AC-Δ2: 엔티티 쓰기가 test 스키마에 내려앉는다 — 개발 데이터(public)와 갈라져 있다")
    void entityWritesLandInTestSchema() {
        em.persist(new NoteEntity(
                new SourcedValue("에티오피아 첼베사", Source.USER), null, null, null, "에티오피아첼베사", null));
        em.flush();

        // 스키마를 명시해 조회한다 — default_schema가 빠지면 여기서 0이 나온다.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM test.note", Long.class)).isEqualTo(1L);
    }
}
