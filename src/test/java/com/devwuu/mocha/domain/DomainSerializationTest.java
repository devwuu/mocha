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
                List.of("photos/coffeevera-yirgacheffe-g1/2026-07-10/a.jpg"),
                ts
        );
        return new Note(
                "coffeevera-yirgacheffe-g1",
                "커피베라 예가체프 G1",
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
                .contains("\"created_at\"");

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
        Entry entry = new Entry(LocalDate.of(2026, 7, 10), "무난", null, List.of(), null);

        String json = mapper.writeValueAsString(entry);
        Entry restored = mapper.readValue(json, Entry.class);

        assertThat(restored.rating()).isNull();
        assertThat(restored).isEqualTo(entry);
    }
}
