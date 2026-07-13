package com.devwuu.mocha.repository;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * 로컬 파일시스템 기반 {@link PhotoStore} (ref: data-model.md#2.4, tasks T4-1).
 * <p>레이아웃:
 * <pre>
 *   data/photos/.staging/&lt;userId&gt;/*   확인 대기 중 원본 (slug 미확정)
 *   data/photos/&lt;slug&gt;/&lt;date&gt;/*     저장 확정된 원본
 * </pre>
 * 스테이징 디렉토리는 {@code .staging} — slug은 {@code [a-z0-9-]+}(점 없음)이라 실제 노트 디렉토리와
 * 절대 충돌하지 않는다. commit은 상대 경로만 반환하고(V-4), 절대/URL 경로는 애초에 만들지 않는다.
 */
public class LocalPhotoStore implements PhotoStore {

    // slug 세그먼트와 절대 겹치지 않는 예약 디렉토리(slug 문법상 '.' 불가).
    private static final String STAGING = ".staging";
    // 파일명 안전 문자만 허용 — 그 외는 '_'로 치환(경로 이스케이프·구분자 차단).
    private static final Pattern UNSAFE = Pattern.compile("[^a-zA-Z0-9._-]");

    private final Path photosDir;

    public LocalPhotoStore(Path dataDir) {
        this.photosDir = dataDir.resolve("photos");
    }

    @Override
    public String stage(String userId, String filename, byte[] bytes) {
        Path dir = stagingDir(userId);
        try {
            Files.createDirectories(dir);
            String name = uniqueName(dir, safeName(filename));
            writeAtomic(dir.resolve(name), bytes);
            return name;
        } catch (IOException e) {
            throw new UncheckedIOException("사진 스테이징 실패: " + dir, e);
        }
    }

    @Override
    public List<StagedImage> readStaged(String userId) {
        Path staging = stagingDir(userId);
        if (!Files.isDirectory(staging)) {
            return List.of();
        }
        List<StagedImage> images = new ArrayList<>();
        try {
            for (Path src : listSorted(staging)) {
                images.add(new StagedImage(src.getFileName().toString(), Files.readAllBytes(src)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("사진 스테이징 읽기 실패: " + staging, e);
        }
        return images;
    }

    @Override
    public List<String> commit(String userId, String slug, String date) {
        Path staging = stagingDir(userId);
        if (!Files.isDirectory(staging)) {
            return List.of();
        }
        Path target = photosDir.resolve(slug).resolve(date);
        List<String> relPaths = new ArrayList<>();
        try {
            Files.createDirectories(target);
            List<Path> staged = listSorted(staging);
            for (Path src : staged) {
                String name = uniqueName(target, src.getFileName().toString());
                move(src, target.resolve(name));
                // V-4: JSON에는 photos/ 로 시작하는 상대 경로만. 구분자는 '/'로 고정(플랫폼 무관·file:// 링크용).
                relPaths.add("photos/" + slug + "/" + date + "/" + name);
            }
            Files.deleteIfExists(staging);
        } catch (IOException e) {
            throw new UncheckedIOException("사진 커밋 실패: " + target, e);
        }
        return relPaths;
    }

    @Override
    public void discard(String userId) {
        Path staging = stagingDir(userId);
        if (!Files.isDirectory(staging)) {
            return;
        }
        try (Stream<Path> entries = Files.list(staging)) {
            for (Path p : entries.toList()) {
                Files.deleteIfExists(p);
            }
            Files.deleteIfExists(staging);
        } catch (IOException e) {
            throw new UncheckedIOException("사진 스테이징 폐기 실패: " + staging, e);
        }
    }

    @Override
    public List<String> stagedUserIds() {
        Path root = photosDir.resolve(STAGING);
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        try (Stream<Path> dirs = Files.list(root)) {
            // 사용자별 스테이징은 디렉토리다 — .staging 바로 아래의 파일(.DS_Store 등)은 사용자 키가 아니다.
            return dirs
                    .filter(Files::isDirectory)
                    .map(p -> p.getFileName().toString())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("스테이징 사용자 목록 읽기 실패: " + root, e);
        }
    }

    private Path stagingDir(String userId) {
        return photosDir.resolve(STAGING).resolve(safeName(userId));
    }

    private List<Path> listSorted(Path dir) throws IOException {
        try (Stream<Path> files = Files.list(dir)) {
            return files
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .toList();
        }
    }

    // 대상 디렉토리에 같은 이름이 있으면 base-2.ext, base-3.ext … 로 유일화(스테이징 중복·재기록 충돌 모두 대응).
    private String uniqueName(Path dir, String name) {
        if (!Files.exists(dir.resolve(name))) {
            return name;
        }
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        for (int n = 2; ; n++) {
            String candidate = base + "-" + n + ext;
            if (!Files.exists(dir.resolve(candidate))) {
                return candidate;
            }
        }
    }

    private static String safeName(String raw) {
        String cleaned = raw == null || raw.isBlank() ? "photo" : UNSAFE.matcher(raw).replaceAll("_");
        // 선행 점('.')은 숨김/예약(.staging)과 헷갈리므로 제거.
        return cleaned.startsWith(".") ? "_" + cleaned.substring(1) : cleaned;
    }

    private static void writeAtomic(Path target, byte[] bytes) throws IOException {
        Path tmp = Files.createTempFile(target.getParent(), "photo-", ".tmp");
        Files.write(tmp, bytes);
        move(tmp, target);
    }

    private static void move(Path src, Path target) throws IOException {
        try {
            Files.move(src, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (java.nio.file.AtomicMoveNotSupportedException notAtomic) {
            Files.move(src, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
