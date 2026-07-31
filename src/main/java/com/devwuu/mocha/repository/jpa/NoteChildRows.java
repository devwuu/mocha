package com.devwuu.mocha.repository.jpa;

import com.devwuu.mocha.repository.entity.BrewEntity;
import com.devwuu.mocha.repository.entity.EntryEntity;
import com.devwuu.mocha.repository.entity.NoteAliasEntity;
import com.devwuu.mocha.repository.entity.NoteBeanEntity;
import com.devwuu.mocha.repository.entity.NoteOfficialNoteEntity;
import com.devwuu.mocha.repository.entity.NoteSourceEntity;
import com.devwuu.mocha.repository.entity.RecipeEntity;
import com.devwuu.mocha.repository.entity.TastingEntity;

import java.util.List;

/**
 * 노트 애그리거트의 자식 행 묶음 — {@link NoteEntityRepositoryCustom#findChildRows}의 결과.
 *
 * <p><b>정렬된 평면 목록</b>이지 조립된 그래프가 아니다. 부모별 그룹핑과 3단 중첩 재구성은
 * {@code JpaNoteRepository}가 한다 — 이 층은 "어떤 행이 어떤 순서로 있는가"까지만 안다.
 *
 * <p>각 목록의 순서가 곧 도메인 순서다: 배열 3종·회차는 {@code seq} 오름차순, 엔트리는 {@code tasted_on}
 * 오름차순, 별칭은 {@code id} 오름차순(= 첫 등장 순서, V-13). {@code recipe}·{@code tasting}은
 * {@code brew_id}로 짝지어지므로 순서 개념이 없다.
 */
public record NoteChildRows(
        List<NoteBeanEntity> beans,
        List<NoteOfficialNoteEntity> officialNotes,
        List<NoteAliasEntity> aliases,
        List<NoteSourceEntity> sources,
        List<EntryEntity> entries,
        List<BrewEntity> brews,
        List<RecipeEntity> recipes,
        List<TastingEntity> tastings
) {

    public static NoteChildRows empty() {
        return new NoteChildRows(List.of(), List.of(), List.of(), List.of(),
                List.of(), List.of(), List.of(), List.of());
    }
}
