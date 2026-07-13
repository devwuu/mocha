package com.devwuu.mocha.slack;

import com.devwuu.mocha.domain.PendingNote;
import com.devwuu.mocha.domain.PhotoBuffer;
import com.devwuu.mocha.repository.LocalPhotoStore;
import com.devwuu.mocha.repository.PendingStore;
import com.devwuu.mocha.repository.PhotoBufferStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * StagingSweeper — 시작 시 스테이징 고아 청소 검증 (ref: plan.md#ADR-29·§7, data-model.md#V-12, AC-Δ3).
 * <p>실제 파일 I/O(@TempDir + LocalPhotoStore)로 "고아만 제거"를 단언한다: pending·buffer 어느 쪽도
 * 참조하지 않는 스테이징만 지우고, 살아있는 대기의 스테이징은 남긴다.
 */
class StagingSweeperTest {

    /** live 집합에 든 userId만 present로 돌려주는 최소 fake — sweeper는 isPresent만 본다. */
    private static final class FakePendingStore implements PendingStore {
        final Set<String> live = new HashSet<>();

        @Override
        public void put(String userId, PendingNote pending) {
        }

        @Override
        public Optional<PendingNote> get(String userId) {
            return live.contains(userId) ? Optional.of(new PendingNote(null, null, null, null)) : Optional.empty();
        }

        @Override
        public void clear(String userId) {
        }
    }

    private static final class FakePhotoBufferStore implements PhotoBufferStore {
        final Set<String> live = new HashSet<>();

        @Override
        public void put(String userId, PhotoBuffer buffer) {
        }

        @Override
        public Optional<PhotoBuffer> get(String userId) {
            return live.contains(userId)
                    ? Optional.of(new PhotoBuffer(OffsetDateTime.now(), List.of()))
                    : Optional.empty();
        }

        @Override
        public void clear(String userId) {
        }
    }

    private static Path stagingOf(Path dataDir, String userId) {
        return dataDir.resolve("photos").resolve(".staging").resolve(userId);
    }

    @Test
    @DisplayName("AC-Δ3: pending·buffer 어디에도 없는 스테이징(고아)만 청소하고 살아있는 대기의 스테이징은 남긴다")
    void sweepsOnlyOrphans(@TempDir Path dataDir) {
        LocalPhotoStore photoStore = new LocalPhotoStore(dataDir);
        FakePendingStore pendingStore = new FakePendingStore();
        FakePhotoBufferStore bufferStore = new FakePhotoBufferStore();

        // 세 사용자의 스테이징을 실제로 만든다.
        photoStore.stage("Uorphan", "poison.heic", new byte[]{1, 2, 3});
        photoStore.stage("Upending", "a.jpg", new byte[]{1});
        photoStore.stage("Ubuffer", "b.jpg", new byte[]{2});
        // 둘은 살아있는 대기가 참조한다.
        pendingStore.live.add("Upending");
        bufferStore.live.add("Ubuffer");

        new StagingSweeper(photoStore, pendingStore, bufferStore).run(null);

        // 고아만 사라진다.
        assertThat(stagingOf(dataDir, "Uorphan")).doesNotExist();
        assertThat(stagingOf(dataDir, "Upending")).exists();
        assertThat(stagingOf(dataDir, "Ubuffer")).exists();
        assertThat(photoStore.stagedUserIds()).containsExactly("Ubuffer", "Upending");
    }

    @Test
    @DisplayName("스테이징이 비어 있으면 아무 것도 하지 않는다(회귀 가드 — 정상 시작)")
    void noStagingIsNoop(@TempDir Path dataDir) {
        LocalPhotoStore photoStore = new LocalPhotoStore(dataDir);

        // 예외 없이 통과해야 한다.
        new StagingSweeper(photoStore, new FakePendingStore(), new FakePhotoBufferStore()).run(null);

        assertThat(photoStore.stagedUserIds()).isEmpty();
    }
}
