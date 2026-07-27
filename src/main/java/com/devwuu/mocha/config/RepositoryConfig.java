package com.devwuu.mocha.config;

import com.devwuu.mocha.repository.JsonFileNoteRepository;
import com.devwuu.mocha.repository.JsonFilePendingStore;
import com.devwuu.mocha.repository.JsonFilePhotoBufferStore;
import com.devwuu.mocha.repository.LocalPhotoStore;
import com.devwuu.mocha.repository.NoteRepository;
import com.devwuu.mocha.repository.PendingStore;
import com.devwuu.mocha.repository.PhotoBufferStore;
import com.devwuu.mocha.repository.PhotoStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;

/**
 * 저장소 빈 배선. 파일 경로는 {@code mocha.data.dir}에서만 온다(plan.md §5, CLAUDE.md §3).
 * <p>도메인 JSON 규칙은 공통 {@code ObjectMapper} 빈({@link CommonConfig} — {@code MochaObjectMapper}
 * 규칙, ADR-63)을 주입받아 통일한다.
 */
@Configuration
public class RepositoryConfig {

    // POLICY: mocha.* 키는 코드 default를 갖는다 — 설정 부재로 기동이 막히지 않는다
    // (ref: specs/coffee-note-agent/plan.md#ADR-50 POLICY). 값은 plan §5 문서값(./data · 24시간)과 일치시킨다.
    // changes/0025 CR25-8: RB-B6이 mocha.artifact.dir만 고쳐 같은 부류가 이 파일에 남아 있었다.
    private static final String DEFAULT_DATA_DIR = "${mocha.data.dir:./data}";

    @Bean
    public NoteRepository noteRepository(
            @Value(DEFAULT_DATA_DIR) String dataDir, ObjectMapper mapper, Clock clock) {
        return new JsonFileNoteRepository(Path.of(dataDir), mapper, clock);
    }

    @Bean
    public PendingStore pendingStore(
            @Value(DEFAULT_DATA_DIR) String dataDir,
            @Value("${mocha.pending.ttl:24h}") Duration ttl,
            ObjectMapper mapper,
            Clock clock) {
        return new JsonFilePendingStore(Path.of(dataDir), mapper, ttl, clock);
    }

    @Bean
    public PhotoStore photoStore(@Value(DEFAULT_DATA_DIR) String dataDir) {
        return new LocalPhotoStore(Path.of(dataDir));
    }

    @Bean
    public PhotoBufferStore photoBufferStore(
            @Value(DEFAULT_DATA_DIR) String dataDir, ObjectMapper mapper) {
        return new JsonFilePhotoBufferStore(Path.of(dataDir), mapper);
    }
}
