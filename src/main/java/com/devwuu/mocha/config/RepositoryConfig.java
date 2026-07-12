package com.devwuu.mocha.config;

import com.devwuu.mocha.json.MochaObjectMapper;
import com.devwuu.mocha.repository.InMemorySearchSessionStore;
import com.devwuu.mocha.repository.InMemoryTransitionSlot;
import com.devwuu.mocha.repository.JsonFileNoteRepository;
import com.devwuu.mocha.repository.JsonFilePendingStore;
import com.devwuu.mocha.repository.JsonFilePhotoBufferStore;
import com.devwuu.mocha.repository.LocalPhotoStore;
import com.devwuu.mocha.repository.NoteRepository;
import com.devwuu.mocha.repository.PendingStore;
import com.devwuu.mocha.repository.PhotoBufferStore;
import com.devwuu.mocha.repository.PhotoStore;
import com.devwuu.mocha.repository.SearchSessionStore;
import com.devwuu.mocha.repository.TransitionSlot;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;
import java.time.Duration;

/**
 * 저장소 빈 배선. 파일 경로는 {@code mocha.data.dir}에서만 온다(plan.md §5, CLAUDE.md §3).
 * <p>도메인 JSON 규칙은 {@link MochaObjectMapper}로 통일 — Spring 기본 ObjectMapper와 분리해
 * snake_case·오프셋 보존 규칙이 다른 곳에 새지 않게 한다.
 */
@Configuration
public class RepositoryConfig {

    @Bean
    public NoteRepository noteRepository(@Value("${mocha.data.dir}") String dataDir) {
        return new JsonFileNoteRepository(Path.of(dataDir), MochaObjectMapper.create());
    }

    @Bean
    public PendingStore pendingStore(
            @Value("${mocha.data.dir}") String dataDir,
            @Value("${mocha.pending.ttl}") Duration ttl) {
        return new JsonFilePendingStore(Path.of(dataDir), MochaObjectMapper.create(), ttl);
    }

    @Bean
    public PhotoStore photoStore(@Value("${mocha.data.dir}") String dataDir) {
        return new LocalPhotoStore(Path.of(dataDir));
    }

    @Bean
    public PhotoBufferStore photoBufferStore(@Value("${mocha.data.dir}") String dataDir) {
        return new JsonFilePhotoBufferStore(Path.of(dataDir), MochaObjectMapper.create());
    }

    @Bean
    public SearchSessionStore searchSessionStore(
            @Value("${mocha.search-session.ttl:1h}") Duration ttl) {
        // 메모리 전용(NFR-2 예외, ADR-25) — 파일 경로가 필요 없다(data/ 미생성은 생성자 시그니처가 보장).
        return new InMemorySearchSessionStore(ttl);
    }

    @Bean
    public TransitionSlot transitionSlot() {
        // 메모리 전용 단일 슬롯(ADR-26) — TTL 10분은 코드 상수, 설정 키 없음(data-model §2.5).
        return new InMemoryTransitionSlot();
    }
}
