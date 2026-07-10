package com.devwuu.mocha.render;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * T3-5 골격용 임시 {@link SiteRenderer} — 리렌더 트리거를 로그로만 남기고 실제 HTML을 만들지 않는다.
 * <p>{@code DefaultConfirmationFlow}의 [저장] 커밋이 렌더 트리거까지 배선됨을 확인하려는 목적이다
 * (plan.md §1 [6]→[7]).
 * <p><b>T5-1에서 Thymeleaf 기반 실제 렌더러로 대체된다</b> — 이 클래스는 그때 제거한다.
 */
@Component
public class LoggingSiteRenderer implements SiteRenderer {

    private static final Logger log = LoggerFactory.getLogger(LoggingSiteRenderer.class);

    @Override
    public void renderAll() {
        log.info("전체 리렌더 트리거(T5-1 미구현): site 산출은 아직 만들지 않습니다.");
    }
}
