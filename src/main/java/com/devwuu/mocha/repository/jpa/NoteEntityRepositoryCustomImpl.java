package com.devwuu.mocha.repository.jpa;

import static com.devwuu.mocha.repository.entity.QBrewEntity.brewEntity;
import static com.devwuu.mocha.repository.entity.QEntryEntity.entryEntity;
import static com.devwuu.mocha.repository.entity.QNoteAliasEntity.noteAliasEntity;
import static com.devwuu.mocha.repository.entity.QNoteBeanEntity.noteBeanEntity;
import static com.devwuu.mocha.repository.entity.QNoteEntity.noteEntity;
import static com.devwuu.mocha.repository.entity.QNoteOfficialNoteEntity.noteOfficialNoteEntity;
import static com.devwuu.mocha.repository.entity.QNotePhotoEntity.notePhotoEntity;
import static com.devwuu.mocha.repository.entity.QNoteSourceEntity.noteSourceEntity;
import static com.devwuu.mocha.repository.entity.QRecipeEntity.recipeEntity;
import static com.devwuu.mocha.repository.entity.QTastingEntity.tastingEntity;

import com.devwuu.mocha.domain.NoteCandidate;
import com.devwuu.mocha.domain.NoteCursor;
import com.devwuu.mocha.domain.NoteFacets;
import com.devwuu.mocha.domain.NoteFilter;
import com.devwuu.mocha.domain.NoteListItem;
import com.devwuu.mocha.domain.NotePhoto;
import com.devwuu.mocha.repository.entity.BrewEntity;
import com.devwuu.mocha.repository.entity.EntryEntity;
import com.devwuu.mocha.repository.entity.RecipeEntity;
import com.devwuu.mocha.repository.entity.TastingEntity;
import com.querydsl.core.BooleanBuilder;
import com.querydsl.core.Tuple;
import com.querydsl.core.types.Predicate;
import com.querydsl.core.types.Projections;
import com.querydsl.core.types.dsl.BooleanExpression;
import com.querydsl.core.types.dsl.DateExpression;
import com.querydsl.jpa.JPAExpressions;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;

import java.time.LocalDate;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * {@link NoteEntityRepositoryCustom} 구현 — Spring Data가 <b>이름 규약</b>({@code <계약>Impl})으로 찾아
 * {@link NoteEntityRepository}에 붙인다.
 *
 * <p>질의를 JPQL 문자열이 아니라 QueryDSL로 쓰는 이유는 가독성이 <b>질의가 복잡해질수록</b> 갈리기
 * 때문이다 — 여기 조회 8종은 아직 단순하지만 날짜 이동(TΔ5c)·순서 삭제(TΔ5d)가 같은 자리에 들어온다.
 *
 * <p>주입받는 {@link EntityManager}는 Spring이 스레드 바인딩 영속성 컨텍스트로 위임하는 프록시다 —
 * 이 구현이 트랜잭션을 열지 않는다. 경계는 호출부({@code NoteTxService})가 소유한다(Q-11, ADR-77).
 */
class NoteEntityRepositoryCustomImpl implements NoteEntityRepositoryCustom {

    private final EntityManager em;
    private final JPAQueryFactory query;

    NoteEntityRepositoryCustomImpl(EntityManager em) {
        this.em = em;
        this.query = new JPAQueryFactory(em);
    }

