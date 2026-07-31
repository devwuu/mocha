package com.devwuu.mocha.repository;

import static org.assertj.core.api.Assertions.assertThat;

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
    private static final List<String> TABLES = List.of(
            "note", "note_bean", "note_official_note", "note_alias", "note_source",
            "entry", "brew", "recipe", "tasting", "pending_note");

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    EntityManager em;

    @Test
    @DisplayName("AC-Δ2: Flyway가 test 스키마에 V1을 적용한다")
    void flywayAppliesInitialSchema() {
        // version IS NULL 행은 스키마 생성 마커다 — clean 후 migrate라 test 스키마를 Flyway가 직접 만든다.
        List<String> versions = jdbc.queryForList(
                "SELECT version FROM test.flyway_schema_history"
                        + " WHERE success = true AND version IS NOT NULL ORDER BY installed_rank",
                String.class);

        assertThat(versions).containsExactly("1");
    }

    @Test
    @DisplayName("AC-Δ2: 마이그레이션 산출이 TΔ2 스키마 그대로다 — 테이블 10개")
    void schemaHasAllTables() {
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables"
                        + " WHERE table_schema = 'test' AND table_name <> 'flyway_schema_history'",
                String.class);

        assertThat(tables).containsExactlyInAnyOrderElementsOf(TABLES);
    }

    @Test
    @DisplayName("AC-Δ2·Δ3: 제약이 실제로 올라왔다 — 스키마 소유자가 Hibernate가 아니라 Flyway다")
    void schemaCarriesConstraints() {
        // 엔티티에는 제약 선언이 0건이라 ddl-auto가 만든 스키마였다면 CHECK·UNIQUE가 통째로 비어 있다.
        // 개수는 TΔ2 판정이 psql로 확인한 값과 같아야 한다(FK 0 = ADR-75).
        Map<String, Object> byType = jdbc.queryForMap(
                "SELECT count(*) FILTER (WHERE contype = 'p') AS pk,"
                        + " count(*) FILTER (WHERE contype = 'u') AS uq,"
                        + " count(*) FILTER (WHERE contype = 'c') AS ck,"
                        + " count(*) FILTER (WHERE contype = 'f') AS fk"
                        + " FROM pg_constraint c JOIN pg_namespace n ON n.oid = c.connamespace"
                        + " JOIN pg_class t ON t.oid = c.conrelid"
                        + " WHERE n.nspname = 'test' AND t.relname <> 'flyway_schema_history'");

        assertThat(byType).containsEntry("pk", 10L).containsEntry("uq", 6L)
                .containsEntry("ck", 15L).containsEntry("fk", 0L);
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
