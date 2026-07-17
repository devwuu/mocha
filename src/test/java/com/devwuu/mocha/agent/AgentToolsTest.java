package com.devwuu.mocha.agent;

import com.devwuu.mocha.domain.Aliases;
import com.devwuu.mocha.domain.Entry;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.NoteMeta;
import com.devwuu.mocha.domain.PendingNote;
import com.devwuu.mocha.domain.Rating;
import com.devwuu.mocha.domain.Sourced;
import com.devwuu.mocha.json.MochaObjectMapper;
import com.devwuu.mocha.render.NoteRenderer;
import com.devwuu.mocha.repository.NoteRepository;
import com.devwuu.mocha.slack.SlackResponder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TΔ5(changes/0018): 읽기·카드 tool 3종 — list_notes 페이로드 필드, get_note 미존재 오류(환각 필터),
 * send_entry_card의 카드 재사용/증분 렌더 분기를 결정론으로 단언한다 (data-model §3.1·3.2·3.5, FR-14/20).
 * 외부 호출 없음 — 협력자는 전부 fake(모듈 CLAUDE.md §5.2).
 */
class AgentToolsTest {

    private static final String CHANNEL = "C-mocha";

    @TempDir
    Path artifactDir;

    private final ObjectMapper mapper = MochaObjectMapper.create();
    private final StubNoteRepository noteRepository = new StubNoteRepository();
    private RecordingRenderer renderer;
    private RecordingResponder responder;
    private AgentTools agentTools;

    @BeforeEach
    void setUp() {
        renderer = new RecordingRenderer(artifactDir);
        responder = new RecordingResponder();
        agentTools = new AgentTools(noteRepository, renderer, responder, artifactDir, mapper);
    }

    @Test
    @DisplayName("plan §3: forTurn은 읽기·카드 tool 3종을 strict 스키마(additionalProperties=false)로 장착한다")
    void forTurnExposesThreeStrictTools() {
        List<AgentTool> tools = agentTools.forTurn(CHANNEL);

        assertThat(tools).extracting(AgentTool::name)
                .containsExactly("list_notes", "get_note", "send_entry_card");
        for (AgentTool tool : tools) {
            JsonNode schema = mapper.readTree(tool.parametersSchema());
            assertThat(schema.get("additionalProperties").asBoolean()).isFalse();
            // strict 계약: 선언한 전 필드가 required에 있다 (findings-TΔ0 §SDK).
            List<String> properties = new ArrayList<>(schema.get("properties").propertyNames());
            assertThat(required(schema)).containsExactlyInAnyOrderElementsOf(properties);
        }
    }

    @Test
    @DisplayName("data-model §3.1/FR-14·20: list_notes 페이로드 — 메타 필드 + 통합 별칭 + 최근 시음일(snake_case)")
    void listNotesPayloadCarriesMetaAliasesAndLastTasted() {
        noteRepository.put(note("2026-07-13-102030", "Ethiopia Chelbesa", "FroB",
                new Aliases(List.of("에티오피아 첼베사"), List.of("프롭")),
                LocalDate.of(2026, 7, 13), LocalDate.of(2026, 7, 14)));

        JsonNode result = mapper.readTree(execute("list_notes", "{}"));

        assertThat(result.get("notes")).hasSize(1);
        JsonNode summary = result.get("notes").get(0);
        assertThat(summary.get("slug").asString()).isEqualTo("2026-07-13-102030");
        assertThat(summary.get("coffee_name").asString()).isEqualTo("Ethiopia Chelbesa");
        assertThat(summary.get("roastery").asString()).isEqualTo("FroB");
        // 별칭은 커피명·로스터리 통합 목록 — 표기 비일관 흡수 재료(plan ADR-37, AC-53).
        assertThat(summary.get("aliases").values()).extracting(JsonNode::asString)
                .containsExactly("에티오피아 첼베사", "프롭");
        assertThat(summary.get("origin").asString()).isEqualTo("에티오피아");
        assertThat(summary.get("official_notes").values()).extracting(JsonNode::asString)
                .containsExactly("자스민");
        assertThat(summary.get("last_tasted").asString()).isEqualTo("2026-07-14");
    }

