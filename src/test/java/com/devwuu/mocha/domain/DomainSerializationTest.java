package com.devwuu.mocha.domain;

import com.devwuu.mocha.json.MochaObjectMapper;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T1-1: 도메인 모델 Jackson 직렬화.
 * <p>직렬화 왕복 동일성 + rating enum 역직렬화 위반 거부(V-1).
 */
class DomainSerializationTest {

    private final ObjectMapper mapper = MochaObjectMapper.create();

    private static Note sampleNote() {
        OffsetDateTime ts = OffsetDateTime.of(2026, 7, 10, 9, 30, 0, 0, ZoneOffset.ofHours(9));
        Entry entry = new Entry(
                LocalDate.of(2026, 7, 10),
                "새콤하고 좋았다",
                Rating.GOOD,
                new Recipe(15.0, 240.0, "중간"),   // 레시피 포함 — Note 왕복에 recipe도 실린다(FR-18)
                List.of("photos/coffeevera-yirgacheffe-g1/2026-07-10/a.jpg"),
                ts
        );
        return new Note(
                "coffeevera-yirgacheffe-g1",
                Sourced.user("커피베라 예가체프 G1"),
                Sourced.user("커피베라"),
                Sourced.search("에티오피아 예가체프"),
                Sourced.search("워시드"),
                new Sourced<>(null, Source.SEARCH),          // 미확정 값 + source 마킹
                Sourced.search(List.of("자스민", "베르가못")),  // Sourced<List<String>>
                List.of("https://example.com/coffeevera"),
                List.of(entry),
                ts,
                ts
        );
    }

    @Test
    @DisplayName("T1-1: Note 직렬화 왕복 동일성")
    void noteRoundTrip() throws Exception {
        Note original = sampleNote();

        String json = mapper.writeValueAsString(original);
        Note restored = mapper.readValue(json, Note.class);

        assertThat(restored).isEqualTo(original);
    }

    @Test
    @DisplayName("T1-1: PendingNote(draft+match) 직렬화 왕복 동일성")
    void pendingNoteRoundTrip() throws Exception {
        OffsetDateTime ts = OffsetDateTime.of(2026, 7, 10, 9, 30, 0, 0, ZoneOffset.ofHours(9));
        PendingNote pendingNew = new PendingNote(sampleNote(), MatchInfo.newNote(), "1720000000.000100", ts);
        PendingNote pendingExisting = new PendingNote(
                sampleNote(),
                MatchInfo.existing("coffeevera-yirgacheffe-g1", LocalDate.of(2026, 7, 9)),
                "1720000000.000200",
                ts
        );

        for (PendingNote original : List.of(pendingNew, pendingExisting)) {
            String json = mapper.writeValueAsString(original);
            PendingNote restored = mapper.readValue(json, PendingNote.class);
            assertThat(restored).isEqualTo(original);
        }
    }

    @Test
    @DisplayName("T1-1: JSON 필드명은 snake_case + match=new는 slug/date 생략")
    void jsonShape() throws Exception {
        String noteJson = mapper.writeValueAsString(sampleNote());
        assertThat(noteJson)
                .contains("\"coffee_name\"")
                .contains("\"roast_level\"")
                .contains("\"official_notes\"")
                .contains("\"my_taste\"")
                .contains("\"created_at\"")
                .contains("\"dose_g\"")     // recipe 필드 snake_case(FR-18)
                .contains("\"water_ml\"");

        String matchJson = mapper.writeValueAsString(MatchInfo.newNote());
        assertThat(matchJson).isEqualTo("{\"type\":\"new\"}");
    }

    @Test
    @DisplayName("V-1: rating 4범주 외 값은 역직렬화 거부")
    void invalidRatingRejected() {
        String json = "\"별로였음\"";

        assertThatThrownBy(() -> mapper.readValue(json, Rating.class))
                .isInstanceOf(JacksonException.class)
                .hasMessageContaining("invalid rating");
    }

    @Test
    @DisplayName("V-1: rating null 허용(미언급)")
    void nullRatingAllowed() throws Exception {
        Entry entry = new Entry(LocalDate.of(2026, 7, 10), "무난", null, null, List.of(), null);

        String json = mapper.writeValueAsString(entry);
        Entry restored = mapper.readValue(json, Entry.class);

        assertThat(restored.rating()).isNull();
        assertThat(restored).isEqualTo(entry);
    }

    // --- TΔ1: recipe (FR-18, V-8, changes/0010) ---

    @Test
    @DisplayName("TΔ1/FR-18: recipe 직렬화 왕복(dose_g·water_ml·grind)")
    void recipeRoundTrip() throws Exception {
        Entry entry = new Entry(
                LocalDate.of(2026, 7, 10), "새콤", Rating.GOOD,
                new Recipe(15.0, 240.0, "중간"), List.of(), null);

        String json = mapper.writeValueAsString(entry);
        Entry restored = mapper.readValue(json, Entry.class);

        assertThat(json).contains("\"dose_g\":15").contains("\"water_ml\":240").contains("\"grind\":\"중간\"");
        assertThat(restored).isEqualTo(entry);
        assertThat(restored.recipe()).isEqualTo(new Recipe(15.0, 240.0, "중간"));
    }

