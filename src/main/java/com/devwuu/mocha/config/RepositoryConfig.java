package com.devwuu.mocha.config;

import com.devwuu.mocha.repository.LocalPhotoStore;
import com.devwuu.mocha.repository.PhotoStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Path;

/**
 * <b>파일 매체</b> 저장소 빈 배선 — 사진 원본이 {@code mocha.data.dir} 아래에서 온다
 * (plan.md §5, CLAUDE.md §3, changes/0028 ADR-73·74). 노트는 DB에 있고 배선은 {@link ServiceConfig}가
 * 소유한다(0029 TΔ4c).
 */
@Configuration
public class RepositoryConfig {

    // POLICY: mocha.* 키는 코드 default를 갖는다 — 설정 부재로 기동이 막히지 않는다
    // (ref: specs/coffee-note-agent/plan.md#ADR-50 POLICY). 값은 plan §5 문서값(./data)과 일치시킨다.
    // changes/0025 CR25-8: RB-B6이 mocha.artifact.dir만 고쳐 같은 부류가 이 파일에 남아 있었다.
    // 0029 TΔ5a: WebConfig가 사진 아카이브를 정적 서빙하며 같은 키를 읽는다 — 두 곳이 각자의 default를
    // 들면 설정 부재 프로파일에서 저장 위치와 서빙 위치가 갈린다(RenderConfig.DEFAULT_ARTIFACT_DIR과 같은 방침).
    public static final String DEFAULT_DATA_DIR = "${mocha.data.dir:./data}";

    // 노트가 DB로 옮겨졌어도 사진 원본은 계속 파일이다 — mocha.data.dir는 그 뿌리로 잔존한다.
    // 0029 TΔ4: pending 저장소 빈이 사라졌다 — 작성 중 데이터는 클라이언트 폼이 소유하고 서버는 기억하지
    // 않는다(delta 0029 D-2). TTL 설정 키(mocha.pending.ttl)도 함께 소멸했다.
    // 0029 TΔ4c: 노트 빈도 여기서 사라졌다 — 구 JpaNoteRepository가 이미 트랜잭션 경계였음이 드러나
    // service/NoteTxService로 재정의됐고(delta D-9), 배선은 ServiceConfig가 가져갔다.
    // 0029 TΔ16: photo buffer 빈이 사라졌다 — 시간 윈도우로 사진과 발화를 묶던 Slack 배관(FR-10)이고,
    // 앱에서는 사용자가 ＋ 버튼으로 직접 묶어(D-3·D-11) 추정할 것이 없다. 남은 파일 매체는 사진 하나다.
    @Bean
    public PhotoStore photoStore(@Value(DEFAULT_DATA_DIR) String dataDir) {
        return new LocalPhotoStore(Path.of(dataDir));
    }
}
