package com.devwuu.mocha.service;

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
 * <p>정상 경로(저장 확정·취소)는 스테이징을 그때그때 비운다. 그럼에도 프로세스가 스테이징과 정리 사이에서
 * 죽거나, 포맷 게이트(ADR-29) 도입 전의 poison이 남으면 스테이징에 잔존물이 생긴다 — 시작 시 걸러 낸다.
 * <p>POLICY: 스테이징은 <b>한 요청 안에서만 산다</b> — 시작 시 남아 있는 것은 정의상 고아다
 * (ref: specs/coffee-note-agent/plan.md#ADR-29).
 * <p><b>0029 TΔ16에서 판정이 단항이 됐다.</b> 종전에는 "살아있는 <i>버퍼</i>가 참조하는 스테이징은 산 것"으로
 * 보호했는데, 버퍼(시간 윈도우로 사진과 발화를 묶던 Slack 배관, FR-10)가 이 델타에서 소멸했다 — 앱에서는
 * 사용자가 ＋ 버튼으로 직접 묶으므로 추정할 윈도우가 없다(delta D-3·D-11). 판정 입력이 사라진 것이지
 * 규칙이 느슨해진 것이 아니다: TΔ4가 예고한 <i>"스테이징 수명이 요청 안으로 짧아지며 닫히는 창"</i>이
 * 여기서 닫혔고, 남은 스테이징은 예외 없이 고아다.
 * <p><b>TΔ8a까지는 생성처가 없어 무동작</b>이다 — 사진 입구가 REST 업로드로 다시 서기 전까지 스테이징에
 * 새로 쌓이는 것이 없다. 그래도 남겨 두는 이유는 <b>기존 잔존물</b>(Slack 시절의 스테이징)을 걷는 일이
 * 남아 있어서다.
 * <p>{@code !rerender} 프로파일에서만 — 리렌더 전용 실행은 1회성 배치라 대기 상태를 건드리지 않는다.
 */
@Component
@Profile("!rerender")
public class StagingSweeper implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(StagingSweeper.class);

    private final PhotoStore photoStore;

    public StagingSweeper(PhotoStore photoStore) {
        this.photoStore = photoStore;
    }

    @Override
    public void run(ApplicationArguments args) {
        int swept = 0;
        for (String userId : photoStore.stagedUserIds()) {
            photoStore.discard(userId);
            swept++;
            log.info("스테이징 고아 청소: user={}", userId);
        }
        if (swept > 0) {
            log.info("시작 시 스테이징 고아 {}건 청소 완료.", swept);
        }
    }
}
