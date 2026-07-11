package com.devwuu.mocha.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T4-1: LocalPhotoStore — 임시 디렉토리 실제 파일 I/O로 검증(CLAUDE.md §5.2).
 * <p>스테이징→커밋 경로 규칙, JSON엔 photos/ 상대 경로만(V-4), 취소 시 스테이징 폐기.
 */
class LocalPhotoStoreTest {

    private static final String USER = "U123";

    private Path dataDir;
    private LocalPhotoStore store;

    @BeforeEach
    void setUp(@TempDir Path dataDir) {
        this.dataDir = dataDir;
        this.store = new LocalPhotoStore(dataDir);
    }

    @Test
    @DisplayName("T4-1: 스테이징은 slug 미확정이라 photos/.staging/<user> 아래 원본을 보관한다")
    void stagesOriginalUnderStagingDir() {
        store.stage(USER, "bean.jpg", new byte[]{1, 2, 3});

        Path staged = dataDir.resolve("photos").resolve(".staging").resolve(USER).resolve("bean.jpg");
        assertThat(staged).exists();
        // 아직 노트 트리로 새지 않는다 — 확인 전 커밋 금지(ADR-3 정신).
        assertThat(dataDir.resolve("photos").resolve("2026-07-11")).doesNotExist();
    }

    @Test
    @DisplayName("T4-1: commit은 스테이징을 photos/<slug>/<date>/로 옮기고 photos/ 상대 경로만 반환한다 (V-4)")
    void commitMovesToSlugDateAndReturnsRelativePaths() throws IOException {
        store.stage(USER, "a.jpg", new byte[]{1});
        store.stage(USER, "b.jpg", new byte[]{2});

        List<String> relPaths = store.commit(USER, "2026-07-11", "2026-07-11");

        // V-4: JSON에는 photos/ 로 시작하는 상대 경로만. 절대 경로·URL 금지.
        assertThat(relPaths).containsExactly(
                "photos/2026-07-11/2026-07-11/a.jpg",
                "photos/2026-07-11/2026-07-11/b.jpg");
        assertThat(relPaths).allSatisfy(p -> {
            assertThat(p).startsWith("photos/");
            assertThat(Path.of(p).isAbsolute()).isFalse();
            assertThat(p).doesNotContain("://");
        });
        // 실제 원본이 최종 경로로 이동했고 스테이징은 비었다.
        Path finalDir = dataDir.resolve("photos").resolve("2026-07-11").resolve("2026-07-11");
        assertThat(Files.readAllBytes(finalDir.resolve("a.jpg"))).containsExactly(1);
        assertThat(Files.readAllBytes(finalDir.resolve("b.jpg"))).containsExactly(2);
        assertThat(dataDir.resolve("photos").resolve(".staging").resolve(USER)).doesNotExist();
    }

    @Test
    @DisplayName("T4-1: 같은 slug/date에 같은 파일명이 다시 오면 -N 접미로 유일화한다(재기록 충돌)")
    void deduplicatesCollidingFilenamesInTarget() {
        store.stage(USER, "photo.jpg", new byte[]{1});
        store.commit(USER, "coffeevera", "2026-07-11");

        // 같은 날 재기록 — 파일명이 겹쳐도 덮어쓰지 않고 새 이름을 받는다.
        store.stage(USER, "photo.jpg", new byte[]{2});
        List<String> second = store.commit(USER, "coffeevera", "2026-07-11");

        assertThat(second).containsExactly("photos/coffeevera/2026-07-11/photo-2.jpg");
    }

    @Test
    @DisplayName("T4-1: 스테이징 내 파일명 충돌도 -N으로 유일화해 유실 없이 모두 보관한다")
    void deduplicatesCollidingFilenamesInStaging() {
        store.stage(USER, "img.jpg", new byte[]{1});
        store.stage(USER, "img.jpg", new byte[]{2});

        List<String> relPaths = store.commit(USER, "note", "2026-07-11");

        // 두 장 모두 유실 없이 보관됐는지가 핵심 — 충돌 접미 파일의 정렬 순서 자체는 의미 없음.
        assertThat(relPaths).containsExactlyInAnyOrder(
                "photos/note/2026-07-11/img.jpg",
                "photos/note/2026-07-11/img-2.jpg");
    }

    @Test
    @DisplayName("T4-1: 경로 이스케이프 문자가 섞인 파일명은 안전 문자로 정규화해 대상 디렉토리를 벗어나지 않는다")
    void sanitizesUnsafeFilenames() {
        store.stage(USER, "../../evil name.jpg", new byte[]{9});

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
    @DisplayName("T4-1: discard는 스테이징만 폐기한다([취소]/TTL 정리)")
    void discardRemovesStagingOnly() {
        store.stage(USER, "x.jpg", new byte[]{1});

        store.discard(USER);

        assertThat(dataDir.resolve("photos").resolve(".staging").resolve(USER)).doesNotExist();
        // commit 대상이 없으므로 빈 목록.
        assertThat(store.commit(USER, "note", "2026-07-11")).isEmpty();
    }
}