    @Override
    public NoteChildRows findChildRows(Collection<Long> noteIds) {
        if (noteIds.isEmpty()) {
            return NoteChildRows.empty();
        }
        List<EntryEntity> entries = query.selectFrom(entryEntity)
                .where(entryEntity.noteId.in(noteIds))
                // Note 계약: entries는 날짜 오름차순. 순서를 질의가 지므로 조립부가 재정렬하지 않는다.
                .orderBy(entryEntity.noteId.asc(), entryEntity.tastedOn.asc())
                .fetch();
        List<Long> entryIds = entries.stream().map(EntryEntity::getId).toList();

        List<BrewEntity> brews = entryIds.isEmpty() ? List.of()
                : query.selectFrom(brewEntity)
                        .where(brewEntity.entryId.in(entryIds))
                        // AC-Δ4: seq가 회차 번호를 소유한다 — 구 "배열 순서 = 회차"의 암묵 의존을 대체.
                        .orderBy(brewEntity.entryId.asc(), brewEntity.seq.asc())
                        .fetch();
        List<Long> brewIds = brews.stream().map(BrewEntity::getId).toList();

        return new NoteChildRows(
                query.selectFrom(noteBeanEntity)
                        .where(noteBeanEntity.noteId.in(noteIds))
                        .orderBy(noteBeanEntity.noteId.asc(), noteBeanEntity.seq.asc()).fetch(),
                query.selectFrom(noteOfficialNoteEntity)
                        .where(noteOfficialNoteEntity.noteId.in(noteIds))
                        .orderBy(noteOfficialNoteEntity.noteId.asc(), noteOfficialNoteEntity.seq.asc()).fetch(),
                // 별칭에만 seq가 없다 — 유일성 기준이 (note_id, kind, normalized)라 순번 컬럼을 둘 자리가
                // 없고, 대신 표기의 첫 등장 순서를 id가 보존한다(V-13).
                query.selectFrom(noteAliasEntity)
                        .where(noteAliasEntity.noteId.in(noteIds))
                        .orderBy(noteAliasEntity.noteId.asc(), noteAliasEntity.id.asc()).fetch(),
                query.selectFrom(noteSourceEntity)
                        .where(noteSourceEntity.noteId.in(noteIds))
                        .orderBy(noteSourceEntity.noteId.asc(), noteSourceEntity.seq.asc()).fetch(),
                entries,
                brews,
                // recipe·tasting은 brew_id가 곧 PK라 짝짓기가 조회 조건 그 자체다(TΔ3b의 PK 공유 1:1).
                brewIds.isEmpty() ? List.<RecipeEntity>of()
                        : query.selectFrom(recipeEntity).where(recipeEntity.brewId.in(brewIds)).fetch(),
                brewIds.isEmpty() ? List.<TastingEntity>of()
                        : query.selectFrom(tastingEntity).where(tastingEntity.brewId.in(brewIds)).fetch());
    }

    @Override
    public List<NoteCandidate> findCandidates(String normalizedQuery) {
        return query
                .select(Projections.constructor(NoteCandidate.class,
                        noteEntity.id,
                        noteEntity.coffeeName.value,
                        noteEntity.roastery.value,
                        // 엔트리가 없는 노트는 여기가 null이다 — 그래서 join이 left다(inner면 그 노트가
                        // 후보에서 통째로 빠지는데, 삭제 직후가 정확히 그 상태다).
                        entryEntity.tastedOn.max()))
                .from(noteEntity)
                .leftJoin(entryEntity).on(entryEntity.noteId.eq(noteEntity.id))
                .where(matches(normalizedQuery))
                // 노트마다 엔트리 수만큼 행이 불어난 것을 다시 접는다. 별칭 축을 join이 아니라 exists로
                // 둔 것도 같은 이유고(아래), 그쪽은 접을 필요조차 없게 만든 것이다.
                .groupBy(noteEntity.id, noteEntity.coffeeName.value, noteEntity.roastery.value,
                        noteEntity.coffeeNameNormalized, noteEntity.roasteryNormalized)
                // 표시값이 아니라 정규화 컬럼으로 정렬한다 — 같은 커피의 표기 흔들림(대소문자·공백)으로
                // 목록 순서가 갈리지 않는다. 인덱스 이득은 아니다(실측: 집계 뒤 Sort 노드가 선다).
                .orderBy(noteEntity.coffeeNameNormalized.asc(),
                        // NULLS LAST를 명시한다 — 로스터리 미상·시음 기록 없음을 뒤로 보낸다. Postgres
                        // 기본값은 ASC에서 LAST, DESC에서 FIRST라 아래 줄은 안 적으면 뒤집힌다.
                        noteEntity.roasteryNormalized.asc().nullsLast(),
                        entryEntity.tastedOn.max().desc().nullsLast())
                .fetch();
    }

