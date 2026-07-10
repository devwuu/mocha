package com.devwuu.mocha.repository;

import com.devwuu.mocha.domain.PendingNote;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Optional;

/**
 * {@code data/pending.json} 파일 기반 PendingStore (ref: plan.md#ADR-3).
 * <p>단일 파일(단일 사용자 NFR-6). 쓰기는 임시 파일 → move(replace) 원자적 반영으로
 * 쓰기 도중 죽어도 원본이 깨지지 않게 한다(CLAUDE.md §3). get은 TTL 초과분을 만료 처리한다(V-7).
 */
public class JsonFilePendingStore implements PendingStore {

    // 날짜/타임스탬프는 Asia/Seoul 기준 — NoteRepository와 동일(V-3).
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final Path pendingFile;
    private final ObjectMapper mapper;
    private final Duration ttl;
    private final Clock clock;

    public JsonFilePendingStore(Path dataDir, ObjectMapper mapper, Duration ttl) {
        this(dataDir, mapper, ttl, Clock.system(SEOUL));
    }

    JsonFilePendingStore(Path dataDir, ObjectMapper mapper, Duration ttl, Clock clock) {
        this.pendingFile = dataDir.resolve("pending.json");
        this.mapper = mapper;
        this.ttl = ttl;
        this.clock = clock;
    }

    @Override
    public void put(String userId, PendingNote pending) {
        try {
            Path dir = pendingFile.getParent();
            Files.createDirectories(dir);
            // 같은 디렉토리에 임시 파일로 쓴 뒤 원자적 move.
            Path tmp = Files.createTempFile(dir, "pending-", ".tmp");
            Files.write(tmp, mapper.writeValueAsBytes(pending));
            try {
                Files.move(tmp, pendingFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException notAtomic) {
                Files.move(tmp, pendingFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("pending 저장 실패: " + pendingFile, e);
        }
    }

    @Override
    public Optional<PendingNote> get(String userId) {
        if (!Files.isRegularFile(pendingFile)) {
            return Optional.empty();
        }
        PendingNote pending = read();
        // POLICY: TTL 초과 pending은 유효한 대기로 취급하지 않는다 (ref: data-model.md#V-7, plan §5).
        if (isExpired(pending)) {
            return Optional.empty();
        }
        return Optional.of(pending);
    }

    @Override
    public void clear(String userId) {
        try {
            Files.deleteIfExists(pendingFile);
        } catch (IOException e) {
            throw new UncheckedIOException("pending 폐기 실패: " + pendingFile, e);
        }
    }

    private boolean isExpired(PendingNote pending) {
        Duration age = Duration.between(pending.createdAt(), OffsetDateTime.now(clock));
        return age.compareTo(ttl) > 0;
    }

    private PendingNote read() {
        try {
            return mapper.readValue(Files.readAllBytes(pendingFile), PendingNote.class);
        } catch (IOException e) {
            throw new UncheckedIOException("pending 읽기 실패: " + pendingFile, e);
        }
    }
}