    @Test
    @DisplayName("data-model §3.2: get_note는 노트 전체(엔트리 포함)를 돌려준다")
    void getNoteReturnsWholeNote() {
        noteRepository.put(note("2026-07-13-102030", "Ethiopia Chelbesa", "FroB",
                Aliases.empty(), LocalDate.of(2026, 7, 13)));

        JsonNode result = mapper.readTree(execute("get_note", "{\"slug\":\"2026-07-13-102030\"}"));

        assertThat(result.get("slug").asString()).isEqualTo("2026-07-13-102030");
        assertThat(result.get("coffee_name").get("value").asString()).isEqualTo("Ethiopia Chelbesa");
        assertThat(result.get("entries")).hasSize(1);
        assertThat(result.get("entries").get(0).get("date").asString()).isEqualTo("2026-07-13");
        assertThat(result.get("entries").get(0).get("my_taste").asString()).isEqualTo("새콤하고 좋았음");
    }

    @Test
    @DisplayName("data-model §3.2: 미존재 slug는 오류 사유 반환 — 환각 필터")
    void getNoteRejectsUnknownSlug() {
        JsonNode result = mapper.readTree(execute("get_note", "{\"slug\":\"ghost-note\"}"));

        assertThat(result.get("error").asString()).contains("ghost-note", "list_notes");
    }