    /**
     * 검색 대조 — 커피명·로스터리·별칭 셋 중 하나라도 걸리면 후보다. 빈 검색어는 필터 없음(전건).
     *
     * <p>별칭만 {@code exists} 서브쿼리인 것은 <b>행이 불어나는 축이라서</b>다: 노트 하나에 별칭이 여럿
     * 걸리므로 join하면 같은 노트가 여러 줄이 된다. 위 {@code groupBy}가 접어 주긴 하지만, 걸리는지만
     * 알면 되는 축을 굳이 실어 왔다 접을 이유가 없다.
     *
     * <p>{@code contains}는 Querydsl이 {@code like %…%}로 옮기며 {@code %}·{@code _}를 이스케이프한다 —
     * 사용자가 친 {@code %}가 와일드카드로 새지 않는다({@code NoteTxServiceCandidatesTest}가 박는다).
     */
    private static Predicate matches(String normalizedQuery) {
        if (normalizedQuery == null || normalizedQuery.isEmpty()) {
            return null;
        }
        BooleanExpression aliasHit = JPAExpressions.selectOne().from(noteAliasEntity)
                .where(noteAliasEntity.noteId.eq(noteEntity.id),
                        noteAliasEntity.normalized.contains(normalizedQuery))
                .exists();
        return noteEntity.coffeeNameNormalized.contains(normalizedQuery)
                // 로스터리 정규화는 nullable — SQL에서 null LIKE는 null(=거짓)이라 별도 가드가 필요 없다.
                .or(noteEntity.roasteryNormalized.contains(normalizedQuery))
                .or(aliasHit);
    }

    @Override
    public List<NoteListItem> findNoteItems(NoteFilter filter, NoteCursor cursor, int limit) {
        // 최근 시음일은 집계값이라 leftJoin + groupBy로 낸다(findCandidates와 같은 형태). left인 이유도
        // 같다 — 엔트리가 없는 노트는 inner join에서 목록 자체에서 빠지는데, 삭제 직후가 그 상태다.
        List<Tuple> rows = query
                .select(noteEntity.id, noteEntity.coffeeName.value, noteEntity.roastery.value,
                        entryEntity.tastedOn.max())
                .from(noteEntity)
                .leftJoin(entryEntity).on(entryEntity.noteId.eq(noteEntity.id))
                .where(filters(filter))
                .groupBy(noteEntity.id, noteEntity.coffeeName.value, noteEntity.roastery.value)
                // 커서는 집계값(최근 시음일)과 견주므로 where가 아니라 having이다.
                .having(after(cursor))
                .orderBy(entryEntity.tastedOn.max().desc().nullsLast(), noteEntity.id.desc())
                .limit(limit)
                .fetch();

        Map<Long, String> thumbnails = findThumbnails(
                rows.stream().map(row -> row.get(noteEntity.id)).toList());
        return rows.stream()
                .map(row -> new NoteListItem(
                        row.get(noteEntity.id),
                        row.get(noteEntity.coffeeName.value),
                        row.get(noteEntity.roastery.value),
                        row.get(entryEntity.tastedOn.max()),
                        thumbnails.get(row.get(noteEntity.id))))
                .toList();
    }

