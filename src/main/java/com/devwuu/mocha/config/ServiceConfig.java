package com.devwuu.mocha.config;

import com.devwuu.mocha.llm.AliasGenerator;
import com.devwuu.mocha.repository.PhotoStore;
import com.devwuu.mocha.repository.jpa.NoteEntityRepository;
import com.devwuu.mocha.service.NoteService;
import com.devwuu.mocha.service.NoteTxService;
import com.devwuu.mocha.service.PhotoService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * 유스케이스 계층 빈 배선 (ref: 백엔드 CLAUDE.md §2, changes/0029 delta#D-8·D-9, tasks TΔ4a·TΔ4c).
 *
 * <p>두 빈의 관계가 곧 계층 축이다: {@link NoteTxService}는 <b>한 트랜잭션 단위</b>이고
 * {@link NoteService}는 <b>외부 IO 오케스트레이션</b>이다. 순서(외부 콜 → 쓰기)가 계층으로 고정되므로
 * 백엔드 CLAUDE.md §3(<i>"트랜잭션 안에서 외부 호출 금지"</i>)이 규칙이 아니라 구조로 지켜진다.
 *
 * <p>둘 다 프레임워크 의존이 없는 순수 협력자라 {@code @Component} 스캔 대신 여기서 명시적으로
 * 조립한다({@link RepositoryConfig}·{@link RenderConfig}와 동일 방침, ADR-63).
 *
 * <p>{@link AliasGenerator}는 {@link NoteService}에만 주입된다 — 유일한 외부 콜이고, 그것이 트랜잭션
 * 계층에 닿지 않는다는 사실이 <b>생성자 시그니처로</b> 드러난다.
 */
@Configuration
public class ServiceConfig {

    // @Transactional 프록시가 서는 지점이다. 인터페이스가 없어 CGLIB 서브클래싱이며(0029 ADR-76),
    // 그래서 NoteTxService와 공개 메서드는 final이면 안 된다.
    @Bean
    public NoteTxService noteTxService(NoteEntityRepository notes) {
        return new NoteTxService(notes);
    }

    // 0029 TΔ8b: 파일 협력자 둘이 늘었다 — 커밋의 사진 확정(PhotoStore)과 삭제의 카드 정리(artifactDir).
    // 둘 다 외부 IO라 이 층이고, 그래서 NoteTxService의 생성자에는 여전히 나타나지 않는다.
    @Bean
    public NoteService noteService(NoteTxService noteTxService, AliasGenerator aliasGenerator,
                                   PhotoStore photoStore,
                                   @Value(RenderConfig.DEFAULT_ARTIFACT_DIR) String artifactDir) {
        return new NoteService(noteTxService, aliasGenerator, photoStore, Path.of(artifactDir));
    }

    // 사진 업로드 유스케이스(0029 TΔ8a) — 포맷 게이트 → EXIF 제거 → 스테이징. 트랜잭션이 없는 것이
    // 계층 축과 정합한다: 사진은 파일이고 노트 행과 같은 원자 단위가 아니다(백엔드 CLAUDE.md §3).
    @Bean
    public PhotoService photoService(PhotoStore photoStore) {
        return new PhotoService(photoStore);
    }
}
