package com.devwuu.mocha.repository;

import com.devwuu.mocha.domain.Aliases;
import com.devwuu.mocha.domain.Bean;
import com.devwuu.mocha.domain.Brew;
import com.devwuu.mocha.domain.Entry;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.Rating;
import com.devwuu.mocha.domain.Recipe;
import com.devwuu.mocha.domain.Source;
import com.devwuu.mocha.domain.Sourced;
import com.devwuu.mocha.domain.Tasting;
import com.devwuu.mocha.repository.entity.AliasKind;
import com.devwuu.mocha.repository.entity.EntryEntity;
import com.devwuu.mocha.repository.entity.NoteAliasEntity;
import com.devwuu.mocha.repository.entity.NoteBeanEntity;
import com.devwuu.mocha.repository.entity.NoteEntity;
import com.devwuu.mocha.repository.entity.NoteOfficialNoteEntity;
import com.devwuu.mocha.repository.entity.NoteSourceEntity;
import com.devwuu.mocha.repository.entity.RecipeEntity;
import com.devwuu.mocha.repository.entity.SourcedValue;
import com.devwuu.mocha.repository.entity.TastingEntity;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TΔ3c: 도메인↔엔티티 변환기 — <b>왕복 동치성</b>이 판정이다
 * (ref: changes/0028-rdb-storage/tasks.md TΔ3c).
 *
 * <p>도메인 → 엔티티 → 도메인이 원본과 같아야 한다. 3단 중첩과 배열 6종이 행으로 흩어졌다가 다시
 * 모이는 과정에서 값·순서·출처 어느 것도 새지 않는지 본다. TΔ3a·TΔ3b가 {@code ddl-auto: validate}로
 * 닫지 못하고 남긴 것들 — record {@code @Embeddable}의 인스턴스화, {@code Double}↔{@code BigDecimal},
 * 별칭 kind 분해 — 이 여기서 닫힌다.
 *
 * <p>감사 컬럼은 Spring Data Auditing이 채우므로(TΔ4) setter가 없다. 왕복을 타임스탬프까지 포함해
 * 닫으려면 조회 시점의 엔티티 상태를 재현해야 해서 리플렉션으로 주입한다 — 검증 대상은 주입 자체가
 * 아니라 "감사 컬럼이 도메인 타임스탬프를 겸한다"(Q-5)는 매핑이다.
 */
class NoteEntityMapperTest {

    private static final Long NOTE_ID = 42L;
    private static final OffsetDateTime CREATED = OffsetDateTime.of(2026, 7, 10, 9, 30, 0, 0, ZoneOffset.ofHours(9));
    private static final OffsetDateTime UPDATED = OffsetDateTime.of(2026, 7, 12, 20, 5, 0, 0, ZoneOffset.ofHours(9));

    /** 3단 중첩 + 배열 6종을 전부 채운 노트 — 왕복이 무엇 하나 떨구지 않는지 보는 기준 표본. */
    private static Note sampleNote() {
        Entry first = new Entry(
                LocalDate.of(2026, 7, 10),
                List.of(
                        new Brew(new Recipe("핸드드립", 15.0, 240.0, 220.0, 150.0, 92.5, "중간 (코만단테)",
                                "V60", "뜸 40ml 30초 → 100ml → 100ml", "다음엔 더 굵게"),
                                new Tasting("새콤하고 좋았음", "It was pleasantly bright", Rating.GOOD)),
                        new Brew(new Recipe(null, 16.0, null, null, null, null, null, null, null, null), null)),
                CREATED);
        Entry second = new Entry(
                LocalDate.of(2026, 7, 12),
                List.of(new Brew(null, new Tasting("오늘은 밍밍했음", "오늘은 밍밍했음", Rating.OKAY_NOT_MINE))),
                UPDATED);
        return new Note(
                "coffeevera-yirgacheffe-g1",
                new Sourced<>("커피베라 예가체프 G1", Source.USER),
                new Sourced<>("커피베라", Source.USER),
                List.of(new Bean(new Sourced<>("에티오피아 예가체프", Source.USER), new Sourced<>("워시드", Source.SEARCH)),
                        new Bean(new Sourced<>("콜롬비아 우일라", Source.SEARCH), null)),
                new Sourced<>(null, Source.SEARCH),                              // 값 없이 source만 마킹된 필드
                new Sourced<>(List.of("자스민", "베르가못"), Source.SEARCH),
                new Aliases(List.of("예가체프 G1", "yirgacheffe"), List.of("Coffee Vera")),
                List.of("https://example.com/coffeevera"),
                List.of(first, second),
                CREATED,
                UPDATED);
    }

    @Test
    @DisplayName("TΔ3c: Note 왕복 동치성 — 도메인 → 엔티티 → 도메인이 원본과 같다")
    void noteRoundTrip() {
        Note original = sampleNote();

        Note restored = roundTrip(original);

        assertThat(restored).isEqualTo(original);
    }