    /**
     * 노트별 <b>가장 최근 날짜의 첫 장</b> — 없는 노트는 키가 없다.
     *
     * <p>사진을 실어 와 첫 행만 취하는 것은 페이지가 노트 수십 건이고 노트당 사진이 한 자릿수라서다.
     * 노트별 {@code MAX(tasted_on)} 상관 서브쿼리로 좁힐 수도 있지만, 그러면 <b>같은 날짜의 최소 seq</b>를
     * 다시 골라야 해 서브쿼리가 두 겹이 된다 — 이 규모에서 값을 내지 못하는 복잡도다(루트 §4).
     */
    private Map<Long, String> findThumbnails(List<Long> noteIds) {
        if (noteIds.isEmpty()) {
            return Map.of();
        }
        List<Tuple> rows = query.select(notePhotoEntity.noteId, notePhotoEntity.path)
                .from(notePhotoEntity)
                .where(notePhotoEntity.noteId.in(noteIds))
                // 노트별 첫 행이 곧 답이 되는 순서 — 최근 날짜가 먼저, 그 안에서는 붙인 순서대로.
                .orderBy(notePhotoEntity.noteId.asc(), notePhotoEntity.tastedOn.desc(),
                        notePhotoEntity.seq.asc())
                .fetch();

        Map<Long, String> thumbnails = new LinkedHashMap<>();
        for (Tuple row : rows) {
            thumbnails.putIfAbsent(row.get(notePhotoEntity.noteId), row.get(notePhotoEntity.path));
        }
        return thumbnails;
    }

    @Override
    public long countNotes(NoteFilter filter) {
        // 엔트리 join이 없다 — 필터 축 어디에도 최근 시음일이 없으므로 노트 행만 세면 된다(집계·중복 제거
        // 없이 정확하다). 필터가 실제로 거는 축은 전부 exists거나 노트 본문 컬럼이다.
        Long count = query.select(noteEntity.count()).from(noteEntity).where(filters(filter)).fetchOne();
        return count == null ? 0L : count;
    }

    @Override
    public NoteFacets findFacets() {
        return new NoteFacets(
                query.select(noteEntity.roastery.value).distinct().from(noteEntity)
                        .where(noteEntity.roastery.value.isNotNull())
                        .orderBy(noteEntity.roastery.value.asc()).fetch(),
                query.select(noteBeanEntity.process.value).distinct().from(noteBeanEntity)
                        .where(noteBeanEntity.process.value.isNotNull())
                        .orderBy(noteBeanEntity.process.value.asc()).fetch());
    }

    /**
     * 커서 이후 — 정렬 축 {@code (최근 시음일 desc nulls last, id desc)}의 "그 지점보다 뒤".
     *
     * <p><b>NULLS LAST가 조건을 둘로 가른다</b>: 날짜 있는 지점에서는 <i>날짜가 더 이르거나</i>
     * <i>날짜가 아예 없거나</i>(그쪽이 뒤에 서므로) <i>같은 날짜의 더 작은 id</i>가 뒤다. 날짜 없는
     * 지점에서는 이미 맨 뒤 구간이라 <b>같은 구간의 더 작은 id</b>만 남는다.
     */
    private static Predicate after(NoteCursor cursor) {
        if (cursor == null) {
            return null;
        }
        DateExpression<LocalDate> latestDate = entryEntity.tastedOn.max();
        if (cursor.latestDate() == null) {
            return latestDate.isNull().and(noteEntity.id.lt(cursor.noteId()));
        }
        return latestDate.lt(cursor.latestDate())
                .or(latestDate.isNull())
                .or(latestDate.eq(cursor.latestDate()).and(noteEntity.id.lt(cursor.noteId())));
    }

