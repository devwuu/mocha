package com.devwuu.mocha.repository;

import com.devwuu.mocha.domain.PhotoSession;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Optional;

/**
 * {@code data/photo-session.json} 파일 기반 {@link PhotoSessionStore} (ref: spec FR-10, tasks T4-2).
 * <p>단일 파일(단일 사용자 NFR-6). 쓰기는 임시 파일 → move(replace) 원자적 반영으로 쓰기 도중 죽어도 원본이
 * 깨지지 않게 한다({@link JsonFilePendingStore}와 동일 정신, CLAUDE.md §3).
 */
public class JsonFilePhotoSessionStore implements PhotoSessionStore {

    private final Path sessionFile;
    private final ObjectMapper mapper;

    public JsonFilePhotoSessionStore(Path dataDir, ObjectMapper mapper) {
        this.sessionFile = dataDir.resolve("photo-session.json");
        this.mapper = mapper;
    }

    @Override
    public void put(String userId, PhotoSession session) {
        try {
            Path dir = sessionFile.getParent();
            Files.createDirectories(dir);
            Path tmp = Files.createTempFile(dir, "photo-session-", ".tmp");
            Files.write(tmp, mapper.writeValueAsBytes(session));
            try {
                Files.move(tmp, sessionFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException notAtomic) {
                Files.move(tmp, sessionFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("사진 세션 저장 실패: " + sessionFile, e);
        }
    }

    @Override
    public Optional<PhotoSession> get(String userId) {
        if (!Files.isRegularFile(sessionFile)) {
            return Optional.empty();
        }
        try {
            return Optional.of(mapper.readValue(Files.readAllBytes(sessionFile), PhotoSession.class));
        } catch (IOException e) {
            throw new UncheckedIOException("사진 세션 읽기 실패: " + sessionFile, e);
        }
    }

    @Override
    public void clear(String userId) {
        try {
            Files.deleteIfExists(sessionFile);
        } catch (IOException e) {
            throw new UncheckedIOException("사진 세션 폐기 실패: " + sessionFile, e);
        }
    }
}
