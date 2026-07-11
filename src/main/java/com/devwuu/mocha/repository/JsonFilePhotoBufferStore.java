package com.devwuu.mocha.repository;

import com.devwuu.mocha.domain.PhotoBuffer;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * {@code data/photo-buffer.json} 파일 기반 {@link PhotoBufferStore} (ref: spec FR-10, tasks T4-2).
 * <p>단일 파일(단일 사용자 NFR-6). 쓰기는 임시 파일 → move(replace) 원자적 반영으로 쓰기 도중 죽어도 원본이
 * 깨지지 않게 한다({@link JsonFilePendingStore}와 동일 정신, CLAUDE.md §3).
 */
public class JsonFilePhotoBufferStore implements PhotoBufferStore {

    private final Path bufferFile;
    private final ObjectMapper mapper;

    public JsonFilePhotoBufferStore(Path dataDir, ObjectMapper mapper) {
        this.bufferFile = dataDir.resolve("photo-buffer.json");
        this.mapper = mapper;
    }

    @Override
    public void put(String userId, PhotoBuffer buffer) {
        try {
            Path dir = bufferFile.getParent();
            Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, "photo-buffer-", ".tmp");
            Files.write(tmp, mapper.writeValueAsBytes(buffer));
            try {
                Files.move(tmp, bufferFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException notAtomic) {
                Files.move(tmp, bufferFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("사진 버퍼 저장 실패: " + bufferFile, e);
        }
    }

    @Override
    public Optional<PhotoBuffer> get(String userId) {
        if (!Files.isRegularFile(bufferFile)) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(Files.readAllBytes(bufferFile), PhotoBuffer.class));
        } catch (IOException e) {
            throw new UncheckedIOException("사진 버퍼 읽기 실패: " + bufferFile, e);
        }
    }

    @Override
    public void clear(String userId) {
        try {
            Files.deleteIfExists(bufferFile);
        } catch (IOException e) {
            throw new UncheckedIOException("사진 버퍼 폐기 실패: " + bufferFile, e);
        }
    }
}
