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
import com.devwuu.mocha.repository.NoteFolderName;
import com.devwuu.mocha.repository.RecordingPhotoStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * TΔ4a·TΔ4c (changes/0029-app-interface) — {@link NoteService}가 지는 <b>외부 IO 오케스트레이션</b>의 검증.
 *
 * <p>여기서 보는 것은 <b>순서와 발동</b>뿐이다: 별칭 생성 콜이 <b>언제</b> 나가는가, 그 결과가 어떤 값으로
 * 아래에 내려가는가, 트랜잭션을 열기 전에 걸러지는 입력은 무엇인가. <b>비즈니스 규칙은 대상이 아니다</b> —
 * V-9(커피명 불변)·V-13(별칭 축적)·V-10(날짜 이동)·ADR-4(병합)는 전부 {@link NoteTxService}에 있고
 * {@code NoteTxServiceCommitTest}·{@code NoteTxServiceEditTest}가 <b>실 Postgres로</b> 소유한다
 * (백엔드 CLAUDE.md §5.2, delta D-9).
 *
 * <p>TΔ4c에서 이 클래스의 검증 범위가 좁아진 것이 축 재정의의 관측 지점이다 — 구 축("판단 vs 행 조율")에서
 * 여기 있던 V-9·V-13이 트랜잭션 계층으로 내려갔다.
 *
 * <p>협력자는 손 fake다 — 이 저장소에 {@code org.mockito} 참조가 0건인 관행을 유지한다(사용자 확정
 * 2026-08-01). 포트가 걷힌 뒤(ADR-76) 아래층 fake는 <b>구상 타입을 상속</b>한다.
 */
class NoteServiceTest {

    @TempDir
    Path artifactDir;

    private final RecordingTxService tx = new RecordingTxService();
    private final RecordingAliasGenerator aliasGenerator = new RecordingAliasGenerator();
    private final RecordingPhotoStore photoStore = new RecordingPhotoStore();
    private NoteService service;

    @BeforeEach
    void setUp() {
        service = new NoteService(tx, aliasGenerator, photoStore, artifactDir);
    }

    @Nested
    @DisplayName("커밋 — 외부 콜의 발동과 순서")
    class Commit {

        @Test
        @DisplayName("ADR-37: 신규 노트 커밋은 별칭을 LLM 1콜로 생성해 내려보낸다 — 노트당 평생 1회")
        void newNoteGeneratesAliases() {
            aliasGenerator.returns(new Aliases(List.of("Yirgacheffe G1"), List.of("Coffee Vera")));

            service.commit(draft(null, "커피베라 예가체프 G1", "커피베라"), MatchInfo.newNote());

            assertThat(aliasGenerator.calls).hasSize(1);
            assertThat(aliasGenerator.calls.getFirst())
                    .containsExactly("커피베라 예가체프 G1", "커피베라");
            assertThat(tx.lastNoteId).isNull();
            assertThat(tx.lastGenerated.coffeeName()).containsExactly("Yirgacheffe G1");
            assertThat(tx.lastGenerated.roastery()).containsExactly("Coffee Vera");
        }

        @Test
        @DisplayName("TΔ4a 선행 확인: AliasGenerator 배선이 살아난다 — 커밋이 유일한 호출부다")
        void aliasGeneratorIsWiredAgain() {
            // TΔ4에서 SlackCommitHandler가 사라지며 이 협력자는 빈만 등록되고 아무도 부르지 않는
            // 상태가 됐다. TΔ4a가 되살린 배선이라 판정에 남긴다(tasks TΔ4a ⚠️ 선행 확인).
            service.commit(draft(null, "커피", "로스터리"), MatchInfo.newNote());

            assertThat(aliasGenerator.calls).as("신규 커밋이 별칭 생성기를 부른다").isNotEmpty();
        }

