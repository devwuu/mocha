package com.devwuu.mocha.repository.entity;

/**
 * 별칭 종류 — 도메인 {@link com.devwuu.mocha.domain.Aliases}의 두 목록(coffeeName·roastery)을
 * 한 테이블({@code note_alias})로 정규화하면서 생긴 판별 컬럼이다(ADR-73).
 * <p>도메인에는 대응 타입이 없다 — 도메인은 목록 두 개를 필드로 갖고, "kind 컬럼"은 순전히 영속 형태다.
 * 분해·재조립은 변환기(TΔ3c)가 소유한다.
 */
public enum AliasKind {
    COFFEE_NAME,
    ROASTERY
}