    @Test
    @DisplayName("TΔ1/AC-Δ8: recipe 미언급 엔트리는 recipe=null로 직렬화·왕복된다")
    void recipeNullRoundTrip() throws Exception {
        Entry entry = new Entry(LocalDate.of(2026, 7, 10), "무난", Rating.GOOD, null, List.of(), null);

        String json = mapper.writeValueAsString(entry);
        Entry restored = mapper.readValue(json, Entry.class);

        assertThat(json).contains("\"recipe\":null");
        assertThat(restored.recipe()).isNull();
        assertThat(restored).isEqualTo(entry);
    }

    @Test
    @DisplayName("TΔ1/V-8: recipe 3항목 전무면 normalize가 null로 정규화한다")
    void recipeNormalizeAllNull() {
        assertThat(Recipe.normalize(null, null, null)).isNull();
        assertThat(Recipe.normalize(null, null, "  ")).isNull();  // 공백 grind도 전무로 간주
    }

    @Test
    @DisplayName("TΔ1/V-8: 위반 값(음수·0·공백)은 해당 항목만 null로 드롭한다")
    void recipeNormalizeDropsInvalid() {
        // dose 음수·water 0 → 각 null 드롭, grind만 살아 Recipe는 유지(부속 정보라 저장 거부 아님).
        Recipe dropped = Recipe.normalize(-15.0, 0.0, "굵게");
        assertThat(dropped).isEqualTo(new Recipe(null, null, "굵게"));

        // 정상 양수는 보존, 공백 grind만 드롭.
        Recipe partial = Recipe.normalize(15.0, 240.0, "   ");
        assertThat(partial).isEqualTo(new Recipe(15.0, 240.0, null));
    }

    // --- 0012 TΔ1: PendingNote mode·target (FR-21, changes/0012) ---

    @Test
    @DisplayName("0012-TΔ1: edit 모드 PendingNote(mode+target) 직렬화 왕복 동일성")
    void editModePendingNoteRoundTrip() throws Exception {
        OffsetDateTime ts = OffsetDateTime.of(2026, 7, 10, 9, 30, 0, 0, ZoneOffset.ofHours(9));
        PendingNote editPending = new PendingNote(
                PendingNote.Mode.EDIT,
                sampleNote(),
                new PendingNote.EditTarget("coffeevera-yirgacheffe-g1", LocalDate.of(2026, 7, 9)),
                true,   // 날짜 이동 충돌 경고 — 재시작 후에도 [저장]/[취소]까지 유지돼야 한다(V-10, TΔ5)
                null,   // match는 record 모드 한정(data-model §2.3)
                "1720000000.000300",
                ts
        );

        String json = mapper.writeValueAsString(editPending);
        PendingNote restored = mapper.readValue(json, PendingNote.class);

        assertThat(json).contains("\"mode\":\"edit\"").contains("\"date_conflict\":true");
        assertThat(restored).isEqualTo(editPending);
        assertThat(restored.dateConflict()).isTrue();
        assertThat(restored.target()).isEqualTo(
                new PendingNote.EditTarget("coffeevera-yirgacheffe-g1", LocalDate.of(2026, 7, 9)));
    }

    // (0012 이전 pending.json 하위 호환 테스트는 제거 — 기존 데이터는 배포 전 수동 삭제로 결정, delta 비범위.)

    @Test
    @DisplayName("0012-TΔ1: 알 수 없는 mode 값은 역직렬화 거부")
    void invalidPendingModeRejected() {
        assertThatThrownBy(() -> mapper.readValue("\"delete\"", PendingNote.Mode.class))
                .isInstanceOf(JacksonException.class)
                .hasMessageContaining("unknown pending mode");
    }

    // --- TΔ1: coffee_name Sourced 승격 (V-5, changes/0010) ---

    @Test
    @DisplayName("TΔ1/V-5: coffee_name은 {value, source} 객체로 왕복된다(user·photo)")
    void coffeeNameSourcedRoundTrip() throws Exception {
        for (Source src : List.of(Source.USER, Source.PHOTO)) {
            Sourced<String> coffeeName = new Sourced<>("커피베라 예가체프 G1", src);
            String json = mapper.writeValueAsString(coffeeName);
            @SuppressWarnings("unchecked")
            Sourced<String> restored = mapper.readValue(json, Sourced.class);

            assertThat(json)
                    .contains("\"value\":\"커피베라 예가체프 G1\"")
                    .contains("\"source\":\"" + src.json() + "\"");
            assertThat(restored).isEqualTo(coffeeName);
        }
    }
}
