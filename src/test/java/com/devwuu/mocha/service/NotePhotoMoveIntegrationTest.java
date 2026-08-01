package com.devwuu.mocha.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.devwuu.mocha.SingleUser;
import com.devwuu.mocha.domain.Aliases;
import com.devwuu.mocha.domain.Brew;
import com.devwuu.mocha.domain.Entry;
import com.devwuu.mocha.domain.MatchInfo;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.NoteMeta;
import com.devwuu.mocha.domain.Rating;
import com.devwuu.mocha.domain.Source;
import com.devwuu.mocha.domain.Sourced;
import com.devwuu.mocha.domain.Tasting;
import com.devwuu.mocha.repository.LocalPhotoStore;
import com.devwuu.mocha.repository.NoteFolderName;
import com.devwuu.mocha.repository.jpa.NoteEntityRepository;
import com.devwuu.mocha.support.PostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * TΔ5b-2 (changes/0029-app-interface) — 날짜 이동이 <b>행과 파일을 함께</b> 옮기는지의 관통 검증
 * (ref: delta.md#D-12 ③, spec FR-10·FR-21, plan.md#ADR-79).
 *
 * <p><b>두 매체를 한 자리에서 보는 것이 이 클래스의 존재 이유다</b>: 색인은 실 Postgres에, 사진은
 * {@code @TempDir}의 실제 파일에 있고(백엔드 CLAUDE.md §5.2 — 어느 쪽도 인메모리로 대체하지 않는다),
 * 단언 대상은 <b>{@code note_photo.path}가 가리키는 자리에 파일이 실제로 있는가</b>이다. 층을 갈라
 * 보면(파일 이동은 {@code LocalPhotoStoreTest}, 행 이동은 {@code NoteTxServicePhotoTest}) 둘 다 그린이면서
 * 서로 다른 날짜를 가리키는 조합이 통과한다 — 그 조합이 정확히 TΔ8b 이월 (a)가 남긴 상태다.
 *
 * <p>병합·무충돌 두 갈래를 모두 본다. 갈리는 지점은 <b>파일명이 유일화되는가</b>({@code -N})이고,
 * 그것이 색인을 계산으로 따라갈 수 없게 만드는 이유다.
 */
@Transactional
class NotePhotoMoveIntegrationTest extends PostgresIntegrationTest {

    @Autowired
    EntityManager em;

    @Autowired
    NoteEntityRepository notes;

    @TempDir
    Path dataDir;

    private LocalPhotoStore photoStore;
    private NoteService service;
    private NoteTxService tx;

    @BeforeEach
    void setUp() {
        photoStore = new LocalPhotoStore(dataDir);
        tx = new NoteTxService(notes);
        // 별칭 생성기는 표본을 심는 커밋만 지나므로 빈 값으로 고정한다(검증 대상은 사진이다).
        // 아티팩트 경로는 이 경로가 지나지 않는다 — 수정에는 카드 단계가 없다(TΔ9).
        service = new NoteService(tx, (coffeeName, roastery) -> Aliases.empty(), photoStore, null);
    }

    @Test
    @DisplayName("D-12 ③: 무충돌 날짜 이동 — 행의 경로와 실제 파일 위치가 함께 새 날짜다")
    void moveWithoutCollisionKeepsRowsAndFilesTogether() {
        Note saved = seedWithPhoto(day(10), "bag.jpg");

        service.replaceEntry(saved.id(), day(10), entry(day(11)));
        flushAndClear();

        assertThat(tx.photoPaths(saved.id())).containsExactly(archivePath(saved, 11, "bag.jpg"));
        assertFilesExistAt(saved);
        // 옛 날짜 폴더는 접힌다 — 폴더=진실 불변식.
        assertThat(dateDir(saved, 10)).doesNotExist();
    }

    @Test
    @DisplayName("D-12 ③: 병합 이동 — 유일화된 실제 파일명이 그대로 색인에 남고 이동처 뒤로 이어진다")
    void mergeMoveRecordsUniquifiedFilename() {
        // 같은 이름으로 두 날짜에 사진이 있는 상황 — 날짜 세그먼트만 갈아 끼우면 두 행이 한 파일을 가리킨다.
        Note saved = seedWithPhoto(day(10), "bag.jpg");
        attachPhoto(saved, day(11), "bag.jpg");

        service.replaceEntry(saved.id(), day(10), entry(day(11)));
        flushAndClear();

        assertThat(tx.photoPaths(saved.id())).containsExactly(
                archivePath(saved, 11, "bag.jpg"),    // 그날 먼저 있던 사진이 앞(seq 0)
                archivePath(saved, 11, "bag-2.jpg")); // 옮겨 온 것이 뒤(seq 1)
        assertFilesExistAt(saved);
        assertThat(dateDir(saved, 10)).doesNotExist();
    }

    @Test
    @DisplayName("TΔ5b-2: 사진 없는 엔트리의 날짜 이동은 파일을 만들지도 지우지도 않는다")
    void moveWithoutPhotosTouchesNothing() {
        Note saved = service.commit(draft(null, entry(day(10))), MatchInfo.newNote());
        flushAndClear();

        service.replaceEntry(saved.id(), day(10), entry(day(11)));
        flushAndClear();

        assertThat(tx.photoPaths(saved.id())).isEmpty();
        assertThat(dataDir.resolve("photos")).doesNotExist();
    }

    // ────────────────────────────── 표본·헬퍼 ──────────────────────────────

    /** 스테이징 → 커밋으로 사진 1장을 실제 아카이브에 세운 노트. */
    private Note seedWithPhoto(LocalDate date, String filename) {
        photoStore.stage(SingleUser.ID, filename, jpeg(1));
        Note saved = service.commit(draft(null, entry(date)), MatchInfo.newNote());
        flushAndClear();
        return saved;
    }

    /** 이미 있는 노트의 다른 날짜에 사진 1장을 더한다 — 병합 갈래의 이동처를 만드는 자리. */
    private void attachPhoto(Note note, LocalDate date, String filename) {
        photoStore.stage(SingleUser.ID, filename, jpeg(2));
        service.commit(draft(note.id(), entry(date)), MatchInfo.existing(note.id(), date));
        flushAndClear();
    }

    /** 색인이 가리키는 자리에 파일이 실제로 있는가 — 이 클래스의 단언 본체다. */
    private void assertFilesExistAt(Note note) {
        for (String path : tx.photoPaths(note.id())) {
            assertThat(dataDir.resolve(path)).as(path).isRegularFile();
        }
    }

    private Path dateDir(Note note, int dayOfMonth) {
        return dataDir.resolve("photos").resolve(folder(note)).resolve("2026-07-%02d".formatted(dayOfMonth));
    }

    private static String archivePath(Note note, int dayOfMonth, String filename) {
        return "photos/" + folder(note) + "/2026-07-%02d/".formatted(dayOfMonth) + filename;
    }

    /** 접미 조립 규칙의 소유자는 하나다 — 여기서 다시 짜면 그 규칙이 갈린다(ADR-75). */
    private static String folder(Note note) {
        return NoteFolderName.of(note);
    }

    // 스테이징 열람이 매직바이트로 사진만 통과시킨다(ADR-66 ③) — 픽스처도 실제 JPEG 선두를 갖춘다.
    private static byte[] jpeg(int marker) {
        return new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) marker};
    }

    private static NoteMeta meta() {
        return new NoteMeta(
                new Sourced<>("커피베라 예가체프 G1", Source.USER), new Sourced<>("커피베라", Source.USER),
                List.of(), null, null, List.of());
    }

    private static Note draft(Long id, Entry entry) {
        NoteMeta meta = meta();
        return new Note(id, meta.coffeeName(), meta.roastery(), meta.beans(), meta.roastLevel(),
                meta.officialNotes(), Aliases.empty(), meta.sources(), List.of(entry), null, null);
    }

    private static Entry entry(LocalDate date) {
        return new Entry(date, List.of(new Brew(null, new Tasting("감상", "감상", Rating.GOOD))),
                OffsetDateTime.of(2000, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC));
    }

    private static LocalDate day(int dayOfMonth) {
        return LocalDate.of(2026, 7, dayOfMonth);
    }

    private void flushAndClear() {
        em.flush();
        em.clear();
    }
}
