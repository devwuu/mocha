package com.devwuu.mocha.repository;

import com.devwuu.mocha.domain.Aliases;
import com.devwuu.mocha.domain.Bean;
import com.devwuu.mocha.domain.Brew;
import com.devwuu.mocha.domain.Entry;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.NoteMeta;
import com.devwuu.mocha.domain.Recipe;
import com.devwuu.mocha.domain.Source;
import com.devwuu.mocha.domain.Sourced;
import com.devwuu.mocha.domain.Tasting;
import com.devwuu.mocha.repository.entity.AliasKind;
import com.devwuu.mocha.repository.entity.BrewEntity;
import com.devwuu.mocha.repository.entity.EntryEntity;
import com.devwuu.mocha.repository.entity.NoteAliasEntity;
import com.devwuu.mocha.repository.entity.NoteBeanEntity;
import com.devwuu.mocha.repository.entity.NoteEntity;
import com.devwuu.mocha.repository.entity.NoteOfficialNoteEntity;
import com.devwuu.mocha.repository.entity.NoteSourceEntity;
import com.devwuu.mocha.repository.entity.RecipeEntity;
import com.devwuu.mocha.repository.entity.SourcedValue;
import com.devwuu.mocha.repository.entity.TastingEntity;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 도메인 record ↔ JPA 엔티티 양방향 변환 (ref: changes/0028-rdb-storage/tasks.md TΔ3c).
 *
 * <p>POLICY: 변환은 <b>저장소 쪽에만</b> 있다 — 도메인 record는 영속 타입을 모른다(백엔드 CLAUDE.md §4,
 * REVIEW.md §2). 엔티티도 도메인 타입을 조립하지 않는다: 엔티티는 행 하나를 표현하고, 3단 중첩
 * ({@code Note → entries → brews → recipe/tasting})과 배열 6종의 분해·재조립은 전부 여기서 일어난다.
 *
 * <p><b>조립은 아래에서 위로</b> 한다 — 엔티티에 연관 매핑이 없으므로({@link NoteEntity} POLICY) 부모가
 * 자식 행을 알지 못한다. 호출부(저장소 구현체)가 질의 결과를 그룹핑해
 * {@link #toBrew} → {@link #toEntry} → {@link #toNote} 순으로 올린다. <b>정렬은 질의가 소유한다</b>:
 * seq 있는 3종은 {@code seq} 오름차순, 별칭은 {@code id} 오름차순(= 첫 등장 순서, V-13). 이 클래스는
 * 받은 목록의 순서를 그대로 보존할 뿐 재정렬하지 않는다.
 *
 * <p><b>도메인↔스키마 간극 4건</b>을 여기서 흡수한다:
 * <ul>
 *   <li>{@code Sourced<String>} → (value, source) 두 컬럼({@link SourcedValue})</li>
 *   <li>{@code officialNotes}({@code Sourced<List<String>>}) → 값은 {@code note_official_note} 행,
 *       source는 {@code note.official_notes_source} 컬럼 하나(Q-8)</li>
 *   <li>{@link Aliases}의 목록 두 개 → {@code note_alias} 한 테이블 + {@link AliasKind} 판별 컬럼</li>
 *   <li>{@link Recipe}의 {@code Double} → {@code numeric}({@link BigDecimal}) — V-8이 요구하는 "양수
 *       <b>유한</b>"을 CHECK로 강제하려면 {@code double precision}으로 부족했다(TΔ2 실측)</li>
 * </ul>
 *
 * <p><b>식별자</b>: 도메인 {@link Note#id()}와 {@code note.id}가 같은 값이므로 변환기는 행이 든 id를 그대로
 * 나른다. 도메인 → 엔티티 방향에서는 인자의 id를 <b>버린다</b> — 발급자는 {@code BIGSERIAL}이지 호출부가
 * 아니다(TΔ5a {@code insert} 계약). 변환기가 식별자를 발급하거나 유추하는 일은 어느 방향으로도 없다.
 *
 * <p><b>감사 컬럼</b>은 이 클래스가 채우지 않는다 — 도메인 → 엔티티 방향에서 {@code createdAt}/
 * {@code updatedAt}은 버려지고 값 주입은 Spring Data Auditing이 맡는다(Q-5, TΔ4). 역방향에서는 감사
 * 컬럼이 도메인 타임스탬프를 겸한다({@code created_at} → {@code createdAt}, {@code modified_at} →
 * {@code updatedAt}) — 같은 의미의 타임스탬프를 두 벌 두지 않는다.
 */
public final class NoteEntityMapper {

    private NoteEntityMapper() {
    }

    // ────────────────────────────── 도메인 → 엔티티 ──────────────────────────────

    /**
     * 노트 본문 행. 하위 배열(beans·official_notes·aliases·sources)과 엔트리는 각자의 메서드가 만든다 —
     * 자식이 부모 id를 컬럼으로 들기 때문에 노트 INSERT로 id를 받은 뒤에야 만들 수 있다(ADR-75).
     */
    public static NoteEntity toNoteEntity(Note note) {
        String coffeeName = Sourced.valueOrNull(note.coffeeName());
        String roastery = Sourced.valueOrNull(note.roastery());
        return new NoteEntity(
                toSourcedValue(note.coffeeName()),
                toSourcedValue(note.roastery()),
                toSourcedValue(note.roastLevel()),
                note.officialNotes() == null ? null : note.officialNotes().source(),
                // Q-6: 매칭 비교 키는 애플리케이션이 계산해 넣는다 — Aliases.normalize()가 단일 소스다.
                Aliases.normalize(coffeeName),
                roastery == null ? null : Aliases.normalize(roastery));
    }

    /**
     * 노트 본문 행의 <b>수정 가능한 필드</b>를 새 메타 값으로 덮는다 (FR-21, TΔ5c 수정 커밋).
     *
     * <p>{@code coffee_name}은 대상이 아니다 — 노트 생성 후 불변이라(V-9) {@link NoteEntity}에 setter조차
     * 없다. 값 검증(다른 커피명을 실었는가)은 service가 앞에서 거부하므로 여기 도달하지 않는다(0029 TΔ4a).
     *
     * <p>배열 자식(beans·official_notes·sources)은 별도 테이블이라 이 메서드 밖에서 교체된다. 갱신을
     * 저장소가 아니라 여기 둔 것은 <b>정규화 비교 키</b>(Q-6) 때문이다 — 로스터리가 바뀌면 함께 움직여야
     * 하는데, 계산을 호출부에 두면 {@link #toNoteEntity}와 규칙이 갈라진다.
     */
    public static void updateNoteEntity(NoteEntity row, NoteMeta meta) {
        String roastery = Sourced.valueOrNull(meta.roastery());
        row.updateRoastery(toSourcedValue(meta.roastery()),
                roastery == null ? null : Aliases.normalize(roastery));
        row.updateRoastLevel(toSourcedValue(meta.roastLevel()));
        row.updateOfficialNotesSource(meta.officialNotes() == null ? null : meta.officialNotes().source());
    }

    /** 원두 행 — 순서는 {@code seq}가 소유한다(0부터). */
    public static List<NoteBeanEntity> toBeanEntities(Long noteId, List<Bean> beans) {
        List<NoteBeanEntity> rows = new ArrayList<>();
        for (int seq = 0; seq < beans.size(); seq++) {
            Bean bean = beans.get(seq);
            rows.add(new NoteBeanEntity(noteId, seq,
                    toSourcedValue(bean.description()), toSourcedValue(bean.process())));
        }
        return rows;
    }

    /**
     * 공식 노트 행 — <b>값만</b> 내려간다. source는 배열 전체에 하나뿐이라
     * {@link #toNoteEntity}가 {@code note.official_notes_source}로 싣는다(Q-8).
     */
    public static List<NoteOfficialNoteEntity> toOfficialNoteEntities(Long noteId, Sourced<List<String>> officialNotes) {
        List<String> values = Sourced.valuesOrEmpty(officialNotes);
        List<NoteOfficialNoteEntity> rows = new ArrayList<>();
        for (int seq = 0; seq < values.size(); seq++) {
            rows.add(new NoteOfficialNoteEntity(noteId, seq, values.get(seq)));
        }
        return rows;
    }

    /**
     * 별칭 행 — 도메인의 목록 두 개를 {@link AliasKind}로 판별되는 한 테이블로 편다.
     * <p>{@code seq} 컬럼이 없다(유일성 기준이 {@code (note_id, kind, normalized)}) — 첫 등장 순서는
     * 삽입 순서 = id 순서로 보존되므로 커피명 별칭을 먼저, 로스터리 별칭을 뒤에 넣는다.
     * {@code normalized}는 대조·중복 제거의 기준이며 표시 형태({@code alias})와 함께 저장한다(V-13).
     */
    public static List<NoteAliasEntity> toAliasEntities(Long noteId, Aliases aliases) {
        if (aliases == null) {
            return List.of();
        }
        List<NoteAliasEntity> rows = new ArrayList<>();
        for (String alias : aliases.coffeeName()) {
            rows.add(new NoteAliasEntity(noteId, AliasKind.COFFEE_NAME, alias, Aliases.normalize(alias)));
        }
        for (String alias : aliases.roastery()) {
            rows.add(new NoteAliasEntity(noteId, AliasKind.ROASTERY, alias, Aliases.normalize(alias)));
        }
        return rows;
    }

    /** 검색 참조 링크 행 — 순서는 {@code seq}가 소유한다(0부터). */
    public static List<NoteSourceEntity> toSourceEntities(Long noteId, List<String> sources) {
        if (sources == null) {
            return List.of();
        }
        List<NoteSourceEntity> rows = new ArrayList<>();
        for (int seq = 0; seq < sources.size(); seq++) {
            rows.add(new NoteSourceEntity(noteId, seq, sources.get(seq)));
        }
        return rows;
    }

    /** 엔트리 행. 회차는 {@link BrewEntity}로 따로 나가고, {@code updatedAt}은 감사 컬럼이 겸한다(Q-5). */
    public static EntryEntity toEntryEntity(Long noteId, Entry entry) {
        return new EntryEntity(noteId, entry.date());
    }

    /**
     * 레시피 행 — 회차의 {@code recipe}가 없으면 {@code null}(행 미생성).
     * <p>{@code Double} → {@code numeric} 변환에서 비유한값은 예외로 튄다. V-8 정규화
     * ({@link Recipe#normalize})가 저장 경계에서 이미 드롭하므로 여기 도달하지 않고, 도달한다면
     * DB CHECK와 같은 방향(거부)이라 삼키지 않는다.
     */
    public static RecipeEntity toRecipeEntity(Long brewId, Recipe recipe) {
        if (recipe == null) {
            return null;
        }
        return new RecipeEntity(brewId, recipe.method(),
                toNumeric(recipe.doseG()), toNumeric(recipe.waterMl()), toNumeric(recipe.yieldMl()),
                toNumeric(recipe.timeSec()), toNumeric(recipe.tempC()),
                recipe.grind(), recipe.machine(), recipe.pouring(), recipe.feedback());
    }

    /** 감상 행 — 회차의 {@code tasting}이 없으면 {@code null}(행 미생성, V-15). */
    public static TastingEntity toTastingEntity(Long brewId, Tasting tasting) {
        if (tasting == null) {
            return null;
        }
        return new TastingEntity(brewId, tasting.myTaste(), tasting.myTasteOriginal(), tasting.rating());
    }

    /** 출처 표시 필드 → (value, source) 두 컬럼. */
    public static SourcedValue toSourcedValue(Sourced<String> sourced) {
        return sourced == null ? null : new SourcedValue(sourced.value(), sourced.source());
    }

    // ────────────────────────────── 엔티티 → 도메인 ──────────────────────────────

    /** 노트 조립 — 자식 행 목록과 <b>이미 조립된</b> 엔트리를 받는다({@link #toEntry}). */
    public static Note toNote(NoteEntity note, List<NoteBeanEntity> beans,
                              List<NoteOfficialNoteEntity> officialNotes, List<NoteAliasEntity> aliases,
                              List<NoteSourceEntity> sources, List<Entry> entries) {
        return new Note(
                note.getId(),
                toSourced(note.getCoffeeName()),
                toSourced(note.getRoastery()),
                beans.stream().map(NoteEntityMapper::toBean).toList(),
                toSourced(note.getRoastLevel()),
                toOfficialNotes(note.getOfficialNotesSource(), officialNotes),
                toAliases(aliases),
                sources.stream().map(NoteSourceEntity::getUrl).toList(),
                entries,
                note.getCreatedAt(),
                // Q-5: 감사 컬럼이 도메인 타임스탬프를 겸한다 — modified_at이 곧 updatedAt이다.
                note.getModifiedAt());
    }

    /** 엔트리 조립 — 회차는 {@link #toBrew}로 올라온 것을 받는다. 순서는 질의({@code seq} 오름차순)가 소유. */
    public static Entry toEntry(EntryEntity entry, List<Brew> brews) {
        return new Entry(entry.getTastedOn(), brews, entry.getModifiedAt());
    }

    /**
     * 회차 조립 — {@code brew} 행은 식별자만 갖고, 실체는 {@code brew_id}를 PK로 공유하는 두 행이다.
     * 도메인 {@link Brew}가 레시피·감상을 직접 품는 형태로 되돌린다(짝은 구조가 표현한다, ADR-59).
     */
    public static Brew toBrew(RecipeEntity recipe, TastingEntity tasting) {
        return new Brew(toRecipe(recipe), toTasting(tasting));
    }

    /** 원두 1종 — description·process가 각각 출처를 갖는다(V-6 요소 서브필드 단위). */
    public static Bean toBean(NoteBeanEntity bean) {
        return new Bean(toSourced(bean.getDescription()), toSourced(bean.getProcess()));
    }

    /**
     * 별칭 재조립 — {@link AliasKind}로 갈라 도메인의 목록 두 개로 되돌린다. 표시 형태({@code alias})를
     * 복원하며 {@code normalized}는 쓰지 않는다 — 정규화는 대조 기준이지 저장값이 아니다(V-13).
     */
    public static Aliases toAliases(List<NoteAliasEntity> rows) {
        if (rows == null || rows.isEmpty()) {
            return Aliases.empty();
        }
        return new Aliases(
                rows.stream().filter(r -> r.getKind() == AliasKind.COFFEE_NAME).map(NoteAliasEntity::getAlias).toList(),
                rows.stream().filter(r -> r.getKind() == AliasKind.ROASTERY).map(NoteAliasEntity::getAlias).toList());
    }

    public static Recipe toRecipe(RecipeEntity recipe) {
        if (recipe == null) {
            return null;
        }
        return new Recipe(recipe.getMethod(),
                toDouble(recipe.getDoseG()), toDouble(recipe.getWaterMl()), toDouble(recipe.getYieldMl()),
                toDouble(recipe.getTimeSec()), toDouble(recipe.getTempC()),
                recipe.getGrind(), recipe.getMachine(), recipe.getPouring(), recipe.getFeedback());
    }

    public static Tasting toTasting(TastingEntity tasting) {
        if (tasting == null) {
            return null;
        }
        return new Tasting(tasting.getMyTaste(), tasting.getMyTasteOriginal(), tasting.getRating());
    }

    /**
     * (value, source) 두 컬럼 → 출처 표시 필드.
     * <p>둘 다 비면 필드 자체가 부재한 것으로 수렴시킨다 — Hibernate가 {@code @Embedded} 전 컬럼 null을
     * {@code null}로 읽으므로 {@code Sourced<>(null, null)}은 DB 왕복에서 어차피 살아남지 못한다.
     * 판정을 이 한 곳에 두어 조회 경로마다 갈라지지 않게 한다.
     */
    public static Sourced<String> toSourced(SourcedValue value) {
        if (value == null || (value.value() == null && value.source() == null)) {
            return null;
        }
        return new Sourced<>(value.value(), value.source());
    }

    /**
     * 공식 노트 재조립 — 값 목록(별도 테이블)과 source 하나({@code note} 컬럼)를 다시 묶는다(Q-8).
     * <p>둘 다 비면 필드 부재({@code null})로 수렴한다. 값이 없고 source만 있는 경우는 "출처는 아는데
     * 아직 못 채운" 상태라 빈 목록으로 살려 둔다({@code roast_level}의 {@code Sourced<>(null, SEARCH)}와 같은 부류).
     */
    private static Sourced<List<String>> toOfficialNotes(Source source, List<NoteOfficialNoteEntity> rows) {
        List<String> values = rows.stream().map(NoteOfficialNoteEntity::getValue).toList();
        if (values.isEmpty() && source == null) {
            return null;
        }
        return new Sourced<>(values, source);
    }

    // V-8: 스키마가 numeric이라 도메인의 Double을 BigDecimal로 옮긴다. valueOf는 Double.toString 기반이라
    // 십진 표기가 그대로 보존된다(new BigDecimal(double)의 이진 오차 확산을 피한다).
    private static BigDecimal toNumeric(Double value) {
        return value == null ? null : BigDecimal.valueOf(value);
    }

    private static Double toDouble(BigDecimal value) {
        return value == null ? null : value.doubleValue();
    }
}
