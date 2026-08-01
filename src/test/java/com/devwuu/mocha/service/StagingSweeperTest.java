package com.devwuu.mocha.service;

import com.devwuu.mocha.repository.LocalPhotoStore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * StagingSweeper — 시작 시 스테이징 고아 청소 검증 (ref: plan.md#ADR-29·§7, data-model.md#V-12, AC-Δ3).
 * <p>실제 파일 I/O(@TempDir + LocalPhotoStore)로 단언한다 — fake store로 바꾸면 검증 대상(경로 규칙·
 * 실제 삭제)이 통째로 빠진다(모듈 CLAUDE.md §5.2).
 * <p><b>판정이 0029에서 두 번 줄어 단항이 됐다</b>: TΔ4에서 pending 항이(서버가 작성 중 상태를 갖지
 * 않는다), TΔ16에서 buffer 항이(사진과 발화를 시간 윈도우로 묶던 Slack 배관이 소멸했다) 빠졌다. 그래서
 * 구 <i>"고아만 골라 지운다"</i> 케이스가 <b>"시작 시 남은 스테이징은 전부 고아다"</b>로 바뀌었다 —
 * 대조할 참조가 없어진 것이지 규칙이 느슨해진 것이 아니다(StagingSweeper javadoc).
 */
class StagingSweeperTest {

    private static Path stagingOf(Path dataDir, String userId) {
        return dataDir.resolve("photos").resolve(".staging").resolve(userId);
    }

    @Test
    @DisplayName("AC-Δ3/TΔ16: 시작 시 남아 있는 스테이징은 전부 고아 — 예외 없이 청소한다")
    void sweepsAllLeftoverStaging(@TempDir Path dataDir) {
        LocalPhotoStore photoStore = new LocalPhotoStore(dataDir);
        photoStore.stage("Uorphan", "poison.heic", new byte[]{1, 2, 3});
        photoStore.stage("Uother", "b.jpg", new byte[]{2});

        new StagingSweeper(photoStore).run(null);

        assertThat(stagingOf(dataDir, "Uorphan")).doesNotExist();
        assertThat(stagingOf(dataDir, "Uother")).doesNotExist();
        assertThat(photoStore.stagedUserIds()).isEmpty();
    }

    @Test
    @DisplayName("스테이징이 비어 있으면 아무 것도 하지 않는다(회귀 가드 — 정상 시작)")
    void noStagingIsNoop(@TempDir Path dataDir) {
        LocalPhotoStore photoStore = new LocalPhotoStore(dataDir);

        // 예외 없이 통과해야 한다.
        new StagingSweeper(photoStore).run(null);

        assertThat(photoStore.stagedUserIds()).isEmpty();
    }

    @Test
    @DisplayName("TΔ16: 저장 확정으로 노트 폴더에 옮겨간 사진은 청소 대상이 아니다 — 스테이징만 본다")
    void committedPhotosSurviveSweep(@TempDir Path dataDir) {
        LocalPhotoStore photoStore = new LocalPhotoStore(dataDir);
        photoStore.stage("Ucommitted", "a.jpg", new byte[]{7});
        // 판정이 단항이 된 뒤 "너무 많이 지우지 않는가"를 답하는 것은 이 케이스다 — 청소기가 보는 범위가
        // 스테이징 하위로 한정되는지가 유일한 방어선이다.
        photoStore.commit("Ucommitted", "1-커피베라-예가체프", "2026-07-16");

        new StagingSweeper(photoStore).run(null);

        assertThat(dataDir.resolve("photos").resolve("1-커피베라-예가체프").resolve("2026-07-16")).exists();
        assertThat(photoStore.stagedUserIds()).isEmpty();
    }
}
