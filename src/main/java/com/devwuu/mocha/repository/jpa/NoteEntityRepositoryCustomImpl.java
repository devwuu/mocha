package com.devwuu.mocha.repository.jpa;

import static com.devwuu.mocha.repository.entity.QBrewEntity.brewEntity;
import static com.devwuu.mocha.repository.entity.QEntryEntity.entryEntity;
import static com.devwuu.mocha.repository.entity.QNoteAliasEntity.noteAliasEntity;
import static com.devwuu.mocha.repository.entity.QNoteBeanEntity.noteBeanEntity;
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

import java.util.Collection;
import java.util.List;

/**
 * {@link NoteEntityRepositoryCustom} 구현 — Spring Data가 <b>이름 규약</b>({@code <계약>Impl})으로 찾아
 * {@link NoteEntityRepository}에 붙인다.
 *
 * <p>질의를 JPQL 문자열이 아니라 QueryDSL로 쓰는 이유는 가독성이 <b>질의가 복잡해질수록</b> 갈리기
 * 때문이다 — 여기 조회 8종은 아직 단순하지만 날짜 이동(TΔ5c)·순서 삭제(TΔ5d)가 같은 자리에 들어온다.
 *
 * <p>주입받는 {@link EntityManager}는 Spring이 스레드 바인딩 영속성 컨텍스트로 위임하는 프록시다 —
 * 이 구현이 트랜잭션을 열지 않는다. 경계는 호출부({@code JpaNoteRepository})가 소유한다(Q-11).
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
}
