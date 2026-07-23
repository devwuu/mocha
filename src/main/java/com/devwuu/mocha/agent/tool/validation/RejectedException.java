package com.devwuu.mocha.agent.tool.validation;

/**
 * 검증 거부 — 사유를 담아 {@link ToolValidation.Rejected}로 수렴하는 패키지 내부 신호.
 * 규칙 패밀리(출처·회차·단일 대기)와 진입점이 공유한다 — 진입점이 잡아 거부 결과로 변환한다.
 */
final class RejectedException extends RuntimeException {

    RejectedException(String reason) {
        super(reason);
    }
}