    /**
     * 필터 4축 + 검색어 — <b>축 간 AND</b>. 걸린 축이 없으면 {@code null}(전건).
     *
     * <p>원두·회차 축이 join이 아니라 {@code exists}인 것은 {@link #findCandidates}의 별칭 축과 같은
     * 이유다: 노트 하나에 원두·회차가 여럿이라 join하면 행이 불어나고, 걸리는지만 알면 되는 축을 굳이
     * 실어 왔다 접을 이유가 없다. <b>같은 축 안이 OR</b>인 것은 {@code in}·{@code exists} 하나가 표현한다 —
     * <i>"프릳츠 아니면 모모스"</i>가 조건 하나다.
     */
    private static Predicate filters(NoteFilter filter) {
        if (filter == null) {
            return null;
        }
        BooleanBuilder where = new BooleanBuilder();
        where.and(matches(filter.query()));
        if (!filter.roastery().isEmpty()) {
            // 표시값으로 대조한다 — facet이 준 값을 그대로 되받으므로 표기가 흔들릴 여지가 없다.
            where.and(noteEntity.roastery.value.in(filter.roastery()));
        }
        if (!filter.process().isEmpty()) {
            where.and(JPAExpressions.selectOne().from(noteBeanEntity)
                    .where(noteBeanEntity.noteId.eq(noteEntity.id),
                            noteBeanEntity.process.value.in(filter.process()))
                    .exists());
        }
        String origin = filter.origin() == null ? "" : filter.origin().strip();
        if (!origin.isEmpty()) {
            // 유일한 부분일치 축 — 원산지 컬럼이 없어 자유 텍스트 설명으로 근사한다(NoteFilter javadoc).
            where.and(JPAExpressions.selectOne().from(noteBeanEntity)
                    .where(noteBeanEntity.noteId.eq(noteEntity.id),
                            noteBeanEntity.description.value.containsIgnoreCase(origin))
                    .exists());
        }
        if (!filter.rating().isEmpty()) {
            // entry → brew → tasting 세 단을 exists 하나로 — 연관 매핑이 없어 조인 조건을 값으로 적는다.
            where.and(JPAExpressions.selectOne().from(tastingEntity, brewEntity, entryEntity)
                    .where(brewEntity.id.eq(tastingEntity.brewId),
                            entryEntity.id.eq(brewEntity.entryId),
                            entryEntity.noteId.eq(noteEntity.id),
                            tastingEntity.rating.in(filter.rating()))
                    .exists());
        }
        return where.getValue();
    }

    @Override
    public List<NotePhoto> findPhotos(long noteId) {
        return query.select(Projections.constructor(NotePhoto.class,
                        notePhotoEntity.tastedOn, notePhotoEntity.path))
                .from(notePhotoEntity)
                .where(notePhotoEntity.noteId.eq(noteId))
                // 계약 순서: 날짜 → 그 안의 seq. findPhotoPaths와 같은 정렬이라 두 표면이 갈리지 않는다.
                .orderBy(notePhotoEntity.tastedOn.asc(), notePhotoEntity.seq.asc())
                .fetch();
    }

    @Override
    public <T> T insertAndFlush(T row) {
        em.persist(row);
        em.flush();
        return row;
    }

    @Override
    public void insertAll(Collection<?> rows) {
        rows.forEach(em::persist);
    }

    @Override
    public Optional<Long> findEntryId(long noteId, LocalDate tastedOn) {
        return Optional.ofNullable(query.select(entryEntity.id).from(entryEntity)
                .where(entryEntity.noteId.eq(noteId), entryEntity.tastedOn.eq(tastedOn))
                .fetchOne());
    }

    @Override
    public Optional<EntryEntity> findEntry(long noteId, LocalDate tastedOn) {
        return Optional.ofNullable(query.selectFrom(entryEntity)
                .where(entryEntity.noteId.eq(noteId), entryEntity.tastedOn.eq(tastedOn))
                .fetchOne());
    }

    @Override
    public long countBrews(long entryId) {
        Long count = query.select(brewEntity.count()).from(brewEntity)
                .where(brewEntity.entryId.eq(entryId))
                .fetchOne();
        return count == null ? 0L : count;
    }

    @Override
    public List<String> findPhotoPaths(long noteId) {
        return query.select(notePhotoEntity.path).from(notePhotoEntity)
                .where(notePhotoEntity.noteId.eq(noteId))
                // 계약 순서: 날짜 → 그 안의 seq. 아카이브 폴더를 훑은 것과 같은 순서로 보인다.
                .orderBy(notePhotoEntity.tastedOn.asc(), notePhotoEntity.seq.asc())
                .fetch();
    }

    @Override
    public long countPhotos(long noteId, LocalDate tastedOn) {
        Long count = query.select(notePhotoEntity.count()).from(notePhotoEntity)
                .where(notePhotoEntity.noteId.eq(noteId), notePhotoEntity.tastedOn.eq(tastedOn))
                .fetchOne();
        return count == null ? 0L : count;
    }

