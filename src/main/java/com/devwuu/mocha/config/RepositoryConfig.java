package com.devwuu.mocha.config;

import com.devwuu.mocha.json.MochaObjectMapper;
import com.devwuu.mocha.repository.JsonFileNoteRepository;
import com.devwuu.mocha.repository.NoteRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

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
}
