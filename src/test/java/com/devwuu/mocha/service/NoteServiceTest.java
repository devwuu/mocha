package com.devwuu.mocha.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.devwuu.mocha.domain.Aliases;
import com.devwuu.mocha.domain.Bean;
import com.devwuu.mocha.domain.Brew;
import com.devwuu.mocha.domain.Entry;
import com.devwuu.mocha.domain.MatchInfo;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.NoteMeta;
import com.devwuu.mocha.domain.Rating;
import com.devwuu.mocha.domain.Source;
import com.devwuu.mocha.domain.Sourced;
import com.devwuu.mocha.domain.Tasting;
import com.devwuu.mocha.llm.AliasGenerator;
import com.devwuu.mocha.repository.NoteRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * TΔ4a (changes/0029-app-interface) — {@link NoteService}가 지는 <b>판단</b>의 검증.
 *
 * <p>여기서 보는 것은 셋이다: 신규/기존 분기(별칭 <b>생성</b> 콜이 언제 나가는가) · V-13 관측 표기 축적
 * (무엇이 <b>더해져</b> 저장소로 내려가는가) · V-9 커피명 불변. 행이 어떻게 움직이는지는 대상이 아니다 —
 * 그것은 {@code JpaNoteRepositoryEditTest}·{@code JpaNoteRepositoryUpsertEntryTest}가 실 Postgres로
 * 소유한다(백엔드 CLAUDE.md §5.2).
 *
 * <p>저장소·별칭 생성기는 손 fake다 — 이 저장소에 {@code org.mockito} 참조가 0건인 관행을 유지한다
 * (0029 tasks TΔ4b, 사용자 확정 2026-08-01).
 */
class NoteServiceTest {

    private final RecordingNoteRepository repository = new RecordingNoteRepository();
    private final RecordingAliasGenerator aliasGenerator = new RecordingAliasGenerator();
    private final NoteService service = new NoteService(repository, aliasGenerator);

    @Nested
    @DisplayName("커밋 — 신규/기존 분기")
    class Commit {

        @Test
        @DisplayName("ADR-37: 신규 노트 커밋은 별칭을 LLM 1콜로 생성해 심는다 — 노트당 평생 1회")
        void newNoteGeneratesAliases() {
            aliasGenerator.returns(new Aliases(List.of("Yirgacheffe G1"), List.of("Coffee Vera")));

            service.commit(draft(null, "커피베라 예가체프 G1", "커피베라"), MatchInfo.newNote());

            assertThat(aliasGenerator.calls).hasSize(1);
            assertThat(aliasGenerator.calls.getFirst())
                    .containsExactly("커피베라 예가체프 G1", "커피베라");
            assertThat(repository.lastNoteId).isNull();
            assertThat(repository.lastAliases.coffeeName()).containsExactly("Yirgacheffe G1");
            assertThat(repository.lastAliases.roastery()).containsExactly("Coffee Vera");
        }

        @Test
        @DisplayName("TΔ4a 선행 확인: AliasGenerator 배선이 살아난다 — 커밋이 유일한 호출부다")
        void aliasGeneratorIsWiredAgain() {
            // TΔ4에서 SlackCommitHandler가 사라지며 이 협력자는 빈만 등록되고 아무도 부르지 않는
            // 상태가 됐다. 이 task가 되살리는 배선이라 판정에 포함한다(tasks TΔ4a ⚠️ 선행 확인).
            service.commit(draft(null, "커피", "로스터리"), MatchInfo.newNote());

            assertThat(aliasGenerator.calls).as("신규 커밋이 별칭 생성기를 부른다").isNotEmpty();
        }

        @Test
        @DisplayName("ADR-37: 기존 노트 커밋은 별칭 생성 콜을 하지 않는다 — 무콜 축적만")
        void existingNoteDoesNotCallGenerator() {
            repository.put(saved(7, "커피베라 예가체프 G1", "커피베라", Aliases.empty()));

            service.commit(draft(7L, "커피베라 예가체프 G1", "커피베라"), MatchInfo.existing(7L, day(10)));

            assertThat(aliasGenerator.calls).isEmpty();
        }