    @Override
    public void deleteNoteArraysExceptAliases(long noteId) {
        query.delete(noteBeanEntity).where(noteBeanEntity.noteId.eq(noteId)).execute();
        query.delete(noteOfficialNoteEntity).where(noteOfficialNoteEntity.noteId.eq(noteId)).execute();
        query.delete(noteSourceEntity).where(noteSourceEntity.noteId.eq(noteId)).execute();
    }

    @Override
    public void deleteBrews(long entryId) {
        // 회차 id를 먼저 집는다 — recipe·tasting은 brew_id로만 걸려 있어(1:1 PK 공유) 부모가 사라진 뒤에는
        // 지울 근거가 없어진다. FK가 없으므로 이 순서를 잃으면 고아 행이 조용히 남는다(ADR-75).
        List<Long> brewIds = query.select(brewEntity.id).from(brewEntity)
                .where(brewEntity.entryId.eq(entryId)).fetch();
        if (brewIds.isEmpty()) {
            return;
        }
        query.delete(tastingEntity).where(tastingEntity.brewId.in(brewIds)).execute();
        query.delete(recipeEntity).where(recipeEntity.brewId.in(brewIds)).execute();
        query.delete(brewEntity).where(brewEntity.id.in(brewIds)).execute();
    }

    @Override
    public void deleteEntry(long entryId) {
        deleteBrews(entryId);
        query.delete(entryEntity).where(entryEntity.id.eq(entryId)).execute();
    }

    @Override
    public void deleteNote(long noteId) {
        deleteEntries(noteId);
        deleteNoteArraysExceptAliases(noteId);
        // 별칭은 여기서만 지운다 — 수정 세션이 남기는 것은 원본 존치(V-13) 때문이고 노트가 사라지는
        // 자리에는 그 근거가 없다.
        query.delete(noteAliasEntity).where(noteAliasEntity.noteId.eq(noteId)).execute();
        // 사진 색인도 함께 — 파일은 이 트랜잭션이 아니라 호출부(NoteService)가 커밋 뒤에 지운다.
        // 순서가 그쪽인 근거는 V3 마이그레이션 POLICY가 소유한다(사진 바이트를 조용히 잃지 않는다).
        query.delete(notePhotoEntity).where(notePhotoEntity.noteId.eq(noteId)).execute();
        // 노트 행은 마지막이다. 엔티티로 실어 와 em.remove()에 맡기지 않는 것은 deleteEntry와 같은 이유 —
        // Hibernate의 flush 순서가 삭제를 항상 마지막에 두므로, 삭제가 언제 나가는지를 코드가 쥘 수 없다.
        query.delete(noteEntity).where(noteEntity.id.eq(noteId)).execute();
    }

    /**
     * 노트의 엔트리 <b>전부</b>를 하위부터 — {@link #deleteBrews}를 엔트리마다 부르지 않고 id를 모아 한 번에
     * 지운다. 엔트리가 몇 건이든 질의 수가 고정된다(수집 2 + 삭제 4).
     */
    private void deleteEntries(long noteId) {
        List<Long> entryIds = query.select(entryEntity.id).from(entryEntity)
                .where(entryEntity.noteId.eq(noteId)).fetch();
        if (entryIds.isEmpty()) {
            return;
        }
        List<Long> brewIds = query.select(brewEntity.id).from(brewEntity)
                .where(brewEntity.entryId.in(entryIds)).fetch();
        if (!brewIds.isEmpty()) {
            query.delete(tastingEntity).where(tastingEntity.brewId.in(brewIds)).execute();
            query.delete(recipeEntity).where(recipeEntity.brewId.in(brewIds)).execute();
            query.delete(brewEntity).where(brewEntity.id.in(brewIds)).execute();
        }
        query.delete(entryEntity).where(entryEntity.id.in(entryIds)).execute();
    }
}
