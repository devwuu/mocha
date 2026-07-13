package com.devwuu.mocha.slack;

import com.devwuu.mocha.repository.PhotoBufferStore;
import com.devwuu.mocha.repository.PendingStore;
import com.devwuu.mocha.repository.PhotoStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 앱 시작 시 스테이징 <b>고아</b> 파일을 청소한다 (ref: plan.md#ADR-29 · §7, data-model.md#V-12,
 * changes/0013-v3-field-quality-and-photo-intake).
 * <p>정상 경로(미리보기 성공·[저장] 커밋·[취소]·윈도우 밖 abandon)는 스테이징을 그때그때 비운다. 그럼에도
 * 프로세스가 스테이징과 정리 사이에서 죽거나, 포맷 게이트(ADR-29) 도입 전의 poison이 남으면 스테이징에
 * 잔존물이 생긴다. 재시작 시 이 잔존물은 어떤 살아있는 대기(pending·buffer)에도 참조되지 않는 <b>고아</b>다 —
 * {@code readStaged} 전체 재적재가 다시 집어 들지 않도록 시작 시 걸러 낸다(delta #3).
 * <p>POLICY: pending·buffer가 <b>둘 다 없는</b> 스테이징만 고아로 청소한다 — 살아있는 대기의 스테이징은
 * 건드리지 않는다(정상 clear는 불변, ADR-29). 만료된 pending은 {@link PendingStore#get}이 빈 값으로 수렴시켜
 * 자연히 고아로 분류된다(V-7).
 * <p>{@code !rerender} 프로파일에서만 — 리렌더 전용 실행은 소켓/대기 상태를 건드리지 않는다({@code SlackGateway}와 동일).
 */
@Component
@Profile("!rerender")
public class StagingSweeper implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StagingSweeper.class);

    private final PhotoStore photoStore;
    private final PendingStore pendingStore;
    private final PhotoBufferStore photoBufferStore;

    public StagingSweeper(PhotoStore photoStore, PendingStore pendingStore, PhotoBufferStore photoBufferStore) {
        this.photoStore = photoStore;
        this.pendingStore = pendingStore;
        this.photoBufferStore = photoBufferStore;
    }

    @Override
    public void run(ApplicationArguments args) {
        int swept = 0;
        for (String userId : photoStore.stagedUserIds()) {
            boolean live = pendingStore.get(userId).isPresent() || photoBufferStore.get(userId).isPresent();
            if (live) {
                continue; // 살아있는 대기의 스테이징 — 커밋/취소/윈도우 로직이 정리한다.
            }
            photoStore.discard(userId);
            swept++;
            log.info("스테이징 고아 청소: user={}", userId);
        }
        if (swept > 0) {
            log.info("시작 시 스테이징 고아 {}건 청소 완료.", swept);
        }
    }
}