    @Test
    @DisplayName("TΔ3c: 빈 값 노트 왕복 — 배열 전무·부재 필드가 그대로 복원된다")
    void emptyNoteRoundTrip() {
        Note original = new Note(
                "bare",
                new Sourced<>("이름만 있는 커피", Source.USER),
                null,                       // 로스터리 부재 → 정규화 컬럼도 null
                List.of(),
                null,
                null,
                Aliases.empty(),
                List.of(),
                List.of(),
                CREATED,
                UPDATED);

        Note restored = roundTrip(original);

        assertThat(restored).isEqualTo(original);
        assertThat(NoteEntityMapper.toNoteEntity(original).getRoasteryNormalized()).isNull();
    }

    @Test
    @DisplayName("TΔ3c: Aliases kind 분해·재조립 — 목록 두 개 ↔ 한 테이블 + 판별 컬럼")
    void aliasesSplitAndRejoin() {
        Aliases original = new Aliases(List.of("예가체프 G1", "yirgacheffe"), List.of("Coffee Vera"));

        List<NoteAliasEntity> rows = NoteEntityMapper.toAliasEntities(NOTE_ID, original);

        assertThat(rows).extracting(NoteAliasEntity::getKind)
                .containsExactly(AliasKind.COFFEE_NAME, AliasKind.COFFEE_NAME, AliasKind.ROASTERY);
        // V-13: 표시 형태(첫 등장)를 저장하고, 정규화는 대조 기준으로 함께 싣는다.
        assertThat(rows).extracting(NoteAliasEntity::getAlias)
                .containsExactly("예가체프 G1", "yirgacheffe", "Coffee Vera");
        assertThat(rows).extracting(NoteAliasEntity::getNormalized)
                .containsExactly("예가체프g1", "yirgacheffe", "coffeevera");
        assertThat(rows).extracting(NoteAliasEntity::getNoteId).containsOnly(NOTE_ID);

        assertThat(NoteEntityMapper.toAliases(rows)).isEqualTo(original);
    }

    @Test
    @DisplayName("TΔ3c: officialNotes 값/source 분리 (Q-8) — 값은 별도 행, source는 note 컬럼 하나")
    void officialNotesValueAndSourceSplit() {
        Note note = sampleNote();

        NoteEntity noteEntity = NoteEntityMapper.toNoteEntity(note);
        List<NoteOfficialNoteEntity> rows = NoteEntityMapper.toOfficialNoteEntities(NOTE_ID, note.officialNotes());

        // 배열 전체에 source 하나 — 행에는 source가 없다.
        assertThat(noteEntity.getOfficialNotesSource()).isEqualTo(Source.SEARCH);
        assertThat(rows).extracting(NoteOfficialNoteEntity::getValue).containsExactly("자스민", "베르가못");
        assertThat(rows).extracting(NoteOfficialNoteEntity::getSeq).containsExactly(0, 1);

        assertThat(roundTrip(note).officialNotes()).isEqualTo(note.officialNotes());
    }

    @Test
    @DisplayName("TΔ3c: Recipe 수치 Double↔BigDecimal 왕복 — 스키마가 numeric이라 생긴 간극(V-8)")
    void recipeNumericRoundTrip() {
        Recipe original = new Recipe("에스프레소", 18.1, 0.5, 36.5, 27.0, 93.0, null, null, null, null);

        RecipeEntity entity = NoteEntityMapper.toRecipeEntity(7L, original);

        assertThat(entity.getBrewId()).isEqualTo(7L);
        // BigDecimal.valueOf는 Double.toString 기반이라 십진 표기가 그대로 간다. 이진 생성자
        // (new BigDecimal(18.1))였다면 18.100000000000001421...로 numeric에 50여 자리가 저장된다 —
        // 왕복만 보면 doubleValue()가 되돌려 놓아 드러나지 않으므로 표기 자체를 단언한다.
        assertThat(entity.getDoseG().toPlainString()).isEqualTo("18.1");
        assertThat(entity.getYieldMl()).isEqualByComparingTo(new BigDecimal("36.5"));
        assertThat(entity.getWaterMl()).isEqualByComparingTo(new BigDecimal("0.5"));

        assertThat(NoteEntityMapper.toRecipe(entity)).isEqualTo(original);
    }

