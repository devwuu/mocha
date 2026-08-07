package com.devwuu.mocha.repository.jpa;

import com.devwuu.mocha.domain.NoteCandidate;
import com.devwuu.mocha.domain.NoteCursor;
import com.devwuu.mocha.domain.NoteFacets;
import com.devwuu.mocha.domain.NoteFilter;
import com.devwuu.mocha.domain.NoteListItem;
import com.devwuu.mocha.domain.NotePhoto;
import com.devwuu.mocha.repository.entity.TastingDayEntity;
import com.devwuu.mocha.repository.entity.NotePhotoEntity;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * {@link NoteEntityRepository}의 QueryDSL 몫 — 노트의 <b>자식 10종</b> 입출력
 * (사진 {@code note_photo}이 changes/0029 TΔ8b에서 늘었다).
 *
 * <p>구현은 {@link NoteEntityRepositoryCustomImpl}(Spring Data가 이름 규약으로 찾아 붙인다).
 *
 * <p>이 계약이 아는 것은 <b>행과 순서까지</b>다. 행을 도메인으로 조립하는 일(3단 중첩 재구성, 배열 분해·
 * 재조립)은 {@code NoteEntityMapper}가 소유한다 — 관계를 아는 곳을 하나로 모으기 위해서다.
 */
public interface NoteEntityRepositoryCustom {

    /**
     * 부모 id 목록에 걸린 자식 행 전부를 <b>정렬된 채로</b> 가져온다.
     *
     * <p>노트가 몇 건이든 질의 수가 고정된다(배열 4 + 엔트리 1 + 회차 1 + 레시피 1 + 감상 1 = 8) — 엔티티에
     * 연관이 없어(TΔ3a) 지연 로딩이 없고, 따라서 N+1이 생길 자리도 없다. 내부의 id 사슬
     * ({@code noteIds → tastingDayIds → cupIds})은 질의 배관이라 여기서 닫는다.
     */
    NoteChildRows findChildRows(Collection<Long> noteIds);

    /**
     * 매칭 후보 검색 — 변경 시트가 부르는 유일한 질의 (ref: changes/0029 tasks.md TΔ7).
     *
     * <p><b>대조 축은 셋이고 전부 정규화 기준이다</b>: 커피명·로스터리·별칭. 별칭이 축인 것이 이 질의의
     * 값이다 — {@code 예가체프 G1}·{@code 예가체프 지1}·{@code 예가체프 G-1}이 한 노트에 심겨 있으므로
     * (V-13, ADR-37) 사용자가 어느 표기로 치든 같은 노트가 잡혀야 한다. 커피명 원문만 보면 못 잡는다.
     *
     * <p>정렬은 <b>커피명 → 로스터리 → 최근 시음일 내림차순</b>이다(사용자 확정 2026-08-01). 관련도
     * 순위(완전일치 → prefix → 부분일치)는 <b>의도적으로 두지 않았다</b> — 이 스키마에는 그것을 표현할
     * 수단이 없고(임베딩·{@code pg_trgm}·{@code tsvector} 전부 부재, 인덱스는 B-tree 둘뿐), SQL로 흉내 내면
     * 이름만 관련도이고 실질은 문자열 매칭인 코드가 남아 임베딩 도입 시 걷어내야 한다.
     * <b>재론 트리거</b>: 관련도가 실제로 필요해지면 단계를 늘리지 말고 임베딩으로 간다(루트 CLAUDE.md §4).
     *
     * <p>앞 두 축이 <b>정규화 컬럼</b>인 것은 표기 흔들림(대소문자·공백)으로 목록 순서가 갈리지 않게 하기
     * 위해서다. 인덱스 이득은 아니다 — 실측하면 집계 뒤에 Sort 노드가 서고, 부분일치({@code like %…%})는
     * 선행 와일드카드라 B-tree가 애초에 못 탄다. <b>0028의 인덱스 둘이 여기서 하는 일은 대조 기준을
     * 제공하는 것까지</b>이고({@code note_alias}의 UNIQUE가 상관 서브쿼리의 {@code note_id} 조회는 받는다),
     * 전건 스캔이 문제가 되는 규모가 아니다(1인용·노트 수십 건). 그 규모가 바뀌면 그때 답은 인덱스가
     * 아니라 위에 적은 임베딩이다.
     *
     * <p>3축(최근 시음일)이 실제로 발동하는 경우는 드물다 — 노트 정체성이 {@code coffee_name + roastery}라
     * 앞 두 축이 대개 유일하게 결정하고, 남는 것은 <b>로스터리가 없는 동명 노트가 여럿</b>일 때다.
     *
     * <p><b>{@code Note}가 아니라 사영을 돌려주는 이유</b>: 자식 8질의(={@link #findChildRows})가 통째로
     * 빠진다. 시트는 노트를 고르는 화면이라 3단 중첩을 한 줄도 쓰지 않는다.
     *
     * @param normalizedQuery {@code Aliases.normalize()}를 지난 검색어. <b>빈 문자열이면 필터 없이 전건</b>
     *                        — 커피명이 아직 안 뽑힌 상태에서도 시트가 쓸모 있어야 한다(TΔ11 계약).
     */
    List<NoteCandidate> findCandidates(String normalizedQuery);

