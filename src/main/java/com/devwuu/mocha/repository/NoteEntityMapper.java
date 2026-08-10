package com.devwuu.mocha.repository;

import com.devwuu.mocha.domain.Aliases;
import com.devwuu.mocha.domain.Bean;
import com.devwuu.mocha.domain.Cup;
import com.devwuu.mocha.domain.TastingDay;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.NoteMeta;
import com.devwuu.mocha.domain.Recipe;
import com.devwuu.mocha.domain.Source;
import com.devwuu.mocha.domain.Sourced;
import com.devwuu.mocha.domain.Review;
import com.devwuu.mocha.repository.entity.AliasKind;
import com.devwuu.mocha.repository.entity.CupEntity;
import com.devwuu.mocha.repository.entity.TastingDayEntity;
import com.devwuu.mocha.repository.entity.NoteAliasEntity;
import com.devwuu.mocha.repository.entity.NoteBeanEntity;
import com.devwuu.mocha.repository.entity.NoteEntity;
import com.devwuu.mocha.repository.entity.NoteOfficialNoteEntity;
import com.devwuu.mocha.repository.entity.NoteSourceEntity;
import com.devwuu.mocha.repository.entity.RecipeEntity;
import com.devwuu.mocha.repository.entity.SourcedValue;
import com.devwuu.mocha.repository.entity.ReviewEntity;
import com.devwuu.mocha.repository.jpa.NoteChildRows;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 도메인 record ↔ JPA 엔티티 양방향 변환 (ref: changes/0028-rdb-storage/tasks.md TΔ3c).
 *
 * <p>POLICY: 변환은 <b>저장소 쪽에만</b> 있다 — 도메인 record는 영속 타입을 모른다(백엔드 CLAUDE.md §4,
 * REVIEW.md §2). 엔티티도 도메인 타입을 조립하지 않는다: 엔티티는 행 하나를 표현하고, 3단 중첩
 * ({@code Note → tastingDays → cups → recipe/review})과 배열 6종의 분해·재조립은 전부 여기서 일어난다.
 *
 * <p><b>조립은 아래에서 위로</b> 한다 — 엔티티에 연관 매핑이 없으므로({@link NoteEntity} POLICY) 부모가
 * 자식 행을 알지 못한다. 평면 행 목록({@link NoteChildRows})을 부모별로 그룹핑해
 * {@link #toCup} → {@link #toTastingDay} → {@link #toNote} 순으로 올리는 것이 {@link #assemble}이다.
 * <b>정렬은 질의가 소유한다</b>: seq 있는 3종은 {@code seq} 오름차순, 별칭은 {@code id} 오름차순
 * (= 첫 등장 순서, V-13). 이 클래스는 받은 목록의 순서를 그대로 보존할 뿐 재정렬하지 않는다.
 *
 * <p><b>조립이 여기 있는 이유</b>(0029 TΔ4c): 변환도 조립도 <b>순수 함수</b>라 트랜잭션과 무관하다. 구
 * {@code JpaNoteRepository}가 이것과 비즈니스 로직을 함께 지고 있었고, 그 클래스가
 * {@link com.devwuu.mocha.service.NoteTxService}("한 트랜잭션 단위")로 재정의되면서 축에 맞지 않는 절반이
 * 이리로 내려왔다. 남겨 뒀다면 "트랜잭션 단위인가"라는 기준이 다시 흐려졌을 자리다(delta D-9).
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

    private static final Logger log = LoggerFactory.getLogger(NoteEntityMapper.class);

    private NoteEntityMapper() {
    }

    // ────────────────────────────── 도메인 → 엔티티 ──────────────────────────────

    /**
     * 노트 본문 행. 하위 배열(beans·official_notes·aliases·sources)과 시음일은 각자의 메서드가 만든다 —
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

    /** 시음일 행. 회차는 {@link CupEntity}로 따로 나가고, {@code updatedAt}은 감사 컬럼이 겸한다(Q-5). */
    public static TastingDayEntity toTastingDayEntity(Long noteId, TastingDay tastingDay) {
        return new TastingDayEntity(noteId, tastingDay.date());
    }

    /**
     * 레시피 행 — 회차의 {@code recipe}가 없으면 {@code null}(행 미생성).
     * <p>{@code Double} → {@code numeric} 변환에서 비유한값은 예외로 튄다. V-8 정규화
     * ({@link Recipe#normalize})가 저장 경계에서 이미 드롭하므로 여기 도달하지 않고, 도달한다면
     * DB CHECK와 같은 방향(거부)이라 삼키지 않는다.
     */
    public static RecipeEntity toRecipeEntity(Long cupId, Recipe recipe) {
        if (recipe == null) {
            return null;
        }
        return new RecipeEntity(cupId, recipe.method(),
                toNumeric(recipe.doseG()), toNumeric(recipe.waterMl()), toNumeric(recipe.yieldMl()),
                toNumeric(recipe.timeSec()), toNumeric(recipe.tempC()), toNumeric(recipe.grind()),
                recipe.grinder(), recipe.detail(), recipe.feedback());
    }

    /** 감상 행 — 회차의 {@code review}가 없으면 {@code null}(행 미생성, V-15). */
    public static ReviewEntity toReviewEntity(Long cupId, Review review) {
        if (review == null) {
            return null;
        }
        return new ReviewEntity(cupId, review.myTaste(), review.myTasteOriginal(), review.rating());
    }

    /** 출처 표시 필드 → (value, source) 두 컬럼. */
    public static SourcedValue toSourcedValue(Sourced<String> sourced) {
        return sourced == null ? null : new SourcedValue(sourced.value(), sourced.source());
    }

    // ────────────────────────────── 엔티티 → 도메인 (조립) ──────────────────────────────

    /**
     * 노트 행 목록 + 그에 걸린 자식 행 전부 → 도메인 노트 목록 (0029 TΔ4c에서 구 저장소 구현체로부터 이관).
     *
     * <p>자식은 부모 id 목록으로 <b>한 번에</b> 받아 메모리에서 그룹핑한다 — 노트가 몇 건이든 질의 수가
     * 고정된다({@link NoteChildRows} 계약). 질의는 호출부가 이미 끝냈고 여기서는 <b>행 → 도메인 재구성만</b>
     * 한다: 순수 함수라 트랜잭션이 필요 없다.
     *
     * @param rows     노트 본문 행 — <b>결과 순서를 이 목록이 소유한다</b>(재정렬하지 않는다)
     * @param children 위 노트들에 걸린 자식 행 전부, 질의가 정렬해 준 순서 그대로
     */
    public static List<Note> assemble(List<NoteEntity> rows, NoteChildRows children) {
        if (rows.isEmpty()) {
            return List.of();
        }
        Map<Long, List<NoteBeanEntity>> beansByNote = groupBy(children.beans(), NoteBeanEntity::getNoteId);
        Map<Long, List<NoteOfficialNoteEntity>> officialNotesByNote =
                groupBy(children.officialNotes(), NoteOfficialNoteEntity::getNoteId);
        Map<Long, List<NoteAliasEntity>> aliasesByNote = groupBy(children.aliases(), NoteAliasEntity::getNoteId);
        Map<Long, List<NoteSourceEntity>> sourcesByNote = groupBy(children.sources(), NoteSourceEntity::getNoteId);
        Map<Long, List<TastingDay>> tastingDaysByNote = assembleTastingDays(children);

        List<Note> assembled = new ArrayList<>(rows.size());
        for (NoteEntity row : rows) {
            long id = row.getId();
            Note domain = toNote(
                    row,
                    beansByNote.getOrDefault(id, List.of()),
                    officialNotesByNote.getOrDefault(id, List.of()),
                    aliasesByNote.getOrDefault(id, List.of()),
                    sourcesByNote.getOrDefault(id, List.of()),
                    tastingDaysByNote.getOrDefault(id, List.of()));
            assembled.add(sanitized(domain, id));
        }
        return List.copyOf(assembled);
    }

    /** 시음일 → 회차 → 레시피/감상 세 단을 노트별로 되묶는다. 짝짓기는 전부 부모 id 조회다(연관 없음). */
    private static Map<Long, List<TastingDay>> assembleTastingDays(NoteChildRows children) {
        Map<Long, List<CupEntity>> cupsByTastingDay = groupBy(children.cups(), CupEntity::getTastingDayId);
        Map<Long, RecipeEntity> recipeByCup = children.recipes().stream()
                .collect(Collectors.toMap(RecipeEntity::getCupId, Function.identity()));
        Map<Long, ReviewEntity> reviewByCup = children.reviews().stream()
                .collect(Collectors.toMap(ReviewEntity::getCupId, Function.identity()));

        Map<Long, List<TastingDay>> byNote = new LinkedHashMap<>();
        for (TastingDayEntity tastingDay : children.tastingDays()) {
            List<Cup> cups = cupsByTastingDay.getOrDefault(tastingDay.getId(), List.<CupEntity>of()).stream()
                    .map(cup -> toCup(recipeByCup.get(cup.getId()), reviewByCup.get(cup.getId())))
                    .toList();
            byNote.computeIfAbsent(tastingDay.getNoteId(), key -> new ArrayList<>())
                    .add(toTastingDay(tastingDay, cups));
        }
        return byNote;
    }

    /** 자식 행을 부모별로 묶는다 — 그룹 안의 순서는 질의가 준 순서 그대로다(재정렬하지 않는다). */
    private static <T> Map<Long, List<T>> groupBy(List<T> rows, Function<T, Long> parentOf) {
        return rows.stream().collect(Collectors.groupingBy(parentOf, LinkedHashMap::new, Collectors.toList()));
    }

    /**
     * 로드 경계 위생 (ref: plan.md#ADR-66, changes/0025).
     *
     * <p>POLICY: 저장소 <b>읽기 경계가 유일한 관문</b>이고, 그 관문은 이 한 곳이다 — 모든 조회가
     * {@link #assemble}을 지나므로 조회 경로마다 정책이 갈라지지 않는다. 스키마가 강제하지 못하는 규칙
     * (V-14 빈 원두·V-15 빈 회차)은 psql 직접 편집으로 들어올 수 있어 파일 시절과 같은 방어가 남는다.
     * DB는 다시 쓰지 않는다 — 읽기 메모리 정규화만이며, 드롭분은 그 노트의 다음 저장에서 반영되므로
     * 경고 로그로 관측을 남긴다.
     *
     * <p><b>범위 결론(OQ-1, 0028 TΔ5b 실측)</b>: 정규화는 <b>축소하지 않는다</b>. 스키마와 겹치는 것은 V-8
     * 수치 5종뿐이고 그마저 판정 방향이 반대다 — CHECK는 저장 <b>전체를 거부</b>하고 정규화는 <b>그 항목만
     * 드롭</b>한다. 나머지(공백 문자열 접기, 빈 구조체 드롭, 자식 없는 회차 드롭)는 {@code TEXT NOT NULL}이
     * {@code ''}를 통과시키는 한 CHECK로 표현되지 않는다.
     *
     * <p>그래서 <b>쓰기 경로에는 걸지 않는다</b> — 저장 경로가 정규화를 걸면 V-8 위반이 조용히 드롭돼
     * AC-Δ3("위반 삽입 실패")이 관측 불가가 된다. 정규화 진입점은 종전대로 검증기다.
     */
    private static Note sanitized(Note note, long id) {
        Note sanitized = note.normalized();
        if (sanitized != note) {
            log.warn("노트 로드 위생(ADR-66): 무효 요소 드롭 — note id={} (DB 직접 편집 위반 추정, DB는 다시 쓰지 않음. 드롭분은 다음 저장 시 반영됨)",
                    id);
        }
        return sanitized;
    }

    /** 노트 조립 — 자식 행 목록과 <b>이미 조립된</b> 시음일을 받는다({@link #toTastingDay}). */
    public static Note toNote(NoteEntity note, List<NoteBeanEntity> beans,
                              List<NoteOfficialNoteEntity> officialNotes, List<NoteAliasEntity> aliases,
                              List<NoteSourceEntity> sources, List<TastingDay> tastingDays) {
        return new Note(
                note.getId(),
                toSourced(note.getCoffeeName()),
                toSourced(note.getRoastery()),
                beans.stream().map(NoteEntityMapper::toBean).toList(),
                toSourced(note.getRoastLevel()),
                toOfficialNotes(note.getOfficialNotesSource(), officialNotes),
                toAliases(aliases),
                sources.stream().map(NoteSourceEntity::getUrl).toList(),
                tastingDays,
                note.getCreatedAt(),
                // Q-5: 감사 컬럼이 도메인 타임스탬프를 겸한다 — modified_at이 곧 updatedAt이다.
                note.getModifiedAt());
    }

    /** 시음일 조립 — 회차는 {@link #toCup}로 올라온 것을 받는다. 순서는 질의({@code seq} 오름차순)가 소유. */
    public static TastingDay toTastingDay(TastingDayEntity tastingDay, List<Cup> cups) {
        return new TastingDay(tastingDay.getTastedOn(), cups, tastingDay.getModifiedAt());
    }

    /**
     * 회차 조립 — {@code cup} 행은 식별자만 갖고, 실체는 {@code cup_id}를 PK로 공유하는 두 행이다.
     * 도메인 {@link Cup}가 레시피·감상을 직접 품는 형태로 되돌린다(짝은 구조가 표현한다, ADR-59).
     */
    public static Cup toCup(RecipeEntity recipe, ReviewEntity review) {
        return new Cup(toRecipe(recipe), toReview(review));
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
                toDouble(recipe.getTimeSec()), toDouble(recipe.getTempC()), toDouble(recipe.getGrind()),
                recipe.getGrinder(), recipe.getDetail(), recipe.getFeedback());
    }

    public static Review toReview(ReviewEntity review) {
        if (review == null) {
            return null;
        }
        return new Review(review.getMyTaste(), review.getMyTasteOriginal(), review.getRating());
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
