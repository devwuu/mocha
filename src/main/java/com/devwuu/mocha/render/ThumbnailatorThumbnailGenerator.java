package com.devwuu.mocha.render;

import net.coobird.thumbnailator.Thumbnails;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/**
 * Thumbnailator 기반 {@link ThumbnailGenerator} (ref: tasks T4-3, plan.md §5).
 * <p>원본은 {@code mocha.data.dir/photos/…}, 썸네일은 {@code mocha.artifact.dir/thumbs/…}에 쓴다.
 * 폭은 {@code mocha.photo.thumb-width}로 맞추고 비율은 원본(보정 후) 유지.
 * <p>EXIF orientation 보정을 켠다 — 세로로 찍은 사진이 눕는 과거 Thumbnailator 회귀 선례(plan.md §5)를
 * 막기 위해 기본값(true)에 의존하지 않고 명시한다.
 */
@Component
public class ThumbnailatorThumbnailGenerator implements ThumbnailGenerator {

    private static final String PHOTOS_PREFIX = "photos/";
    private static final String THUMBS_PREFIX = "thumbs/";

    private final Path dataDir;
    private final Path artifactDir;
    private final int thumbWidth;

    @Autowired
    public ThumbnailatorThumbnailGenerator(
            @Value("${mocha.data.dir}") String dataDir,
            @Value("${mocha.artifact.dir}") String artifactDir,
            @Value("${mocha.photo.thumb-width}") int thumbWidth) {
        this(Path.of(dataDir), Path.of(artifactDir), thumbWidth);
    }

    // 테스트/직접 배선용 — 경로를 문자열 변환 없이 주입.
    public ThumbnailatorThumbnailGenerator(Path dataDir, Path artifactDir, int thumbWidth) {
        this.dataDir = dataDir;
        this.artifactDir = artifactDir;
        this.thumbWidth = thumbWidth;
    }

    @Override
    public String makeThumbnail(String photoRelPath) {
        if (photoRelPath == null || !photoRelPath.startsWith(PHOTOS_PREFIX)) {
            throw new IllegalArgumentException("원본 사진은 photos/ 상대 경로여야 한다: " + photoRelPath);
        }
        Path original = dataDir.resolve(photoRelPath);
        // photos/ 접두만 thumbs/로 치환 — <slug>/<date>/<name> 트리를 그대로 미러링(구분자 '/' 고정, file:// 링크용).
        String thumbRel = THUMBS_PREFIX + photoRelPath.substring(PHOTOS_PREFIX.length());
        Path target = artifactDir.resolve(thumbRel);
        try {
            Files.createDirectories(target.getParent());
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            Thumbnails.of(original.toFile())
                    .width(thumbWidth)
                    // 세로 사진이 눕지 않게 EXIF orientation을 이미지에 적용(회귀 가드).
                    .useExifOrientation(true)
                    .outputFormat(outputFormat(thumbRel))
                    .toOutputStream(buf);
            writeAtomic(target, buf.toByteArray());
        } catch (IOException e) {
            throw new UncheckedIOException("썸네일 생성 실패: " + original, e);
        }
        return thumbRel;
    }

    // 확장자 기반 출력 포맷(ImageIO writer 이름). jpeg→jpg 정규화, 그 외/미상은 jpg로 수렴.
    private static String outputFormat(String name) {
        int dot = name.lastIndexOf('.');
        String ext = dot > 0 ? name.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
        return switch (ext) {
            case "png" -> "png";
            default -> "jpg";
        };
    }

    // 파생물이지만 부분 파일이 남지 않게 임시 파일 → move(replace)로 반영(LocalPhotoStore와 동일 규율).
    private static void writeAtomic(Path target, byte[] bytes) throws IOException {
        Path tmp = Files.createTempFile(target.getParent(), "thumb-", ".tmp");
        Files.write(tmp, bytes);
        try {
            Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException notAtomic) {
            Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