        @Test
        @DisplayName("V-13: 기존 노트의 다른 관측 표기는 축적되고, 저장소에는 늘어난 것만 내려간다")
        void accumulatesOnlyNewObservedAliases() {
            // 이미 별칭에 있는 표기를 다시 내려보내면 행이 중복되거나(유일성 위반) 다시 심으며 id가 밀려
            // 첫 등장 순서가 무너진다 — "늘어난 것만"이 저장소가 아니라 여기서 계산되는 이유다.
            repository.put(saved(7, "예가체프 G1", "커피베라",
                    new Aliases(List.of("Yirgacheffe G1"), List.of())));

            service.commit(draft(7L, "yirgacheffe  g1", "커피베라 성수"), MatchInfo.existing(7L, day(10)));

            assertThat(repository.lastAliases.coffeeName())
                    .as("정규화 일치분(Yirgacheffe G1)은 다시 내려가지 않는다").isEmpty();
            assertThat(repository.lastAliases.roastery()).containsExactly("커피베라 성수");
        }

        @Test
        @DisplayName("V-13: 노트 표시값과 정규화 일치하는 관측 표기는 별칭에 넣지 않는다")
        void observedValueSameAsCanonicalIsNotAccumulated() {
            repository.put(saved(7, "예가체프 G1", "커피베라", Aliases.empty()));

            service.commit(draft(7L, "예가체프  G1", "커피베라"), MatchInfo.existing(7L, day(10)));

            assertThat(repository.lastAliases.coffeeName()).isEmpty();
            assertThat(repository.lastAliases.roastery()).isEmpty();
        }

        @Test
        @DisplayName("커밋은 draft의 마지막 엔트리를 저장소로 넘긴다 — 노트 메타는 id/엔트리를 뺀 나머지")
        void commitPassesLatestEntryAndMeta() {
            service.commit(draft(null, "커피", "로스터리"), MatchInfo.newNote());

            assertThat(repository.lastEntry.date()).isEqualTo(day(10));
            assertThat(Sourced.valueOrNull(repository.lastMeta.coffeeName())).isEqualTo("커피");
            assertThat(Sourced.valueOrNull(repository.lastMeta.roastery())).isEqualTo("로스터리");
        }

        @Test
        @DisplayName("저장할 시음 엔트리가 없으면 거부 — 빈 커밋이 노트만 만들지 않는다")
        void commitRejectsDraftWithoutEntry() {
            Note empty = new Note(null, new Sourced<>("커피", Source.USER), null, List.of(), null, null,
                    Aliases.empty(), List.of(), List.of(), null, null);

            assertThatThrownBy(() -> service.commit(empty, MatchInfo.newNote()))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(repository.lastEntry).isNull();
        }

        @Test
        @DisplayName("plan §7: 별칭 생성 실패는 저장을 되돌리지 않는다 — 빈 별칭으로 수렴")
        void aliasGenerationFailureDoesNotBlockSave() {
            // 실패 수렴은 AliasGenerator 구현체 계약이라(빈 Aliases 반환) service는 그 값을 그대로 심는다.
            aliasGenerator.returns(Aliases.empty());

            service.commit(draft(null, "커피", "로스터리"), MatchInfo.newNote());

            assertThat(repository.lastAliases).isEqualTo(Aliases.empty());
            assertThat(repository.saves).isOne();
        }
    }

    @Nested
    @DisplayName("메타 수정 — V-9")
    class UpdateMeta {

        @Test
        @DisplayName("V-9: coffee_name 변경 시도는 거부 — 저장소 쓰기에 닿지 않는다")
        void rejectsCoffeeNameChange() {
            repository.put(saved(7, "커피베라 예가체프 G1", "커피베라", Aliases.empty()));

            assertThatThrownBy(() -> service.updateMeta(7, meta("커피베라 예가체프 G2", "커피베라")))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThat(repository.metaUpdates).isZero();
        }

        @Test
        @DisplayName("V-9: 같은 커피명이면 통과 — 로스터리 등 나머지는 수정 범위다")
        void allowsSameCoffeeName() {
            repository.put(saved(7, "커피베라 예가체프 G1", "커피베라", Aliases.empty()));

            service.updateMeta(7, meta("커피베라 예가체프 G1", "커피베라 성수점"));

            assertThat(repository.metaUpdates).isOne();
        }