    /**
     * 갤러리 목록 한 페이지 — 필터로 좁히고 커서 이후부터 {@code limit}건
     * (ref: changes/0029 tasks.md TΔ5a, 계약 {@code contract/note-list.contract.json}).
     *
     * <p>정렬은 <b>최근 시음일 내림차순(NULLS LAST) → note_id 내림차순</b>이다. 앞 축이 갤러리가 답하는
     * 질문(<i>"요즘 뭘 마셨나"</i>)이고, 뒤 축은 같은 날 마신 노트가 둘일 때 순서를 <b>결정론적으로</b>
     * 만든다 — 없으면 페이지 경계에서 같은 노트가 두 번 보이거나 한 건이 사라진다.
     *
     * <p><b>{@link #findCandidates}와 정렬이 반대인 것은 자리의 성격 차이다</b>: 후보 시트는 이름으로
     * 찾는 자리라 커피명 순이고, 갤러리는 훑는 자리라 최근순이다.
     *
     * <p>썸네일은 <b>가장 최근 날짜의 첫 장</b>이다 — 상세 화면의 히어로와 같은 사진이라 갤러리에서 들어온
     * 맥락이 끊기지 않는다. 그 선택은 질의가 소유한다(사진 목록을 통째로 올려 조립부가 고르지 않는다).
     *
     * @param filter 좁히기 조건 — {@code query}는 <b>이미 정규화된 것</b>이어야 한다
     *               ({@link com.devwuu.mocha.domain.NoteFilter#normalized()}).
     * @param cursor 이전 페이지의 마지막 지점. {@code null}이면 첫 페이지.
     * @param limit  최대 건수. 호출부는 <b>페이지 크기 + 1</b>을 넘겨 다음 페이지 유무를 판정한다.
     */
    List<NoteListItem> findNoteItems(NoteFilter filter, NoteCursor cursor, int limit);

    /**
     * 그 필터에 걸리는 노트 총수 — 헤더의 <i>"N편의 기록"</i>.
     * <p>커서는 대상이 아니다: 페이지를 넘겨도 총수는 그대로여야 한다.
     */
    long countNotes(NoteFilter filter);

    /**
     * 필터 칩의 선택지 — 저장된 로스터리·가공방식 전부, 오름차순.
     * <p>POLICY: 필터를 적용하지 않는다 — 근거는 {@link NoteFacets}가 소유한다.
     */
    NoteFacets findFacets();

    /**
     * 그 노트의 사진 전부 — {@code (날짜, seq)} 오름차순, 없으면 빈 목록.
     *
     * <p>{@link #findPhotoPaths}와 갈라 두는 이유는 <b>날짜가 필요한가</b>다. 저쪽은 파일을 지우거나 폴더
     * 접미를 되읽는 자리라 경로만 있으면 되고, 이쪽은 상세 화면이 사진을 <b>엔트리에 붙이는</b> 자리라
     * {@code note_photo}의 참조 축({@code (note_id, tasted_on)})이 그대로 필요하다.
     */
    List<NotePhoto> findPhotos(long noteId);

    /**
     * 생성 id가 <b>즉시 필요한</b> 행 하나를 넣는다 — {@code tasting_day}·{@code cup}처럼 자식이 그 id를 컬럼으로
     * 받아야 하는 경우다(FK가 없으므로 애플리케이션이 값을 옮긴다, ADR-75). flush가 곧 id 확정이다.
     */
    <T> T insertAndFlush(T row);

    /** 생성 id를 쓰지 않는 행들 — 배열 4종·{@code recipe}·{@code review}. flush는 트랜잭션에 맡긴다. */
    void insertAll(Collection<?> rows);

