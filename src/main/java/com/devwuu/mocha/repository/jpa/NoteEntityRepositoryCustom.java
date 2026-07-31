package com.devwuu.mocha.repository.jpa;

import com.devwuu.mocha.repository.entity.EntryEntity;

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
     * 뒤 영속성 컨텍스트에 죽은 사본이 남는다. <b>수정할</b> 대상은 {@link #findEntry}가 준다.
     */
    Optional<Long> findEntryId(long noteId, LocalDate tastedOn);

    /**
     * 같은 조회를 <b>관리되는 엔티티</b>로 — 대상이 지울 것이 아니라 <b>고칠 것</b>일 때 쓴다(날짜 이동).
     *
     * <p>{@link #findEntryId}와 갈라 두는 이유가 그대로 계약이다: 돌려준 엔티티는 영속성 컨텍스트에 붙어
     * 있어 필드를 바꾸면 flush가 UPDATE로 내보낸다 — 지우고 다시 넣지 않으므로 {@code created_at}이
     * 보존되고 {@code modified_at}은 감사 리스너가 채운다(TΔ4). 논리 FK({@code note_id})로 찾을 뿐
     * 연관 매핑은 여전히 없다.
     */
    Optional<EntryEntity> findEntry(long noteId, LocalDate tastedOn);

    /**
     * 노트의 배열 자식을 지운다 — {@code note_bean}·{@code note_official_note}·{@code note_source}.
     *
     * <p><b>별칭은 대상이 아니다</b>(이름에 박아 둔 이유): 수정 세션은 별칭을 건드리지 않고 원본을 존치한다
     * (V-13). 별칭 축적은 재기록 경로({@code upsertEntry})만의 개념이고, 별칭에는 seq가 없어 지웠다 다시
     * 넣으면 id가 밀려 첫 등장 순서까지 무너진다.
     *
     * <p>세 배열은 통째 교체가 정책이라 부분 갱신 메서드를 두지 않는다 — {@code seq}가 순서를 소유하므로
     * 지우고 다시 넣어도 순서는 그대로다(별칭과 갈리는 지점).
     */
    void deleteNoteArraysExceptAliases(long noteId);

    /**
     * 엔트리의 <b>회차만</b> 지운다 — {@code tasting}·{@code recipe} → {@code brew}. 엔트리 행은 남는다.
     *
     * <p>수정 세션이 엔트리를 <b>살려 둔 채</b> 회차를 갈아끼우는 자리다(TΔ5c) — 회차는 통째 교체가
     * 정책이라(ADR-59) 부분 갱신 개념이 없고, {@code UNIQUE(entry_id, seq)} 때문에 새 회차를 넣기 전에
     * 옛 seq가 비어 있어야 한다.
     */
    void deleteBrews(long entryId);

    /**
     * 엔트리 한 건을 하위부터 지운다 — {@link #deleteBrews} 뒤에 {@code entry} 행까지.
     * FK가 없으므로 순서를 코드가 소유한다(ADR-74).
     *
     * <p><b>벌크 DML이라 즉시 DB에 나간다</b> — 같은 {@code (note_id, tasted_on)}을 다시 쓰기 전에 UNIQUE가
     * 풀려 있어야 하는데, {@code em.remove()}로는 그 순서를 만들 수 없다. Hibernate의 flush 순서는
     * INSERT → UPDATE → DELETE라 삭제가 <b>항상 마지막</b>이다: 재삽입이든 날짜 이동 UPDATE든 아직 살아
     * 있는 행과 부딪힌다. 파일 구현과 갈리는 지점이다 — DB는 중간 상태에서 제약을 검사한다.
     */
    void deleteEntry(long entryId);

    /**
     * 노트 한 건을 <b>하위부터</b> 통째로 지운다 — tasting·recipe → brew → entry → 배열 4종 → note
     * (ref: changes/0028-rdb-storage/tasks.md TΔ5d, AC-Δ8).
     *
     * <p>{@code cascade}·{@code orphanRemoval}을 쓰지 않으므로(ADR-74) 이 순서를 <b>코드가 소유한다</b> —
     * {@link #deleteEntry}가 엔트리 하나에 대해 지는 책임을 애그리거트 전체로 넓힌 자리다. 부모를 먼저
     * 지우면 자식은 걸릴 곳을 잃고 DB는 아무 말도 하지 않는다(FK가 없다).
     *
     * <p><b>별칭도 함께 지운다</b> — {@link #deleteNoteArraysExceptAliases}가 별칭을 남기는 것은 수정 세션이
     * 원본을 존치하기 때문이고(V-13), 노트 자체가 사라지는 자리에서는 그 근거가 없다.
     *
     * <p>엔트리·회차를 <b>집합으로</b> 지운다 — 엔트리가 몇 건이든 질의 수가 고정된다(파생
     * {@code deleteAllBy…}는 엔티티를 로드한 뒤 한 건씩 지운다).
     */
    void deleteNote(long noteId);
}
