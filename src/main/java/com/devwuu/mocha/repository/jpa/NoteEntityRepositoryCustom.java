package com.devwuu.mocha.repository.jpa;

import java.util.Collection;

/**
 * {@link NoteEntityRepository}의 QueryDSL 몫 — 노트의 <b>자식 9종</b> 입출력.
 *
 * <p>구현은 {@link NoteEntityRepositoryCustomImpl}(Spring Data가 이름 규약으로 찾아 붙인다).
 *
 * <p>이 계약이 아는 것은 <b>행과 순서까지</b>다. 행을 도메인으로 조립하는 일(3단 중첩 재구성, 배열 분해·
 * 재조립)은 {@code JpaNoteRepository}가 소유한다 — 관계를 아는 곳을 하나로 모으기 위해서다.
 */
public interface NoteEntityRepositoryCustom {

    /**
     * 부모 id 목록에 걸린 자식 행 전부를 <b>정렬된 채로</b> 가져온다.
     *
     * <p>노트가 몇 건이든 질의 수가 고정된다(배열 4 + 엔트리 1 + 회차 1 + 레시피 1 + 감상 1 = 8) — 엔티티에
     * 연관이 없어(TΔ3a) 지연 로딩이 없고, 따라서 N+1이 생길 자리도 없다. 내부의 id 사슬
     * ({@code noteIds → entryIds → brewIds})은 질의 배관이라 여기서 닫는다.
     */
    NoteChildRows findChildRows(Collection<Long> noteIds);

    /**
     * 생성 id가 <b>즉시 필요한</b> 행 하나를 넣는다 — {@code entry}·{@code brew}처럼 자식이 그 id를 컬럼으로
     * 받아야 하는 경우다(FK가 없으므로 애플리케이션이 값을 옮긴다, ADR-74). flush가 곧 id 확정이다.
     */
    <T> T insertAndFlush(T row);

    /** 생성 id를 쓰지 않는 행들 — 배열 4종·{@code recipe}·{@code tasting}. flush는 트랜잭션에 맡긴다. */
    void insertAll(Collection<?> rows);
}
