package com.devwuu.mocha.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

/**
 * T4-1: LocalPhotoStore — 임시 디렉토리 실제 파일 I/O로 검증(CLAUDE.md §5.2).
 * <p>스테이징→커밋 경로 규칙, JSON엔 photos/ 상대 경로만(V-4), 취소 시 스테이징 폐기.
 */
class LocalPhotoStoreTest {

    private static final String USER = "U123";

    // 노트 폴더 접미 — 실 값은 NoteFolderName이 만든다(<id>-<로스터리>-<커피명>, changes/0028 TΔ7).
    // 이 저장소는 접미를 해석하지 않고 경로 세그먼트로만 쓰므로 생성 규칙은 NoteFolderNameTest가 소유한다.
    private static final String NOTE_FOLDER = "12-FroB-Ethiopia-Chelbesa";

    private Path dataDir;
    private LocalPhotoStore store;

    @BeforeEach
    void setUp(@TempDir Path dataDir) {
        this.dataDir = dataDir;
        this.store = new LocalPhotoStore(dataDir);
    }

    // 스테이징 열람이 매직바이트로 사진만 통과시키므로(TΔ2c/ADR-66 ③) 픽스처도 실제 JPEG 선두를 갖춘다.
    private static byte[] jpeg(int marker) {
        return new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) marker};
    }

    @Test
    @DisplayName("T4-1: 스테이징은 노트 폴더 미확정이라 photos/.staging/<user> 아래 원본을 보관한다")
    void stagesOriginalUnderStagingDir() {
        store.stage(USER, "bean.jpg", jpeg(1));

        Path staged = dataDir.resolve("photos").resolve(".staging").resolve(USER).resolve("bean.jpg");
        assertThat(staged).exists();
        // 아직 노트 트리로 새지 않는다 — 확인 전 커밋 금지(ADR-3 정신).
        assertThat(dataDir.resolve("photos").resolve("2026-07-11")).doesNotExist();
    }

    @Test
    @DisplayName("T4-1: commit은 스테이징을 photos/<노트 폴더>/<date>/로 옮기고 photos/ 상대 경로만 반환한다 (V-4)")
    void commitMovesToNoteFolderDateAndReturnsRelativePaths() throws IOException {
        store.stage(USER, "a.jpg", jpeg(1));
        store.stage(USER, "b.jpg", jpeg(2));

        List<String> relPaths = store.commit(USER, NOTE_FOLDER, "2026-07-11");

        // V-4: 상대 경로만 남는다. photos/ 로 시작하고 절대 경로·URL은 금지.
        assertThat(relPaths).containsExactly(
                "photos/" + NOTE_FOLDER + "/2026-07-11/a.jpg",
                "photos/" + NOTE_FOLDER + "/2026-07-11/b.jpg");
        assertThat(relPaths).allSatisfy(p -> {
            assertThat(p).startsWith("photos/");
            assertThat(Path.of(p).isAbsolute()).isFalse();
            assertThat(p).doesNotContain("://");
        });
        // 실제 원본이 최종 경로로 이동했고 스테이징은 비었다.
        Path finalDir = dataDir.resolve("photos").resolve(NOTE_FOLDER).resolve("2026-07-11");
        assertThat(Files.readAllBytes(finalDir.resolve("a.jpg"))).containsExactly(jpeg(1));
        assertThat(Files.readAllBytes(finalDir.resolve("b.jpg"))).containsExactly(jpeg(2));
        assertThat(dataDir.resolve("photos").resolve(".staging").resolve(USER)).doesNotExist();
    }

    @Test
    @DisplayName("T4-1: 같은 노트 폴더/date에 같은 파일명이 다시 오면 -N 접미로 유일화한다(재기록 충돌)")
    void deduplicatesCollidingFilenamesInTarget() {
        store.stage(USER, "photo.jpg", jpeg(1));
        store.commit(USER, NOTE_FOLDER, "2026-07-11");

        // 같은 날 재기록 — 파일명이 겹쳐도 덮어쓰지 않고 새 이름을 받는다.
        store.stage(USER, "photo.jpg", jpeg(2));
        List<String> second = store.commit(USER, NOTE_FOLDER, "2026-07-11");

        assertThat(second).containsExactly("photos/" + NOTE_FOLDER + "/2026-07-11/photo-2.jpg");
    }

    @Test
    @DisplayName("T4-1: 스테이징 내 파일명 충돌도 -N으로 유일화해 유실 없이 모두 보관한다")
    void deduplicatesCollidingFilenamesInStaging() {
        store.stage(USER, "img.jpg", jpeg(1));
        store.stage(USER, "img.jpg", jpeg(2));

        List<String> relPaths = store.commit(USER, "note", "2026-07-11");

        // 두 장 모두 유실 없이 보관됐는지가 핵심 — 충돌 접미 파일의 정렬 순서 자체는 의미 없음.
        assertThat(relPaths).containsExactlyInAnyOrder(
                "photos/note/2026-07-11/img.jpg",
                "photos/note/2026-07-11/img-2.jpg");
    }

    @Test
    @DisplayName("T4-1: 경로 이스케이프 문자가 섞인 파일명은 안전 문자로 정규화해 대상 디렉토리를 벗어나지 않는다")
    void sanitizesUnsafeFilenames() {
        store.stage(USER, "../../evil name.jpg", jpeg(9));

        List<String> relPaths = store.commit(USER, "note", "2026-07-11");

        assertThat(relPaths).hasSize(1);
        String rel = relPaths.get(0);
        assertThat(rel).startsWith("photos/note/2026-07-11/").doesNotContain(" ");
        // 파일명 세그먼트에 경로 구분자가 없어 대상 디렉토리 밖으로 탈출하지 않는다.
        String filename = rel.substring(rel.lastIndexOf('/') + 1);
        Path resolved = dataDir.resolve("photos").resolve("note").resolve("2026-07-11")
                .resolve(filename).normalize();
        assertThat(resolved.startsWith(dataDir.resolve("photos").resolve("note").resolve("2026-07-11")))
                .isTrue();
        assertThat(resolved).exists();
    }

    @Test
    @DisplayName("TΔ8b: deletePhotos는 지정 파일을 지우고 비게 된 날짜·노트 폴더를 접는다 (AC-6)")
    void deletePhotosRemovesFilesAndEmptyDirs() {
        store.stage(USER, "a.jpg", jpeg(1));
        store.stage(USER, "b.jpg", jpeg(2));
        List<String> relPaths = store.commit(USER, "yirga", "2026-07-08");

        store.deletePhotos(relPaths);

        // 파일이 빠졌으면 그 껍데기 폴더도 남기지 않는다 — 폴더=진실 불변식의 정리 몫.
        assertThat(dataDir.resolve("photos").resolve("yirga")).doesNotExist();
    }

    @Test
    @DisplayName("TΔ8b: 다른 날짜의 사진은 남는다 — 지울 대상은 넘긴 목록뿐이다")
    void deletePhotosIsScopedToGivenPaths() {
        store.stage(USER, "a.jpg", jpeg(1));
        List<String> july8 = store.commit(USER, "yirga", "2026-07-08");
        store.stage(USER, "b.jpg", jpeg(2));
        store.commit(USER, "yirga", "2026-07-09");

        store.deletePhotos(july8);

        assertThat(dataDir.resolve("photos").resolve("yirga").resolve("2026-07-08")).doesNotExist();
        // 노트 폴더는 아직 비지 않았으므로 접히지 않는다.
        assertThat(dataDir.resolve("photos").resolve("yirga").resolve("2026-07-09").resolve("b.jpg")).exists();
    }

    @Test
    @DisplayName("TΔ8b POLICY: 규약을 벗어난 경로는 지우지 않고 건너뛴다 — 경로가 곧 삭제 명령이다")
    void deletePhotosRefusesPathsOutsideArchive() throws IOException {
        Path outsider = dataDir.resolve("secret.txt");
        Files.writeString(outsider, "건드리면 안 되는 파일");
        store.stage(USER, "a.jpg", jpeg(1));
        List<String> kept = store.commit(USER, "yirga", "2026-07-08");

        store.deletePhotos(List.of("../secret.txt", "photos/../../secret.txt", "secret.txt"));

        assertThat(outsider).exists();
        // 정상 경로는 여전히 지울 수 있다 — 거부가 전체를 막는 것이 아니다.
        store.deletePhotos(kept);
        assertThat(dataDir.resolve("photos").resolve("yirga")).doesNotExist();
    }

    @Test
    @DisplayName("TΔ8b: 없는 파일·빈 목록은 무해하게 지나간다(멱등) — 두 번 지워도 같다")
    void deletePhotosIsIdempotent() {
        store.stage(USER, "a.jpg", jpeg(1));
        List<String> relPaths = store.commit(USER, "yirga", "2026-07-08");

        store.deletePhotos(relPaths);
        store.deletePhotos(relPaths);
        store.deletePhotos(List.of());
        store.deletePhotos(null);

        assertThat(dataDir.resolve("photos").resolve("yirga")).doesNotExist();
    }

    @Test
    @DisplayName("TΔ3(0014): moveTastingDayPhotos는 옛 날짜 폴더 파일 전부를 새 날짜로 옮기고 빈 옛 폴더를 제거한다 (AC-Δ4)")
    void moveTastingDayPhotosMovesAllFilesAndRemovesEmptySource() {
        store.stage(USER, "a.jpg", jpeg(1));
        store.stage(USER, "b.jpg", jpeg(2));
        store.commit(USER, "yirga", "2026-07-08");

        Map<String, String> moved = store.moveTastingDayPhotos("yirga", "2026-07-08", "2026-07-09");

        Path from = dataDir.resolve("photos").resolve("yirga").resolve("2026-07-08");
        Path to = dataDir.resolve("photos").resolve("yirga").resolve("2026-07-09");
        assertThat(from).doesNotExist(); // 빈 옛 폴더 제거(폴더=진실).
        assertThat(to.resolve("a.jpg")).exists();
        assertThat(to.resolve("b.jpg")).exists();
        // TΔ5b-2: 옮긴 자리를 돌려준다 — 색인(note_photo)이 이 답을 그대로 받아 적는다. V-4 어휘 유지.
        assertThat(moved).containsExactly(
                entry("photos/yirga/2026-07-08/a.jpg", "photos/yirga/2026-07-09/a.jpg"),
                entry("photos/yirga/2026-07-08/b.jpg", "photos/yirga/2026-07-09/b.jpg"));
    }

    @Test
    @DisplayName("TΔ3(0014): 이동처에 같은 이름 파일이 있으면 -N 접미로 병합해 유실 없이 보관한다 (AC-Δ4)")
    void moveTastingDayPhotosMergesCollidingFilenames() throws IOException {
        // 새 날짜 폴더에 이미 photo.jpg가 있는 상태(그 날짜에 먼저 올린 사진).
        store.stage(USER, "photo.jpg", jpeg(9));
        store.commit(USER, "yirga", "2026-07-09");
        // 옛 날짜 폴더에도 같은 이름 photo.jpg.
        store.stage(USER, "photo.jpg", jpeg(1));
        store.commit(USER, "yirga", "2026-07-08");

        Map<String, String> moved = store.moveTastingDayPhotos("yirga", "2026-07-08", "2026-07-09");

        Path to = dataDir.resolve("photos").resolve("yirga").resolve("2026-07-09");
        // 기존 파일은 덮이지 않고 이동분은 -2로 유일화 — 둘 다 보존된다.
        assertThat(Files.readAllBytes(to.resolve("photo.jpg"))).containsExactly(jpeg(9));
        assertThat(Files.readAllBytes(to.resolve("photo-2.jpg"))).containsExactly(jpeg(1));
        assertThat(dataDir.resolve("photos").resolve("yirga").resolve("2026-07-08")).doesNotExist();
        // TΔ5b-2: 이름이 바뀌는 갈래라 색인이 계산으로는 못 따라온다 — 실제 자리를 여기서만 알 수 있다.
        assertThat(moved).containsExactly(
                entry("photos/yirga/2026-07-08/photo.jpg", "photos/yirga/2026-07-09/photo-2.jpg"));
    }

    @Test
    @DisplayName("TΔ3(0014): 원본 폴더가 없으면 아무 일도 하지 않는다(사진 없이 날짜만 이동한 엔트리) — no-op")
    void moveTastingDayPhotosNoOpWhenSourceAbsent() {
        // 예외 없이 조용히 통과하고, 대상 폴더를 새로 만들지도 않는다.
        assertThat(store.moveTastingDayPhotos("yirga", "2026-07-08", "2026-07-09")).isEmpty();

        assertThat(dataDir.resolve("photos").resolve("yirga").resolve("2026-07-08")).doesNotExist();
        assertThat(dataDir.resolve("photos").resolve("yirga").resolve("2026-07-09")).doesNotExist();
    }

    @Test
    @DisplayName("TΔ2c(0025): 스테이징에 섞인 비사진 파일은 readStaged·commit 어디에도 들어가지 않는다 (AC-Δ2 ③)")
    void excludesNonPhotoFilesFromStagedReadAndCommit() throws IOException {
        store.stage(USER, "bean.jpg", jpeg(1));
        // Finder가 흘린 .DS_Store — stage() 입구 게이트를 거치지 않고 파일시스템에서 직접 유입되는 경로.
        Path staging = dataDir.resolve("photos").resolve(".staging").resolve(USER);
        Files.write(staging.resolve(".DS_Store"), new byte[]{0, 0, 0, 1, 'B', 'u', 'd', '1'});

        // OCR 배치가 비사진 바이트를 vision에 넘기지 않는다.
        assertThat(store.readStaged(USER)).extracting(StagedImage::name).containsExactly("bean.jpg");

        List<String> relPaths = store.commit(USER, "yirga", "2026-07-27");

        // 아카이브에도 새지 않고, 정상 사진은 그대로 커밋된다.
        assertThat(relPaths).containsExactly("photos/yirga/2026-07-27/bean.jpg");
        Path archived = dataDir.resolve("photos").resolve("yirga").resolve("2026-07-27");
        assertThat(archived.resolve("bean.jpg")).exists();
        assertThat(archived.resolve(".DS_Store")).doesNotExist();
        // 걸러진 잔재까지 정리돼 스테이징은 소멸한다(커밋 후 스테이징 없음 불변식).
        assertThat(staging).doesNotExist();
    }

    @Test
    @DisplayName("T4-1: discard는 스테이징만 폐기한다([취소]/TTL 정리)")
    void discardRemovesStagingOnly() {
        store.stage(USER, "x.jpg", jpeg(1));

        store.discard(USER);

        assertThat(dataDir.resolve("photos").resolve(".staging").resolve(USER)).doesNotExist();
        // commit 대상이 없으므로 빈 목록.
        assertThat(store.commit(USER, "note", "2026-07-11")).isEmpty();
    }
}
