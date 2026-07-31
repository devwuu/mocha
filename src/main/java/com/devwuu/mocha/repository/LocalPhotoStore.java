package com.devwuu.mocha.repository;

import com.devwuu.mocha.image.ImageFormat;

import java.io.IOException;
import java.io.InputStream;
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
 *   data/photos/.staging/&lt;userId&gt;/*        확인 대기 중 원본 (노트 폴더 미확정)
 *   data/photos/&lt;noteFolder&gt;/&lt;date&gt;/*  저장 확정된 원본
 * </pre>
 * 스테이징 디렉토리는 {@code .staging} — 노트 폴더 접미는 항상 {@code <id>-}로 시작하므로(숫자 선두)
 * 실제 노트 디렉토리와 절대 충돌하지 않는다. commit은 상대 경로만 반환하고(V-4), 절대/URL 경로는 애초에
 * 만들지 않는다.
 */
public class LocalPhotoStore implements PhotoStore {

    // 노트 폴더와 절대 겹치지 않는 예약 디렉토리.
    // POLICY: 근거는 "접미가 항상 <id>-로 시작한다"이다 — slug 시절의 근거였던 "[a-z0-9-]+라 '.' 불가"는
    //         승계되지 않는다. NoteFolderName의 금지문자 목록에 '.'이 없어 커피명의 점은 접미에 남는다
    //         (ref: changes/0028-rdb-storage/inventory.md §4 E-4, delta.md §파일 경로 규약).
    private static final String STAGING = ".staging";
    // 매직바이트 판정에 필요한 선두 길이 — WEBP/HEIC의 브랜드가 8~11바이트라 12면 충분(여유 16).
    private static final int MAGIC_PREFIX = 16;
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
            for (Path src : listStagedPhotos(staging)) {
                images.add(new StagedImage(src.getFileName().toString(), Files.readAllBytes(src)));
            }
        } catch (IOException e) {
            throw new UncheckedIOException("사진 스테이징 읽기 실패: " + staging, e);
        }
        return images;
    }

    @Override
    public List<String> commit(String userId, String noteFolder, String date) {
        Path staging = stagingDir(userId);
        if (!Files.isDirectory(staging)) {
            return List.of();
        }
        Path target = photosDir.resolve(noteFolder).resolve(date);
        List<String> relPaths = new ArrayList<>();
        try {
            Files.createDirectories(target);
            List<Path> staged = listStagedPhotos(staging);
            for (Path src : staged) {
                String name = uniqueName(target, src.getFileName().toString());
                move(src, target.resolve(name));
                // V-4: JSON에는 photos/ 로 시작하는 상대 경로만. 구분자는 '/'로 고정(플랫폼 무관·file:// 링크용).
                relPaths.add("photos/" + noteFolder + "/" + date + "/" + name);
            }
            // 걸러진 비사진 잔재(.DS_Store 등)까지 지우고 폴더를 접는다 — 스테이징은 커밋 후 소멸이 불변식.
            deleteStaging(staging);
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
        try {
            deleteStaging(staging);
        } catch (IOException e) {
            throw new UncheckedIOException("사진 스테이징 폐기 실패: " + staging, e);
        }
    }

    @Override
    public void moveEntryPhotos(String noteFolder, String fromDate, String toDate) {
        Path source = photosDir.resolve(noteFolder).resolve(fromDate);
        // 원본 폴더 부재 = 옮길 사진 없음 → no-op(사진 없이 날짜만 이동한 엔트리도 정상 경로).
        if (!Files.isDirectory(source)) {
            return;
        }
        Path target = photosDir.resolve(noteFolder).resolve(toDate);
        try {
            Files.createDirectories(target);
            for (Path src : listSorted(source)) {
                // 충돌 시 -N 유일화 병합 — commit과 동일 규칙(재기록·이동 겹침 모두 유실 없이 보관).
                String name = uniqueName(target, src.getFileName().toString());
                move(src, target.resolve(name));
            }
            Files.deleteIfExists(source); // 빈 옛 폴더 제거(폴더=진실 불변식).
        } catch (IOException e) {
            throw new UncheckedIOException("사진 폴더 이동 실패: " + source + " → " + target, e);
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

    /**
     * 스테이징 열람은 사진만 본다 — 판정은 매직바이트(입구 게이트 ADR-29와 같은 어휘), 확장자·메타는 신뢰하지 않는다.
     * <p>{@code readStaged}·{@code commit}이 공유하는 유일한 열람 지점이라, 여기서 거른 파일은 OCR 배치에도
     * 아카이브에도 들어가지 않는다. 스테이징엔 vision 지원 포맷과 HEIC 대체 썸네일만 정상 유입되므로
     * {@code UNKNOWN} 제외로 충분하다. 아카이브 열람({@code moveEntryPhotos})은 대상이 아니다 — 이미 저장된
     * 파일을 걸러내면 날짜 이동에서 잔재가 남아 폴더가 접히지 않는다.
     */
    // POLICY: 저장소 읽기 경계가 유일한 무결성 관문 — 소비처는 로드된 객체의 구조 무결성을 재검증하지 않는다 (ref: specs/coffee-note-agent/plan.md#ADR-66)
    private List<Path> listStagedPhotos(Path staging) throws IOException {
        List<Path> photos = new ArrayList<>();
        for (Path file : listSorted(staging)) {
            if (isPhoto(file)) {
                photos.add(file);
            }
        }
        return photos;
    }

    private static boolean isPhoto(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return ImageFormat.detect(in.readNBytes(MAGIC_PREFIX)) != ImageFormat.UNKNOWN;
        }
    }

    // 스테이징 소멸: 남은 항목(걸러진 비사진 포함)을 지운 뒤 폴더까지 제거 — 커밋·폐기 공용.
    private static void deleteStaging(Path staging) throws IOException {
        try (Stream<Path> entries = Files.list(staging)) {
            for (Path p : entries.toList()) {
                Files.deleteIfExists(p);
            }
        }
        Files.deleteIfExists(staging);
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
