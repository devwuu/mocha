package com.devwuu.mocha.slack;

import com.devwuu.mocha.repository.PhotoBufferStore;
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
 * <p>정상 경로(저장 커밋·취소·윈도우 밖 abandon)는 스테이징을 그때그때 비운다. 그럼에도 프로세스가
 * 스테이징과 정리 사이에서 죽거나, 포맷 게이트(ADR-29) 도입 전의 poison이 남으면 스테이징에 잔존물이
 * 생긴다. 재시작 시 이 잔존물은 어떤 살아있는 버퍼에도 참조되지 않는 <b>고아</b>다 —
 * {@code readStaged} 전체 재적재가 다시 집어 들지 않도록 시작 시 걸러 낸다(delta #3).
 * <p>POLICY: buffer가 <b>없는</b> 스테이징만 고아로 청소한다 — 살아있는 버퍼의 스테이징은 건드리지
 * 않는다(정상 clear는 불변, ADR-29).
 * <p>0029 TΔ4에서 판정 조건의 pending 항이 사라졌다. 대가는 <b>청소 범위가 넓어진 것</b>이다 — 종전에는
 * "작성 중인 노트가 서버에 있으면 그 스테이징은 산 것"이라 보호했는데, 그 상태를 이제 클라이언트가
 * 소유하므로 서버가 알 수 없다. 재시작 시점에 폼만 떠 있고 버퍼가 비어 있던 사진은 고아로 걷힌다.
 * 사진 업로드가 폼 경유로 바뀌고 저장 확정이 그것을 소비하면(TΔ8b) 스테이징 수명이 요청 안으로 짧아져
 * 이 창이 닫힌다.
 * <p>{@code !rerender} 프로파일에서만 — 리렌더 전용 실행은 소켓/대기 상태를 건드리지 않는다({@code SlackGateway}와 동일).
 */
@Component
@Profile("!rerender")
public class StagingSweeper implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StagingSweeper.class);

    private final PhotoStore photoStore;
    private final PhotoBufferStore photoBufferStore;

    public StagingSweeper(PhotoStore photoStore, PhotoBufferStore photoBufferStore) {
        this.photoStore = photoStore;
        this.photoBufferStore = photoBufferStore;
    }

    @Override
    public void run(ApplicationArguments args) {
        int swept = 0;
        for (String userId : photoStore.stagedUserIds()) {
            if (photoBufferStore.get(userId).isPresent()) {
                continue; // 살아있는 버퍼의 스테이징 — 커밋/취소/윈도우 로직이 정리한다.
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
