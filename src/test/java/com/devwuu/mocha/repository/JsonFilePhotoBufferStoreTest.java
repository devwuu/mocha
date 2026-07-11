package com.devwuu.mocha.repository;

import com.devwuu.mocha.domain.PhotoBuffer;
import com.devwuu.mocha.json.MochaObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T4-2: {@link JsonFilePhotoBufferStore} — 사진 버퍼의 파일 영속화를 실제 I/O(@TempDir, CLAUDE.md §5.2)로 검증한다.
 * 사진이 텍스트보다 먼저 도착한 상태가 재시작 후에도 복원돼야 그룹핑(FR-10)이 이어진다.
 */
class JsonFilePhotoBufferStoreTest {

    @TempDir
    Path dataDir;

    private JsonFilePhotoBufferStore store() {
        return new JsonFilePhotoBufferStore(dataDir, MochaObjectMapper.create());
    }

    @Test
    @DisplayName("저장 후 새 인스턴스로 로드해 동일 버퍼가 복원된다(재시작 생존)")
    void roundTripsBuffer() {
        OffsetDateTime at = OffsetDateTime.of(2026, 7, 11, 11, 0, 0, 0, ZoneOffset.ofHours(9));
        store().put("U1", new PhotoBuffer(at, List.of("a.jpg", "b.jpg")));

        Optional<PhotoBuffer> loaded = store().get("U1");

        assertTrue(loaded.isPresent());
        assertEquals(at, loaded.get().lastMediaAt(), "수신 시각(오프셋 포함)이 보존된다");
        assertEquals(List.of("a.jpg", "b.jpg"), loaded.get().stagedNames());
    }

    @Test
    @DisplayName("버퍼 부재 시 빈 Optional, clear 후에도 빈 Optional")
    void absentAndCleared() {
        assertTrue(store().get("U1").isEmpty(), "파일 없으면 빈 Optional");

        store().put("U1", new PhotoBuffer(OffsetDateTime.now(), List.of("a.jpg")));
        store().clear("U1");

        assertTrue(store().get("U1").isEmpty(), "clear 후 버퍼가 사라진다");
    }
}