        @Test
        @DisplayName("ADR-37: 기존 노트 커밋은 생성 콜을 하지 않고 null을 내려보낸다 — 축적은 트랜잭션 안의 일")
        void existingNoteDoesNotCallGenerator() {
            // TΔ4c: null이 곧 "생성하지 않았다"는 신호이고, 그 경우 무엇을 더할지(V-13)는 저장된 별칭을
            // 읽어야 알 수 있어 NoteTxService가 같은 트랜잭션 안에서 계산한다.
            service.commit(draft(7L, "커피베라 예가체프 G1", "커피베라"), MatchInfo.existing(7L, day(10)));

            assertThat(aliasGenerator.calls).isEmpty();
            assertThat(tx.lastGenerated).as("생성하지 않았음을 null로 알린다").isNull();
            assertThat(tx.lastNoteId).isEqualTo(7L);
        }

        @Test
        @DisplayName("커밋은 draft의 마지막 엔트리를 아래로 넘긴다 — 노트 메타는 id/엔트리를 뺀 나머지")
        void commitPassesLatestEntryAndMeta() {
            service.commit(draft(null, "커피", "로스터리"), MatchInfo.newNote());

            assertThat(tx.lastEntry.date()).isEqualTo(day(10));
            assertThat(Sourced.valueOrNull(tx.lastMeta.coffeeName())).isEqualTo("커피");
            assertThat(Sourced.valueOrNull(tx.lastMeta.roastery())).isEqualTo("로스터리");
        }

        @Test
        @DisplayName("저장할 시음 엔트리가 없으면 트랜잭션을 열기 전에 거부 — 외부 콜도 나가지 않는다")
        void commitRejectsDraftWithoutEntry() {
            Note empty = new Note(null, new Sourced<>("커피", Source.USER), null, List.of(), null, null,
                    Aliases.empty(), List.of(), List.of(), null, null);

            assertThatThrownBy(() -> service.commit(empty, MatchInfo.newNote()))
                    .isInstanceOf(IllegalArgumentException.class);
            // TΔ4c: 입력 검증이 외부 콜보다 앞이라는 것이 이 계층의 순서 계약이다 — 저장할 것이 없는
            // 요청으로 LLM 과금이 나가거나 쓰기 구간에 들어가지 않는다.
            assertThat(aliasGenerator.calls).isEmpty();
            assertThat(tx.commits).isZero();
        }

        @Test
        @DisplayName("plan §7: 별칭 생성 실패는 저장을 되돌리지 않는다 — 빈 별칭으로 수렴")
        void aliasGenerationFailureDoesNotBlockSave() {
            // 실패 수렴은 AliasGenerator 구현체 계약이라(빈 Aliases 반환) service는 그 값을 그대로 내린다.
            aliasGenerator.returns(Aliases.empty());

            service.commit(draft(null, "커피", "로스터리"), MatchInfo.newNote());

            assertThat(tx.lastGenerated).isEqualTo(Aliases.empty());
            assertThat(tx.commits).isOne();
        }
    }

    @Nested
    @DisplayName("외부 IO 없는 유스케이스 — 트랜잭션 계층으로 통과")
    class Delegation {

        // delta D-9: 외부 IO가 없어도 상위 계층이 잡는 타입을 하나로 유지한다(사용자 확정 2026-08-01).
        // 여기서 보는 것은 "인자가 변형 없이 통과하는가"뿐이고, 규칙 자체는 NoteTxService 테스트가 진다.

        @Test
        @DisplayName("수정·삭제·조회는 트랜잭션 계층에 그대로 위임된다 — 이 계층이 값을 건드리지 않는다")
        void passesThroughWithoutTouchingValues() {
            tx.put(saved(7, "커피베라 예가체프 G1", "커피베라", Aliases.empty()));
            NoteMeta meta = meta("커피베라 예가체프 G1", "커피베라 성수점");

            service.updateMeta(7, meta);
            assertThat(tx.lastMeta).isSameAs(meta);

            Entry entry = entry();
            service.replaceEntry(7, day(9), entry);
            assertThat(tx.lastEntry).isSameAs(entry);
            assertThat(tx.lastTargetDate).isEqualTo(day(9));

            assertThat(service.findById(7)).isPresent();
            assertThat(service.findAll()).hasSize(1);

            service.delete(7);
            assertThat(service.findAll()).isEmpty();
        }