    @Test
    @DisplayName("TΔ3c: 회차의 recipe·tasting 한쪽만 있는 경우 — 행 미생성으로 표현된다")
    void brewWithOnlyOneSide() {
        Brew recipeOnly = new Brew(new Recipe(null, 15.0, null, null, null, null, null, null, null, null), null);
        Brew tastingOnly = new Brew(null, new Tasting("맛있었음", "맛있었음", Rating.PERFECT));

        assertThat(NoteEntityMapper.toTastingEntity(1L, recipeOnly.tasting())).isNull();
        assertThat(NoteEntityMapper.toRecipeEntity(2L, tastingOnly.recipe())).isNull();

        assertThat(NoteEntityMapper.toBrew(
                NoteEntityMapper.toRecipeEntity(1L, recipeOnly.recipe()),
                NoteEntityMapper.toTastingEntity(1L, recipeOnly.tasting()))).isEqualTo(recipeOnly);
        assertThat(NoteEntityMapper.toBrew(
                NoteEntityMapper.toRecipeEntity(2L, tastingOnly.recipe()),
                NoteEntityMapper.toTastingEntity(2L, tastingOnly.tasting()))).isEqualTo(tastingOnly);
    }

    @Test
    @DisplayName("TΔ3c: 정규화 컬럼은 Aliases.normalize()가 단일 소스 (Q-6)")
    void normalizedColumnsComputedByApplication() {
        Note note = sampleNote();

        NoteEntity entity = NoteEntityMapper.toNoteEntity(note);

        assertThat(entity.getCoffeeNameNormalized()).isEqualTo(Aliases.normalize("커피베라 예가체프 G1"));
        assertThat(entity.getRoasteryNormalized()).isEqualTo(Aliases.normalize("커피베라"));
    }

    @Test
    @DisplayName("TΔ3c: 배열 순서는 seq 컬럼이 소유한다 — 조회 순서가 삽입 순서에 기대지 않게 (AC-Δ4)")
    void arrayOrderCarriedBySeq() {
        Note note = sampleNote();

        assertThat(NoteEntityMapper.toBeanEntities(NOTE_ID, note.beans()))
                .extracting(NoteBeanEntity::getSeq).containsExactly(0, 1);
        assertThat(NoteEntityMapper.toSourceEntities(NOTE_ID, note.sources()))
                .extracting(NoteSourceEntity::getSeq).containsExactly(0);
    }

    @Test
    @DisplayName("TΔ3c: 출처 표시 필드 — value·source 모두 부재면 필드 부재로 수렴한다")
    void sourcedValueBothNullCollapses() {
        // Hibernate가 @Embedded 전 컬럼 null을 null로 읽으므로 Sourced<>(null, null)은 DB 왕복에서 살아남지 못한다.
        assertThat(NoteEntityMapper.toSourced(new SourcedValue(null, null))).isNull();
        assertThat(NoteEntityMapper.toSourced(null)).isNull();
        // 값 없이 source만 있는 필드는 살아 있다(미확정 값 + 출처 마킹).
        assertThat(NoteEntityMapper.toSourced(new SourcedValue(null, Source.SEARCH)))
                .isEqualTo(new Sourced<>(null, Source.SEARCH));
    }

    /**
     * 도메인 → 엔티티 행들 → 도메인. 실 저장소가 하는 일(부모 INSERT로 id를 받아 자식을 만들고, 조회 후
     * 아래에서 위로 조립)을 id·감사값만 대신 채워 재현한다.
     */
    private static Note roundTrip(Note original) {
        NoteEntity noteEntity = NoteEntityMapper.toNoteEntity(original);
        ReflectionTestUtils.setField(noteEntity, "id", NOTE_ID);
        ReflectionTestUtils.setField(noteEntity, "createdAt", original.createdAt());
        ReflectionTestUtils.setField(noteEntity, "modifiedAt", original.updatedAt());

        List<NoteBeanEntity> beans = NoteEntityMapper.toBeanEntities(NOTE_ID, original.beans());
        List<NoteOfficialNoteEntity> officialNotes =
                NoteEntityMapper.toOfficialNoteEntities(NOTE_ID, original.officialNotes());
        List<NoteAliasEntity> aliases = NoteEntityMapper.toAliasEntities(NOTE_ID, original.aliases());
        List<NoteSourceEntity> sources = NoteEntityMapper.toSourceEntities(NOTE_ID, original.sources());

        List<Entry> entries = new ArrayList<>();
        long brewId = 0;
        for (Entry entry : original.entries()) {
            EntryEntity entryEntity = NoteEntityMapper.toEntryEntity(NOTE_ID, entry);
            ReflectionTestUtils.setField(entryEntity, "modifiedAt", entry.updatedAt());

            List<Brew> brews = new ArrayList<>();
            for (Brew brew : entry.brews()) {
                long id = ++brewId;
                brews.add(NoteEntityMapper.toBrew(
                        NoteEntityMapper.toRecipeEntity(id, brew.recipe()),
                        NoteEntityMapper.toTastingEntity(id, brew.tasting())));
            }
            entries.add(NoteEntityMapper.toEntry(entryEntity, brews));
        }

        return NoteEntityMapper.toNote(original.slug(), noteEntity, beans, officialNotes, aliases, sources, entries);
    }
}
