package com.devwuu.mocha.repository.jpa;

import static com.devwuu.mocha.repository.entity.QBrewEntity.brewEntity;
import static com.devwuu.mocha.repository.entity.QEntryEntity.entryEntity;
import static com.devwuu.mocha.repository.entity.QNoteAliasEntity.noteAliasEntity;
import static com.devwuu.mocha.repository.entity.QNoteBeanEntity.noteBeanEntity;
import static com.devwuu.mocha.repository.entity.QNoteEntity.noteEntity;
import static com.devwuu.mocha.repository.entity.QNoteOfficialNoteEntity.noteOfficialNoteEntity;
import static com.devwuu.mocha.repository.entity.QNoteSourceEntity.noteSourceEntity;
import static com.devwuu.mocha.repository.entity.QRecipeEntity.recipeEntity;
import static com.devwuu.mocha.repository.entity.QTastingEntity.tastingEntity;

import com.devwuu.mocha.repository.entity.BrewEntity;
import com.devwuu.mocha.repository.entity.EntryEntity;
import com.devwuu.mocha.repository.entity.RecipeEntity;
import com.devwuu.mocha.repository.entity.TastingEntity;
import com.querydsl.jpa.impl.JPAQueryFactory;
import jakarta.persistence.EntityManager;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
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