        @Test
        @DisplayName("plan §7: 대상 노트 소실은 IllegalStateException")
        void rejectsMissingNote() {
            assertThatThrownBy(() -> service.updateMeta(99, meta("커피", "로스터리")))
                    .isInstanceOf(IllegalStateException.class);
        }
    }

    // ────────────────────────────── 표본 ──────────────────────────────

    private static LocalDate day(int dayOfMonth) {
        return LocalDate.of(2026, 7, dayOfMonth);
    }

    private static Entry entry() {
        return new Entry(day(10), List.of(new Brew(null, new Tasting("감상", "감상", Rating.GOOD))),
                OffsetDateTime.parse("2026-07-10T10:00:00+09:00"));
    }

    private static NoteMeta meta(String coffeeName, String roastery) {
        return new NoteMeta(
                new Sourced<>(coffeeName, Source.USER), new Sourced<>(roastery, Source.USER),
                List.of(new Bean(new Sourced<>("에티오피아 예가체프", Source.SEARCH), null)),
                null, null, List.of());
    }

    /** 폼 확정 내용 — 이번 시음 엔트리 1건을 실은 Note. {@code id}가 있으면 그 노트에 병합된다. */
    private static Note draft(Long id, String coffeeName, String roastery) {
        NoteMeta meta = meta(coffeeName, roastery);
        return new Note(id, meta.coffeeName(), meta.roastery(), meta.beans(), meta.roastLevel(),
                meta.officialNotes(), Aliases.empty(), meta.sources(), List.of(entry()), null, null);
    }

    /** 이미 저장된 노트 — 축적·V-9 대조의 원본이 된다. */
    private static Note saved(long id, String coffeeName, String roastery, Aliases aliases) {
        NoteMeta meta = meta(coffeeName, roastery);
        return new Note(id, meta.coffeeName(), meta.roastery(), meta.beans(), meta.roastLevel(),
                meta.officialNotes(), aliases, meta.sources(), List.of(entry()), null, null);
    }

    // ────────────────────────────── fake ──────────────────────────────

    /** 저장소 fake — 무엇이 내려갔는지만 기록한다. 행 조율은 이 테스트의 대상이 아니다. */
    private static final class RecordingNoteRepository implements NoteRepository {
        private final Map<Long, Note> notes = new LinkedHashMap<>();
        private Long lastNoteId;
        private NoteMeta lastMeta;
        private Entry lastEntry;
        private Aliases lastAliases;
        private int saves;
        private int metaUpdates;

        void put(Note note) {
            notes.put(note.id(), note);
        }

        @Override
        public List<Note> findAll() {
            return List.copyOf(notes.values());
        }

        @Override
        public Optional<Note> findById(long id) {
            return Optional.ofNullable(notes.get(id));
        }

        @Override
        public Note upsertEntry(Long noteId, NoteMeta meta, Entry entry, Aliases aliases) {
            lastNoteId = noteId;
            lastMeta = meta;
            lastEntry = entry;
            lastAliases = aliases;
            saves++;
            return notes.getOrDefault(noteId, saved(noteId == null ? 1 : noteId,
                    Sourced.valueOrNull(meta.coffeeName()), Sourced.valueOrNull(meta.roastery()), aliases));
        }

        @Override
        public Note updateMeta(long noteId, NoteMeta meta) {
            lastMeta = meta;
            metaUpdates++;
            return notes.get(noteId);
        }

        @Override
        public Note replaceEntry(long noteId, LocalDate targetDate, Entry entry) {
            lastEntry = entry;
            return notes.get(noteId);
        }

        @Override
        public void delete(long id) {
            notes.remove(id);
        }
    }

    /** 별칭 생성기 fake — 콜 여부가 검증 대상이라 인자를 그대로 기록한다. */
    private static final class RecordingAliasGenerator implements AliasGenerator {
        private final List<List<String>> calls = new ArrayList<>();
        private Aliases result = Aliases.empty();

        void returns(Aliases result) {
            this.result = result;
        }

        @Override
        public Aliases generate(String coffeeName, String roastery) {
            List<String> args = new ArrayList<>();
            args.add(coffeeName);
            args.add(roastery);
            calls.add(args);
            return result;
        }
    }
}