        @Test
        @DisplayName("백엔드 CLAUDE.md §3: 위임 경로에서 외부 콜이 나가지 않는다")
        void delegationDoesNotCallExternalIo() {
            tx.put(saved(7, "커피", "로스터리", Aliases.empty()));

            service.updateMeta(7, meta("커피", "로스터리 2호점"));
            service.replaceEntry(7, day(9), entry());
            service.delete(7);

            assertThat(aliasGenerator.calls).isEmpty();
        }
    }

    @Nested
    @DisplayName("TΔ8b 사진 아카이브 — 확정과 정리")
    class Photos {

        @Test
        @DisplayName("커밋은 저장 뒤에 스테이징을 아카이브로 옮기고 색인을 남긴다 — 폴더는 저장이 발급한 id로 시작한다")
        void commitArchivesStagedPhotos() {
            photoStore.committedPaths = List.of("photos/1-로스터리-커피/2026-07-10/bag.jpg");

            Note saved = service.commit(draft(null, "커피", "로스터리"), MatchInfo.newNote());

            // 접미가 <id>-…라 신규 노트는 INSERT 뒤에야 경로가 정해진다 — 그래서 확정이 커밋 다음이다.
            assertThat(photoStore.committedFolders).containsExactly(NoteFolderName.of(saved));
            assertThat(photoStore.committedDates).containsExactly("2026-07-10");
            assertThat(tx.attached).containsExactly("photos/1-로스터리-커피/2026-07-10/bag.jpg");
            assertThat(tx.lastAttachNoteId).isEqualTo(saved.id());
            assertThat(tx.lastAttachDate).isEqualTo(day(10));
        }

        @Test
        @DisplayName("0028 한계 해소: 사진이 이미 있는 노트는 저장된 경로의 폴더를 되읽는다 — 재계산하지 않는다")
        void existingNoteReusesRecordedFolder() {
            // 폴더명은 생성 시점 스냅샷이다(ADR-75). 로스터리가 그 뒤에 수정됐으므로 지금 이름으로
            // 재계산하면 옛 폴더와 갈리고, 같은 노트의 사진이 두 폴더로 흩어진다.
            tx.put(saved(7, "커피", "새 로스터리", Aliases.empty()));
            tx.photos.add("photos/7-옛로스터리-커피/2026-07-09/bag.jpg");
            photoStore.committedPaths = List.of("photos/7-옛로스터리-커피/2026-07-10/bag.jpg");

            service.commit(draft(7L, "커피", "새 로스터리"), MatchInfo.existing(7L, day(10)));

            assertThat(photoStore.committedFolders).containsExactly("7-옛로스터리-커피");
        }

        @Test
        @DisplayName("스테이징이 비면 색인 행을 만들지 않는다 — 사진 없는 저장이 정상 경로다")
        void commitWithoutPhotosAttachesNothing() {
            service.commit(draft(null, "커피", "로스터리"), MatchInfo.newNote());

            assertThat(tx.attached).isEmpty();
        }

        @Test
        @DisplayName("백엔드 CLAUDE.md §3: 사진 확정 실패는 저장을 되돌리지 않는다 — 노트는 남고 사진은 스테이징에 남는다")
        void photoFailureDoesNotRollBackSave() {
            photoStore.commitFailure = new IllegalStateException("디스크 실패");

            Note saved = service.commit(draft(null, "커피", "로스터리"), MatchInfo.newNote());

            assertThat(saved.id()).isNotNull();
            assertThat(tx.commits).isOne();
            assertThat(tx.attached).isEmpty();
        }

        @Test
        @DisplayName("AC-6: 삭제는 사진 아카이브와 카드 파생물까지 지운다 — 0028이 A2로 미룬 결정")
        void deleteRemovesPhotoAndCardFiles() throws IOException {
            Note note = saved(7, "커피", "로스터리", Aliases.empty());
            tx.put(note);
            tx.photos.add("photos/7-로스터리-커피/2026-07-10/bag.jpg");
            Path cardsDir = artifactDir.resolve("cards").resolve(NoteFolderName.of(note));
            Files.createDirectories(cardsDir);
            Files.writeString(cardsDir.resolve("2026-07-10-taste-1.jpg"), "카드");

            service.delete(7);

            assertThat(photoStore.deleted).containsExactly("photos/7-로스터리-커피/2026-07-10/bag.jpg");
            assertThat(cardsDir).doesNotExist();
        }

