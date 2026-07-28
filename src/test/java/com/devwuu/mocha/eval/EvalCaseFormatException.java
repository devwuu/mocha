package com.devwuu.mocha.eval;

/**
 * eval 케이스 파일이 스키마를 위반했을 때 던진다 (ref: changes/0026 AC-Δ1).
 * <p>메시지는 항상 <b>어느 케이스의 어느 필드가 왜 틀렸는지</b>를 담는다 — bare rejection 금지(REVIEW.md §6.3 준용).
 * 케이스는 사람이 손으로 쓰는 자산이라, 실패 메시지가 곧 포맷 문서 역할을 한다.
 */
public class EvalCaseFormatException extends RuntimeException {

    public EvalCaseFormatException(String message) {
        super(message);
    }

    public EvalCaseFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