    /**
     * 노트 안에서 그 날짜의 엔트리 id — 없으면 빈 Optional. {@code UNIQUE(note_id, tasted_on)}가 하루
     * 한 건을 보장하므로(V-10) 답은 최대 하나다.
     *
     * <p>행이 아니라 <b>id만</b> 돌려주는 것은 곧 지울 대상이기 때문이다 — 엔티티로 실어 오면 벌크 삭제
     * 뒤 영속성 컨텍스트에 죽은 사본이 남는다. <b>수정할</b> 대상은 {@link #findTastingDay}가 준다.
     */
    Optional<Long> findTastingDayId(long noteId, LocalDate tastedOn);

    /**
     * 같은 조회를 <b>관리되는 엔티티</b>로 — 대상이 지울 것이 아니라 <b>고칠 것</b>일 때 쓴다(날짜 이동).
     *
     * <p>{@link #findTastingDayId}와 갈라 두는 이유가 그대로 계약이다: 돌려준 엔티티는 영속성 컨텍스트에 붙어
     * 있어 필드를 바꾸면 flush가 UPDATE로 내보낸다 — 지우고 다시 넣지 않으므로 {@code created_at}이
     * 보존되고 {@code modified_at}은 감사 리스너가 채운다(TΔ4). 논리 FK({@code note_id})로 찾을 뿐
     * 연관 매핑은 여전히 없다.
     */
    Optional<TastingDayEntity> findTastingDay(long noteId, LocalDate tastedOn);

    /**
     * 노트의 배열 자식을 지운다 — {@code note_bean}·{@code note_official_note}·{@code note_source}.
     *
     * <p><b>별칭은 대상이 아니다</b>(이름에 박아 둔 이유): 수정 세션은 별칭을 건드리지 않고 원본을 존치한다
     * (V-13). 별칭 축적은 재기록 경로({@code upsertTastingDay})만의 개념이고, 별칭에는 seq가 없어 지웠다 다시
     * 넣으면 id가 밀려 첫 등장 순서까지 무너진다.
     *
     * <p>세 배열은 통째 교체가 정책이라 부분 갱신 메서드를 두지 않는다 — {@code seq}가 순서를 소유하므로
     * 지우고 다시 넣어도 순서는 그대로다(별칭과 갈리는 지점).
     */
    void deleteNoteArraysExceptAliases(long noteId);

    /**
     * 엔트리의 <b>회차만</b> 지운다 — {@code review}·{@code recipe} → {@code cup}. 엔트리 행은 남는다.
     *
     * <p>수정 세션이 엔트리를 <b>살려 둔 채</b> 회차를 갈아끼우는 자리다(TΔ5c) — 회차는 통째 교체가
     * 정책이라(ADR-59) 부분 갱신 개념이 없고, {@code UNIQUE(tasting_day_id, seq)} 때문에 새 회차를 넣기 전에
     * 옛 seq가 비어 있어야 한다.
     */
    void deleteCups(long tastingDayId);

    /**
     * 엔트리 한 건을 하위부터 지운다 — {@link #deleteCups} 뒤에 {@code tasting_day} 행까지.
     * FK가 없으므로 순서를 코드가 소유한다(ADR-75).
     *
     * <p><b>벌크 DML이라 즉시 DB에 나간다</b> — 같은 {@code (note_id, tasted_on)}을 다시 쓰기 전에 UNIQUE가
     * 풀려 있어야 하는데, {@code em.remove()}로는 그 순서를 만들 수 없다. Hibernate의 flush 순서는
     * INSERT → UPDATE → DELETE라 삭제가 <b>항상 마지막</b>이다: 재삽입이든 날짜 이동 UPDATE든 아직 살아
     * 있는 행과 부딪힌다. 파일 구현과 갈리는 지점이다 — DB는 중간 상태에서 제약을 검사한다.
     */
    void deleteTastingDay(long tastingDayId);

    /**
     * 그 엔트리에 이미 붙어 있는 회차 수 — <b>이어붙일 다음 {@code seq}의 시작점</b>
     * (ref: changes/0029 tasks.md TΔ5b-1, delta.md#D-12).
     *
     * <p>회차를 <b>더하는</b> 경로는 날짜 이동 병합 하나뿐이다 — 재기록(ADR-4·59)도 수정({@code
     * replaceTastingDay})도 모델이 구성한 배열로 통째 교체하므로 시작점이 언제나 0이다. 이동만은 이동처의 회차를
     * 남긴 채 뒤로 잇기 때문에({@code UNIQUE(tasting_day_id, seq)}) 그 수를 물어야 한다.
     *
     * <p>{@code seq}가 0부터 빈 칸 없이 발급되므로 개수가 곧 다음 번호다({@link #countPhotos}와 같은 성질).
     */
    long countCups(long tastingDayId);

