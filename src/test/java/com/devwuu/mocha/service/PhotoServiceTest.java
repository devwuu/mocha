package com.devwuu.mocha.service;

import com.devwuu.mocha.image.TestImages;
import com.devwuu.mocha.repository.RecordingPhotoStore;
import com.devwuu.mocha.repository.StagedImage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * TΔ8a(changes/0029): 사진 업로드 유스케이스 — <b>포맷 게이트 → EXIF 제거 → 스테이징</b>
 * (ref: delta.md#D-7·#D-11, spec FR-10/AC-7, plan.md#ADR-29, data-model.md#V-12).
 *
 * <p>검증의 축은 <b>저장소에 무엇이 닿는가</b>다 — 게이트를 통과한 것만, EXIF가 걷힌 채로, 거부가 섞이면
 * 하나도. 저장소를 fake로 두는 이유가 그것이고({@code org.mockito} 미도입 방침 유지), 파일 I/O 자체는
 * {@code LocalPhotoStoreTest}가 실제 디렉터리로 검증한다.
 */
class PhotoServiceTest {

    private static final String USER = "local";

    private RecordingPhotoStore photoStore;
    private PhotoService photoService;

    @BeforeEach
    void setUp() {
        photoStore = new RecordingPhotoStore();
        photoService = new PhotoService(photoStore);
    }

    @Test
    @DisplayName("AC-7: 스테이징에 닿는 바이트에는 EXIF가 없다 — 걷어낸 뒤에 쓴다")
    void stagesBytesWithoutExif() {
        List<String> names = photoService.stage(USER, List.of(
                new PhotoUpload("bag.jpg", TestImages.jpegWithExif())));

        assertThat(names).containsExactly("bag.jpg");
        assertThat(photoStore.staged).hasSize(1);
        assertThat(contains(photoStore.staged.get(0).bytes(), TestImages.SECRET))
                .as("저장소에 EXIF가 실린 채로 닿으면 제거 지점이 잘못 놓인 것이다")
                .isFalse();
        // 그림은 그대로여야 한다 — 지우는 데 성공하고 사진을 잃으면 실패다.
        assertThat(TestImages.pixels(photoStore.staged.get(0).bytes())).isNotNull();
    }

    @Test
    @DisplayName("V-12/ADR-29: 수용하지 않는 포맷은 거부한다 — 판별은 매직바이트이고 확장자를 믿지 않는다")
    void rejectsUnacceptedFormatByMagicBytes() {
        // 확장자는 .jpg인데 내용은 GIF다 — 구 실사용에서 poison이 스테이징을 통과하던 형태다(changes/0013 #2).
        byte[] gif = "GIF89a....".getBytes(StandardCharsets.US_ASCII);

        assertThatThrownBy(() -> photoService.stage(USER, List.of(new PhotoUpload("bag.jpg", gif))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("GIF");

        assertThat(photoStore.staged).isEmpty();
    }

    @Test
    @DisplayName("TΔ8a POLICY: 한 장이라도 거부되면 아무것도 스테이징되지 않는다 — 계약에 부분 성공이 없다")
    void rejectingOnePhotoStagesNothing() {
        List<PhotoUpload> batch = List.of(
                new PhotoUpload("good.jpg", TestImages.jpeg()),
                new PhotoUpload("bad.heic", "GIF89a....".getBytes(StandardCharsets.US_ASCII)),
                new PhotoUpload("also-good.png", TestImages.png()));

        assertThatThrownBy(() -> photoService.stage(USER, batch))
                .isInstanceOf(IllegalArgumentException.class);

        // 앞 장이 이미 스테이징된 뒤에 거부가 나면 "몇 장은 올라간" 상태가 남는다 — 그 자리를 막는 단언이다.
        assertThat(photoStore.staged).isEmpty();
    }

    @Test
    @DisplayName("TΔ8a: 여러 장은 보낸 순서대로 스테이징된다 — 그 순서가 아카이브 순서가 된다")
    void stagesMultiplePhotosInOrder() {
        List<String> names = photoService.stage(USER, List.of(
                new PhotoUpload("front.jpg", TestImages.jpeg()),
                new PhotoUpload("back.png", TestImages.png())));

        assertThat(names).containsExactly("front.jpg", "back.png");
        assertThat(photoStore.staged).extracting(StagedImage::name).containsExactly("front.jpg", "back.png");
    }

    @Test
    @DisplayName("TΔ8a: 빈 업로드는 저장소를 건드리지 않는다")
    void emptyUploadTouchesNothing() {
        assertThat(photoService.stage(USER, List.of())).isEmpty();
        assertThat(photoService.stage(USER, null)).isEmpty();
        assertThat(photoStore.staged).isEmpty();
    }

    @Test
    @DisplayName("FR-10: 폐기는 그 사용자의 스테이징을 비운다 — [취소]가 부르는 자리다")
    void discardClearsStaging() {
        photoService.discard(USER);

        assertThat(photoStore.discarded).containsExactly(USER);
    }

    private static boolean contains(byte[] haystack, String needle) {
        return new String(haystack, StandardCharsets.ISO_8859_1).contains(needle);
    }
}
