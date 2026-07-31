package com.devwuu.mocha.repository.jpa;

import java.time.LocalDate;
import java.util.Collection;
import java.util.Optional;

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

    /**
     * 노트 안에서 그 날짜의 엔트리 id — 없으면 빈 Optional. {@code UNIQUE(note_id, tasted_on)}가 하루
     * 한 건을 보장하므로(V-10) 답은 최대 하나다.
     *
     * <p>행이 아니라 <b>id만</b> 돌려주는 것은 곧 지울 대상이기 때문이다 — 엔티티로 실어 오면 벌크 삭제
     * 뒤 영속성 컨텍스트에 죽은 사본이 남는다.
     */
    Optional<Long> findEntryId(long noteId, LocalDate tastedOn);

    /**
     * 엔트리 한 건을 하위부터 지운다 — {@code tasting}·{@code recipe} → {@code brew} → {@code entry}.
     * FK가 없으므로 순서를 코드가 소유한다(ADR-74).
     *
     * <p><b>벌크 DML이라 즉시 DB에 나간다</b> — 같은 {@code (note_id, tasted_on)}으로 다시 삽입하기 전에
     * UNIQUE가 풀려 있어야 하는데, {@code em.remove()}로는 그 순서를 만들 수 없다(Hibernate는 flush에서
     * 삭제를 삽입 <b>뒤로</b> 미룬다). 파일 구현과 갈리는 지점이다 — DB는 중간 상태에서 제약을 검사한다.
     */
    void deleteEntry(long entryId);
}