    /**
     * 그 노트에 딸린 사진 경로 전부 — {@code (tasted_on, seq)} 오름차순, 없으면 빈 목록
     * (ref: changes/0029 tasks.md TΔ8b).
     *
     * <p>값은 {@code photos/}로 시작하는 상대 경로다(V-4 어휘). 삭제 시 지울 파일 목록이자, <b>기존 노트에
     * 사진을 덧붙일 때 폴더 접미를 되읽는 자리</b>이기도 하다 — 접미는 생성 시점 스냅샷이라 지금 이름으로
     * 재계산하면 로스터리가 수정된 노트에서 옛 폴더와 갈린다(0028 파생 결정 2가 남긴 한계).
     */
    List<String> findPhotoPaths(long noteId);

    /**
     * 그 (노트, 날짜)의 사진 행을 <b>관리되는 엔티티</b>로, {@code seq} 오름차순
     * (ref: changes/0029 tasks.md TΔ5b-2).
     *
     * <p>{@link #findPhotos}·{@link #findPhotoPaths}와 갈라 두는 이유는 {@link #findTastingDay}가
     * {@link #findTastingDayId}와 갈리는 이유와 같다 — 저 둘은 <b>보여 줄 것</b>이라 사영이고, 이쪽은
     * <b>고칠 것</b>이라 영속성 컨텍스트에 붙어 있어야 한다(날짜 이동이 {@code tasted_on}·{@code seq}·
     * {@code path}를 UPDATE로 내보낸다).
     *
     * <p>정렬이 {@code seq}인 것도 계약이다: 이동처 뒤로 이어 붙일 때 그날 안의 순서가 보존돼야 한다.
     */
    List<NotePhotoEntity> findPhotoRows(long noteId, LocalDate tastedOn);

    /**
     * 그 (노트, 날짜)에 이미 붙어 있는 사진 수 — 다음 {@code seq}의 시작점.
     *
     * <p>같은 날짜를 다시 저장하면 엔트리는 통째로 교체되지만(ADR-4·59) <b>사진은 쌓인다</b> — 파일이
     * 아카이브에 그대로 남아 있고, 그것을 지우는 것은 재기록의 뜻이 아니기 때문이다.
     */
    long countPhotos(long noteId, LocalDate tastedOn);

    /**
     * 노트 한 건을 <b>하위부터</b> 통째로 지운다 — review·recipe → cup → tasting_day → 배열 4종 + 사진 → note
     * (ref: changes/0028-rdb-storage/tasks.md TΔ5d, AC-Δ8; 사진은 changes/0029 TΔ8b).
     *
     * <p>{@code cascade}·{@code orphanRemoval}을 쓰지 않으므로(ADR-75) 이 순서를 <b>코드가 소유한다</b> —
     * {@link #deleteTastingDay}가 엔트리 하나에 대해 지는 책임을 애그리거트 전체로 넓힌 자리다. 부모를 먼저
     * 지우면 자식은 걸릴 곳을 잃고 DB는 아무 말도 하지 않는다(FK가 없다).
     *
     * <p><b>별칭도 함께 지운다</b> — {@link #deleteNoteArraysExceptAliases}가 별칭을 남기는 것은 수정 세션이
     * 원본을 존치하기 때문이고(V-13), 노트 자체가 사라지는 자리에서는 그 근거가 없다.
     *
     * <p>엔트리·회차를 <b>집합으로</b> 지운다 — 엔트리가 몇 건이든 질의 수가 고정된다(파생
     * {@code deleteAllBy…}는 엔티티를 로드한 뒤 한 건씩 지운다).
     *
     * <p><b>노트 행이 지워진 건수를 돌려준다</b>(changes/0029 TΔ5b-3) — 없는 id를 404로 가르는 데 필요한
     * 값이고, 지울 노트를 <i>읽지 않고</i> 얻는 유일한 방법이다({@code NoteTxService#delete}의 규율).
     * 하위 행 건수는 세지 않는다: 고아가 남아 있어도 노트가 없었다면 답은 <i>"없던 노트"</i>다.
     *
     * @return 지워진 {@code note} 행 수 — 0(없는 id) 또는 1.
     */
    long deleteNote(long noteId);
}