        @Test
        @DisplayName("POLICY: 파일 정리가 실패해도 행 삭제는 유지된다 — 순서가 행 먼저이기 때문이다")
        void fileCleanupFailureDoesNotResurrectNote() {
            tx.put(saved(7, "커피", "로스터리", Aliases.empty()));
            tx.photos.add("photos/7-로스터리-커피/2026-07-10/bag.jpg");
            NoteService failing = new NoteService(tx, aliasGenerator, new RecordingPhotoStore() {
                @Override
                public void deletePhotos(List<String> relativePaths) {
                    throw new IllegalStateException("파일 시스템 실패");
                }
            }, artifactDir);

            failing.delete(7);

            assertThat(tx.deletes).isOne();
            assertThat(tx.findById(7)).isEmpty();
        }

        @Test
        @DisplayName("사진이 없는 노트 삭제는 파일 정리를 부르지 않는다 — 지울 것이 없다")
        void deleteWithoutPhotosIsHarmless() {
            tx.put(saved(7, "커피", "로스터리", Aliases.empty()));

            service.delete(7);

            assertThat(photoStore.deleted).isEmpty();
            assertThat(tx.deletes).isOne();
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

    /** 이미 저장된 노트 — 위임 경로의 조회 대상이 된다. */
    private static Note saved(long id, String coffeeName, String roastery, Aliases aliases) {
        NoteMeta meta = meta(coffeeName, roastery);
        return new Note(id, meta.coffeeName(), meta.roastery(), meta.beans(), meta.roastLevel(),
                meta.officialNotes(), aliases, meta.sources(), List.of(entry()), null, null);
    }

    // ────────────────────────────── fake ──────────────────────────────

    /**
     * 트랜잭션 계층 fake — <b>무엇이 어떤 값으로 내려갔는지만</b> 기록한다. 규칙이 행에 어떻게 떨어지는지는
     * 이 테스트의 대상이 아니다(실 Postgres 통합 테스트가 소유).
     * <p>공개 메서드를 전부 덮으므로 상위 생성자에 넘긴 {@code null} 행 저장소는 도달하지 않는다.
     */
    private static final class RecordingTxService extends NoteTxService {
        private final Map<Long, Note> notes = new LinkedHashMap<>();
        private Long lastNoteId;
        private NoteMeta lastMeta;
        private Entry lastEntry;
        private LocalDate lastTargetDate;
        private Aliases lastGenerated;
        private int commits;
        private int deletes;

        /** 이미 색인된 사진 경로 — 폴더 접미를 되읽는 입력이자 삭제가 지울 목록이다(TΔ8b). */
        private final List<String> photos = new ArrayList<>();
        private final List<String> attached = new ArrayList<>();
        private Long lastAttachNoteId;
        private LocalDate lastAttachDate;

        RecordingTxService() {
            super(null);
        }

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
        public Note commit(Long noteId, NoteMeta meta, Entry entry, Aliases generated) {
            lastNoteId = noteId;
            lastMeta = meta;
            lastEntry = entry;
            lastGenerated = generated;
            commits++;
            return notes.getOrDefault(noteId, saved(noteId == null ? 1 : noteId,
                    Sourced.valueOrNull(meta.coffeeName()), Sourced.valueOrNull(meta.roastery()),
                    generated == null ? Aliases.empty() : generated));
        }

        @Override
        public Note updateMeta(long noteId, NoteMeta meta) {
            lastMeta = meta;
            return notes.get(noteId);
        }

        @Override
        public Note replaceEntry(long noteId, LocalDate targetDate, Entry entry) {
            lastTargetDate = targetDate;
            lastEntry = entry;
            return notes.get(noteId);
        }

        @Override
        public void delete(long id) {
            notes.remove(id);
            deletes++;
        }

        @Override
        public void attachPhotos(long noteId, LocalDate tastedOn, List<String> paths) {
            lastAttachNoteId = noteId;
            lastAttachDate = tastedOn;
            attached.addAll(paths);
            photos.addAll(paths);
        }

        @Override
        public List<String> photoPaths(long noteId) {
            return List.copyOf(photos);
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
