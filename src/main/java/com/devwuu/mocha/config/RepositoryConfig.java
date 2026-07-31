package com.devwuu.mocha.config;

import com.devwuu.mocha.repository.JpaNoteRepository;
import com.devwuu.mocha.repository.JsonFilePhotoBufferStore;
import com.devwuu.mocha.repository.LocalPhotoStore;
import com.devwuu.mocha.repository.NoteRepository;
import com.devwuu.mocha.repository.PhotoBufferStore;
import com.devwuu.mocha.repository.PhotoStore;
import com.devwuu.mocha.repository.jpa.NoteEntityRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Path;

/**
 * 저장소 빈 배선. 노트는 DB에서, 사진·photo buffer는 아직 {@code mocha.data.dir} 아래 파일에서 온다
 * (plan.md §5, CLAUDE.md §3, changes/0028 ADR-73·74).
 * <p>도메인 JSON 규칙은 공통 {@code ObjectMapper} 빈({@link CommonConfig} — {@code MochaObjectMapper}
 * 규칙, ADR-63)을 주입받아 통일한다.
 */
@Configuration
public class RepositoryConfig {

    // POLICY: mocha.* 키는 코드 default를 갖는다 — 설정 부재로 기동이 막히지 않는다
    // (ref: specs/coffee-note-agent/plan.md#ADR-50 POLICY). 값은 plan §5 문서값(./data)과 일치시킨다.
    // changes/0025 CR25-8: RB-B6이 mocha.artifact.dir만 고쳐 같은 부류가 이 파일에 남아 있었다.
    private static final String DEFAULT_DATA_DIR = "${mocha.data.dir:./data}";

    // 노트가 DB로 옮겨졌다 — photo buffer는 아직 파일이고(D-3) 사진 원본은 계속 파일이다.
    // 그래서 mocha.data.dir는 소멸하지 않고 아래 둘의 뿌리로 잔존한다(TΔ8).
    // 0029 TΔ4: pending 저장소 빈이 사라졌다 — 작성 중 데이터는 클라이언트 폼이 소유하고 서버는 기억하지
    // 않는다(delta 0029 D-2). TTL 설정 키(mocha.pending.ttl)도 함께 소멸했다.
    @Bean
    public NoteRepository noteRepository(NoteEntityRepository notes) {
        return new JpaNoteRepository(notes);
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