    @Test
    @DisplayName("data-model §3.5/AC-31: 기존 카드 파일이 있으면 그대로 postImage — 새 렌더 없음(파생물 재사용)")
    void sendEntryCardReusesExistingCard() throws IOException {
        noteRepository.put(note("2026-07-13-102030", "Ethiopia Chelbesa", "FroB",
                Aliases.empty(), LocalDate.of(2026, 7, 13)));
        Path existingCard = artifactDir.resolve("cards/2026-07-13-102030/2026-07-13.jpg");
        Files.createDirectories(existingCard.getParent());
        Files.write(existingCard, new byte[]{1});

        JsonNode result = mapper.readTree(execute("send_entry_card",
                "{\"slug\":\"2026-07-13-102030\",\"date\":\"2026-07-13\"}"));

        assertThat(renderer.rendered).isEmpty();
        assertThat(responder.postedImages).hasSize(1);
        assertThat(responder.postedImages.get(0).path()).isEqualTo(existingCard);
        assertThat(responder.postedImages.get(0).channelId()).isEqualTo(CHANNEL);
        assertThat(result.get("sent").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("data-model §3.5: 카드 파일 부재 시에만 그 엔트리 1장 증분 렌더 후 전송")
    void sendEntryCardRendersIncrementallyWhenCardMissing() {
        noteRepository.put(note("2026-07-13-102030", "Ethiopia Chelbesa", "FroB",
                Aliases.empty(), LocalDate.of(2026, 7, 13)));

        JsonNode result = mapper.readTree(execute("send_entry_card",
                "{\"slug\":\"2026-07-13-102030\",\"date\":\"2026-07-13\"}"));

        assertThat(renderer.rendered).containsExactly("2026-07-13-102030/2026-07-13");
        assertThat(responder.postedImages).hasSize(1);
        assertThat(responder.postedImages.get(0).path())
                .isEqualTo(artifactDir.resolve("cards/2026-07-13-102030/2026-07-13.jpg"));
        assertThat(result.get("sent").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("send_entry_card: 미존재 slug·미존재 엔트리 날짜는 오류 사유 반환 — 전송·렌더 없음(환각 필터)")
    void sendEntryCardRejectsUnknownTargets() {
        noteRepository.put(note("2026-07-13-102030", "Ethiopia Chelbesa", "FroB",
                Aliases.empty(), LocalDate.of(2026, 7, 13)));

        JsonNode unknownSlug = mapper.readTree(execute("send_entry_card",
                "{\"slug\":\"ghost-note\",\"date\":\"2026-07-13\"}"));
        JsonNode unknownDate = mapper.readTree(execute("send_entry_card",
                "{\"slug\":\"2026-07-13-102030\",\"date\":\"2026-07-01\"}"));
        JsonNode badDate = mapper.readTree(execute("send_entry_card",
                "{\"slug\":\"2026-07-13-102030\",\"date\":\"엊그제\"}"));

        assertThat(unknownSlug.get("error").asString()).contains("ghost-note");
        assertThat(unknownDate.get("error").asString()).contains("2026-07-01", "get_note");
        assertThat(badDate.get("error").asString()).contains("YYYY-MM-DD");
        assertThat(renderer.rendered).isEmpty();
        assertThat(responder.postedImages).isEmpty();
    }

    // ---- 헬퍼 ----

    private String execute(String toolName, String argumentsJson) {
        return agentTools.forTurn(CHANNEL).stream()
                .filter(tool -> tool.name().equals(toolName))
                .findFirst().orElseThrow()
                .executor().execute(argumentsJson);
    }

    private static List<String> required(JsonNode schema) {
        return schema.get("required").values().stream().map(JsonNode::asString).toList();
    }

    private static Note note(String slug, String coffeeName, String roastery, Aliases aliases,
                             LocalDate... entryDates) {
        List<Entry> entries = new ArrayList<>();
        for (LocalDate date : entryDates) {
            entries.add(new Entry(date, "새콤하고 좋았음", Rating.GOOD, null,
                    OffsetDateTime.parse("2026-07-14T10:00:00+09:00")));
        }
        return new Note(slug, Sourced.user(coffeeName), Sourced.user(roastery),
                Sourced.search("에티오피아"), null, null, Sourced.search(List.of("자스민")),
                aliases, List.of(), entries,
                OffsetDateTime.parse("2026-07-13T10:20:30+09:00"),
                OffsetDateTime.parse("2026-07-14T10:00:00+09:00"));
    }

    // ---- fakes (모듈 CLAUDE.md §5.2 — 외부 의존은 인터페이스 stub/fake) ----

    private static final class StubNoteRepository implements NoteRepository {
        private final Map<String, Note> notes = new LinkedHashMap<>();

        void put(Note note) {
            notes.put(note.slug(), note);
        }

        @Override
        public List<Note> findAll() {
            return List.copyOf(notes.values());
        }

        @Override
        public Optional<Note> findBySlug(String slug) {
            return Optional.ofNullable(notes.get(slug));
        }

        @Override
        public String nextAvailableSlug(String base) {
            throw new UnsupportedOperationException("읽기 tool은 slug를 만들지 않는다");
        }

        @Override
        public Note upsertEntry(String slug, NoteMeta meta, Entry entry, Aliases aliases) {
            throw new UnsupportedOperationException("읽기 tool은 노트를 쓰지 않는다(AC-Δ4)");
        }

        @Override
        public Note applyEdit(String slug, LocalDate targetDate, Note draft) {
            throw new UnsupportedOperationException("읽기 tool은 노트를 쓰지 않는다(AC-Δ4)");
        }
    }

    private static final class RecordingRenderer implements NoteRenderer {
        private final Path artifactDir;
        private final List<String> rendered = new ArrayList<>();

        private RecordingRenderer(Path artifactDir) {
            this.artifactDir = artifactDir;
        }

        @Override
        public void renderAll() {
            throw new UnsupportedOperationException("카드 재전송은 전체 리렌더를 부르지 않는다");
        }

        @Override
        public Path renderEntryCard(String slug, LocalDate date) {
            rendered.add(slug + "/" + date);
            Path card = artifactDir.resolve("cards").resolve(slug).resolve(date + ".jpg");
            try {
                Files.createDirectories(card.getParent());
                Files.write(card, new byte[]{1});
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
            return card;
        }

        @Override
        public void removeEntryCard(String slug, LocalDate date) {
            throw new UnsupportedOperationException("카드 재전송은 파생물을 지우지 않는다");
        }
    }

    private record PostedImage(String channelId, Path path, String caption) {
    }

    private static final class RecordingResponder implements SlackResponder {
        private final List<PostedImage> postedImages = new ArrayList<>();

        @Override
        public void post(String channelId, String text) {
            throw new UnsupportedOperationException("읽기·카드 tool은 평문 통지를 보내지 않는다");
        }

        @Override
        public void postImage(String channelId, Path imagePath, String caption) {
            postedImages.add(new PostedImage(channelId, imagePath, caption));
        }

        @Override
        public void finalizePreview(String channelId, PendingNote pending, String statusText) {
            throw new UnsupportedOperationException("읽기·카드 tool은 미리보기를 만지지 않는다");
        }
    }
}
