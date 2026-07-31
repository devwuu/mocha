package com.devwuu.mocha.slack;

import com.devwuu.mocha.domain.PhotoBuffer;
import com.devwuu.mocha.repository.LocalPhotoStore;
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
 * <p>실제 파일 I/O(@TempDir + LocalPhotoStore)로 "고아만 제거"를 단언한다: buffer가 참조하지 않는
 * 스테이징만 지우고, 살아있는 버퍼의 스테이징은 남긴다.
 * <p>0029 TΔ4에서 판정 조건의 pending 항이 사라졌다 — 서버가 작성 중 상태를 갖지 않으므로 "살아있는
 * 대기"의 근거가 버퍼 하나만 남았다(delta 0029 D-2, StagingSweeper javadoc).
 */
class StagingSweeperTest {

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
    @DisplayName("AC-Δ3: buffer가 참조하지 않는 스테이징(고아)만 청소하고 살아있는 버퍼의 스테이징은 남긴다")
    void sweepsOnlyOrphans(@TempDir Path dataDir) {
        LocalPhotoStore photoStore = new LocalPhotoStore(dataDir);
        FakePhotoBufferStore bufferStore = new FakePhotoBufferStore();

        // 두 사용자의 스테이징을 실제로 만든다.
        photoStore.stage("Uorphan", "poison.heic", new byte[]{1, 2, 3});
        photoStore.stage("Ubuffer", "b.jpg", new byte[]{2});
        // 하나는 살아있는 버퍼가 참조한다.
        bufferStore.live.add("Ubuffer");

        new StagingSweeper(photoStore, bufferStore).run(null);

        // 고아만 사라진다.
        assertThat(stagingOf(dataDir, "Uorphan")).doesNotExist();
        assertThat(stagingOf(dataDir, "Ubuffer")).exists();
        assertThat(photoStore.stagedUserIds()).containsExactly("Ubuffer");
    }

    @Test
    @DisplayName("스테이징이 비어 있으면 아무 것도 하지 않는다(회귀 가드 — 정상 시작)")
    void noStagingIsNoop(@TempDir Path dataDir) {
        LocalPhotoStore photoStore = new LocalPhotoStore(dataDir);

        // 예외 없이 통과해야 한다.
        new StagingSweeper(photoStore, new FakePhotoBufferStore()).run(null);

        assertThat(photoStore.stagedUserIds()).isEmpty();
    }
}
