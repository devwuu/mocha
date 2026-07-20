package com.devwuu.mocha.domain;

import com.devwuu.mocha.json.MochaObjectMapper;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T1-1: 도메인 모델 Jackson 직렬화.
 * <p>직렬화 왕복 동일성 + rating enum 역직렬화 위반 거부(V-1).
 */
class DomainSerializationTest {

    private final ObjectMapper mapper = MochaObjectMapper.create();

    private static Note sampleNote() {
        OffsetDateTime ts = OffsetDateTime.of(2026, 7, 10, 9, 30, 0, 0, ZoneOffset.ofHours(9));
        Entry entry = new Entry(
                LocalDate.of(2026, 7, 10),
                List.of(new Brew(
                        new Recipe(15.0, 240.0, "중간"),   // 레시피 포함 — Note 왕복에 회차 recipe도 실린다(FR-18)
                        new Tasting("새콤하고 좋았다", null, Rating.GOOD))),
                ts
        );
        return new Note(
                "coffeevera-yirgacheffe-g1",
                Sourced.user("커피베라 예가체프 G1"),
                Sourced.user("커피베라"),
                List.of(new Bean(Sourced.user("에티오피아 예가체프"), Sourced.search("워시드"))),
                new Sourced<>(null, Source.SEARCH),          // 미확정 값 + source 마킹
                Sourced.search(List.of("자스민", "베르가못")),  // Sourced<List<String>>
                List.of("https://example.com/coffeevera"),
                List.of(entry),
                ts,
                ts
        );
    }

    @Test
    @DisplayName("T1-1: Note 직렬화 왕복 동일성")
    void noteRoundTrip() throws Exception {
        Note original = sampleNote();

        String json = mapper.writeValueAsString(original);
        Note restored = mapper.readValue(json, Note.class);

        assertThat(restored).isEqualTo(original);
    }

    @Test
    @DisplayName("T1-1: PendingNote(draft+match) 직렬화 왕복 동일성")
    void pendingNoteRoundTrip() throws Exception {
        OffsetDateTime ts = OffsetDateTime.of(2026, 7, 10, 9, 30, 0, 0, ZoneOffset.ofHours(9));
        PendingNote pendingNew = new PendingNote(sampleNote(), MatchInfo.newNote(), "1720000000.000100", ts);
        PendingNote pendingExisting = new PendingNote(
                sampleNote(),
                MatchInfo.existing("coffeevera-yirgacheffe-g1", LocalDate.of(2026, 7, 9)),
                "1720000000.000200",
                ts
        );

        for (PendingNote original : List.of(pendingNew, pendingExisting)) {
            String json = mapper.writeValueAsString(original);
            PendingNote restored = mapper.readValue(json, PendingNote.class);
            assertThat(restored).isEqualTo(original);
        }
    }

    @Test
    @DisplayName("T1-1: JSON 필드명은 snake_case + match=new는 slug/date 생략")
    void jsonShape() throws Exception {
        String noteJson = mapper.writeValueAsString(sampleNote());
        assertThat(noteJson)
                .contains("\"coffee_name\"")
                .contains("\"roast_level\"")
                .contains("\"official_notes\"")
                .contains("\"brews\"")              // 회차 배열(changes/0021 ADR-59)
                .contains("\"tasting\"")
                .contains("\"my_taste\"")
                .contains("\"my_taste_original\"")  // 감상 원문 병존(V-11 — tasting 요소 단위)
                .contains("\"created_at\"")
                .contains("\"dose_g\"")     // recipe 필드 snake_case(FR-18)
                .contains("\"water_ml\"");

        String matchJson = mapper.writeValueAsString(MatchInfo.newNote());
        assertThat(matchJson).isEqualTo("{\"type\":\"new\"}");
    }

    @Test
    @DisplayName("V-1: rating 4범주 외 값은 역직렬화 거부")
    void invalidRatingRejected() {
        String json = "\"별로였음\"";

        assertThatThrownBy(() -> mapper.readValue(json, Rating.class))
                .isInstanceOf(JacksonException.class)
                .hasMessageContaining("invalid rating");
    }

    @Test
    @DisplayName("V-1: rating null 허용(미언급) — 회차 tasting 안에서")
    void nullRatingAllowed() throws Exception {
        Entry entry = new Entry(LocalDate.of(2026, 7, 10),
                List.of(new Brew(null, new Tasting("무난", null, null))), null);

        String json = mapper.writeValueAsString(entry);
        Entry restored = mapper.readValue(json, Entry.class);

        assertThat(restored.brews().getFirst().tasting().rating()).isNull();
        assertThat(restored).isEqualTo(entry);
    }

    @Test
    @DisplayName("AC-Δ1(changes/0014): photos 키가 든 JSON도 오류 없이 로드되고 재저장 시 photos 키가 사라진다")
    void legacyPhotosKeyIgnoredOnRoundTrip() throws Exception {
        // 사진 아카이브 전용화(ADR-32) 이전에 저장된 엔트리 JSON — photos 배열을 품고 있다.
        String legacy = "{\"date\":\"2026-07-10\",\"brews\":[{\"recipe\":null,"
                + "\"tasting\":{\"my_taste\":\"새콤\",\"my_taste_original\":\"새콤\",\"rating\":\"맛있다\"}}],"
                + "\"photos\":[\"photos/coffeevera/2026-07-10/a.jpg\"],\"updated_at\":null}";

        // 미지 키(photos)는 조용히 무시된다(findings-TΔ0 §1) — 마이그레이션·mapper 옵션 불필요.
        Entry restored = mapper.readValue(legacy, Entry.class);
        String reserialized = mapper.writeValueAsString(restored);

        assertThat(reserialized).doesNotContain("photos");
        assertThat(restored.brews().getFirst().tasting().myTaste()).isEqualTo("새콤");
        assertThat(restored.brews().getFirst().tasting().rating()).isEqualTo(Rating.GOOD);
    }

    // --- TΔ4(changes/0013) → 0021 TΔ1b: my_taste 정규화 + 원문 병존 — tasting 요소 단위(V-11 개정) ---

    @Test
    @DisplayName("AC-Δ5: my_taste(정규화)·my_taste_original(원문)이 tasting 안에 snake_case로 영속·왕복된다")
    void myTasteOriginalRoundTrip() throws Exception {
        Entry entry = new Entry(LocalDate.of(2026, 7, 10),
                List.of(new Brew(null, new Tasting("새콤하고 좋았음", "새콤하고 좋았다", Rating.GOOD))), null);

        String json = mapper.writeValueAsString(entry);
        Entry restored = mapper.readValue(json, Entry.class);

        assertThat(json).contains("\"my_taste\":\"새콤하고 좋았음\"")
                .contains("\"my_taste_original\":\"새콤하고 좋았다\"");
        assertThat(restored.brews().getFirst().tasting().myTaste()).isEqualTo("새콤하고 좋았음");
        assertThat(restored.brews().getFirst().tasting().myTasteOriginal()).isEqualTo("새콤하고 좋았다");
        assertThat(restored).isEqualTo(entry);
    }

    @Test
    @DisplayName("V-11: my_taste만 있고 원문이 누락되면 정규화본을 원문에도 담아 영속한다(tasting 요소 단위)")
    void persistsCopiedOriginalWhenMissing() throws Exception {
        Tasting tasting = new Tasting("맛있었음", null, Rating.GOOD); // 원문 누락 → V-11 복사가 걸린다

        assertThat(tasting.myTasteOriginal()).isEqualTo("맛있었음"); // 도메인 불변 보장
        Tasting restored = mapper.readValue(mapper.writeValueAsString(tasting), Tasting.class);
        assertThat(restored.myTasteOriginal()).isEqualTo("맛있었음"); // JSON에도 병존
    }

    // --- 0021 TΔ1b: brews 회차 구조 + Recipe 10필드 (ADR-59, V-8·V-15) ---
    // 기존 노트 JSON은 삭제·재등록하므로(ADR-28 관례) 구 엔트리 레벨 필드 로드 호환 테스트는 두지 않는다.

    @Test
    @DisplayName("0021-TΔ1b: brews(회차 배열 — recipe 10필드·tasting)가 snake_case로 직렬화·왕복된다")
    void brewsRoundTrip() throws Exception {
        // ideas/sample.md 패턴: 같은 날 2회 시도 — 회차별 레시피·피드백·감상이 갈린다(배열 순서 = 회차 번호).
        Entry entry = new Entry(
                LocalDate.of(2026, 7, 18),
                List.of(
                        new Brew(
                                new Recipe("에스프레소", 18.0, null, 10.0, 28.0, 93.0,
                                        "210클릭 (매버릭 2.0)", "게이지아 클래식", null,
                                        "퍽은 물퍽, 다음엔 220클릭으로"),
                                new Tasting("새콤하고 좋았음", "새콤하고 좋았다", Rating.GOOD)),
                        new Brew(
                                new Recipe("핸드드립", 15.0, 240.0, null, 160.0, 92.0,
                                        null, null, "뜸 40ml 30초 → 100ml → 100ml", null),
                                null)),                             // 감상 없는 시도(recipe만) 허용(V-15)
                null);

        String json = mapper.writeValueAsString(entry);
        Entry restored = mapper.readValue(json, Entry.class);

        assertThat(json).contains("\"brews\"")
                .contains("\"yield_ml\":10").contains("\"time_sec\":28")   // 신설 수치 필드 snake_case·number
                .contains("\"grind\":\"210클릭 (매버릭 2.0)\"")
                .contains("\"pouring\":\"뜸 40ml 30초 → 100ml → 100ml\"");
        assertThat(restored).isEqualTo(entry);
        assertThat(restored.brews()).hasSize(2);
        assertThat(restored.brews().getFirst().recipe().feedback()).isEqualTo("퍽은 물퍽, 다음엔 220클릭으로");
        assertThat(restored.brews().getLast().tasting()).isNull();
    }

    @Test
    @DisplayName("0021-TΔ1b/V-15: brews 키 부재·null JSON도 빈 배열로 로드된다(null 불가 기본값)")
    void brewsDefaultsToEmptyList() throws Exception {
        String withoutBrews = "{\"date\":\"2026-07-10\",\"updated_at\":null}";
        String nullBrews = "{\"date\":\"2026-07-10\",\"brews\":null,\"updated_at\":null}";

        assertThat(mapper.readValue(withoutBrews, Entry.class).brews()).isEmpty();
        assertThat(mapper.readValue(nullBrews, Entry.class).brews()).isEmpty();
    }

    @Test
    @DisplayName("0021-TΔ1b/V-15: normalize — recipe·tasting 둘 다 null인 회차와 빈 감상 tasting을 드롭한다")
    void brewsNormalizeDropsEmptyElements() {
        List<Brew> normalized = Brew.normalize(Arrays.asList(
                new Brew(new Recipe(15.0, 240.0, "중간"), new Tasting("새콤", null, Rating.GOOD)),
                new Brew(null, null),                                          // 빈 회차 → 드롭
                null,                                                          // null 요소 → 드롭
                new Brew(null, Tasting.normalize("  ", null, Rating.GOOD)),    // 빈 감상 tasting → null → 드롭
                new Brew(new Recipe(0.0, null, "  "), null)));                 // recipe 전무 정규화 → null → 드롭

        assertThat(normalized).containsExactly(
                new Brew(new Recipe(15.0, 240.0, "중간"), new Tasting("새콤", "새콤", Rating.GOOD)));
        assertThat(Brew.normalize(null)).isEmpty(); // null 배열은 빈 배열
    }

    @Test
    @DisplayName("0021-TΔ1b/V-15: tasting.my_taste는 비어 있지 않아야 한다 — 빈 감상은 tasting 자체가 null로 드롭")
    void tastingNormalizeRejectsBlankTaste() {
        assertThat(Tasting.normalize(null, null, Rating.GOOD)).isNull();
        assertThat(Tasting.normalize("   ", "원문", null)).isNull();
        // 감상이 있으면 원문 병존(V-11) — 원문 누락 시 정규화본 복사.
        assertThat(Tasting.normalize("새콤했음", null, Rating.GOOD))
                .isEqualTo(new Tasting("새콤했음", "새콤했음", Rating.GOOD));
    }

    @Test
    @DisplayName("0021-TΔ1b/V-8: recipe 전 필드 전무면 normalize가 null로 정규화한다")
    void recipeNormalizeAllNull() {
        assertThat(Recipe.normalize(null)).isNull();
        assertThat(Recipe.normalize(new Recipe(null, null, null))).isNull();
        assertThat(Recipe.normalize(new Recipe(null, null, "  "))).isNull();  // 공백 grind도 전무로 간주
        assertThat(Recipe.normalize(new Recipe(null, -18.0, null, 0.0, null, null, " ", "", null, null)))
                .isNull(); // 위반 값만으로 채워진 recipe도 전무로 수렴
    }

    @Test
    @DisplayName("0021-TΔ1b/V-8: 위반 값(음수·0·공백)은 해당 항목만 null로 드롭한다 — 10필드 확장형")
    void recipeNormalizeDropsInvalid() {
        // dose 음수·water 0 → 각 null 드롭, grind만 살아 Recipe는 유지(부속 정보라 저장 거부 아님).
        Recipe dropped = Recipe.normalize(new Recipe(-15.0, 0.0, "굵게"));
        assertThat(dropped).isEqualTo(new Recipe(null, null, "굵게"));

        // 신설 수치(yield_ml·time_sec·temp_c)도 양수만 보존, 공백 텍스트(machine)는 드롭.
        Recipe expanded = Recipe.normalize(new Recipe(
                "에스프레소", 18.0, null, -10.0, 28.0, 0.0, "78클릭", "  ", null, "다음엔 78클릭"));
        assertThat(expanded).isEqualTo(new Recipe(
                "에스프레소", 18.0, null, null, 28.0, null, "78클릭", null, null, "다음엔 78클릭"));
    }

    // --- 0012 TΔ1: PendingNote mode·target (FR-21, changes/0012) ---

    @Test
    @DisplayName("0012-TΔ1: edit 모드 PendingNote(mode+target) 직렬화 왕복 동일성")
    void editModePendingNoteRoundTrip() throws Exception {
        OffsetDateTime ts = OffsetDateTime.of(2026, 7, 10, 9, 30, 0, 0, ZoneOffset.ofHours(9));
        PendingNote editPending = new PendingNote(
                PendingNote.Mode.EDIT,
                sampleNote(),
                new PendingNote.EditTarget("coffeevera-yirgacheffe-g1", LocalDate.of(2026, 7, 9)),
                true,   // 날짜 이동 충돌 경고 — 재시작 후에도 [저장]/[취소]까지 유지돼야 한다(V-10, TΔ5)
                null,   // match는 record 모드 한정(data-model §2.3)
                "1720000000.000300",
                ts
        );

        String json = mapper.writeValueAsString(editPending);
        PendingNote restored = mapper.readValue(json, PendingNote.class);

        assertThat(json).contains("\"mode\":\"edit\"").contains("\"date_conflict\":true");
        assertThat(restored).isEqualTo(editPending);
        assertThat(restored.dateConflict()).isTrue();
        assertThat(restored.target()).isEqualTo(
                new PendingNote.EditTarget("coffeevera-yirgacheffe-g1", LocalDate.of(2026, 7, 9)));
    }

    // (0012 이전 pending.json 하위 호환 테스트는 제거 — 기존 데이터는 배포 전 수동 삭제로 결정, delta 비범위.)

    @Test
    @DisplayName("0012-TΔ1: 알 수 없는 mode 값은 역직렬화 거부")
    void invalidPendingModeRejected() {
        assertThatThrownBy(() -> mapper.readValue("\"delete\"", PendingNote.Mode.class))
                .isInstanceOf(JacksonException.class)
                .hasMessageContaining("unknown pending mode");
    }

    // --- TΔ1: coffee_name Sourced 승격 (V-5, changes/0010) ---

    @Test
    @DisplayName("TΔ1/V-5: coffee_name은 {value, source} 객체로 왕복된다(user·photo)")
    void coffeeNameSourcedRoundTrip() throws Exception {
        for (Source src : List.of(Source.USER, Source.PHOTO)) {
            Sourced<String> coffeeName = new Sourced<>("커피베라 예가체프 G1", src);
            String json = mapper.writeValueAsString(coffeeName);
            @SuppressWarnings("unchecked")
            Sourced<String> restored = mapper.readValue(json, Sourced.class);

            assertThat(json)
                    .contains("\"value\":\"커피베라 예가체프 G1\"")
                    .contains("\"source\":\"" + src.json() + "\"");
            assertThat(restored).isEqualTo(coffeeName);
        }
    }

    // --- 0016 TΔ1: Note.aliases 필드 + 정규화 대조 유틸 (V-13, changes/0016, ADR-37) ---
    // 기존 노트 JSON은 삭제·재생성하므로(ADR-28 관례) aliases 부재 로드 호환 테스트는 두지 않는다.

    @Test
    @DisplayName("0016-TΔ1: aliases를 담은 Note는 snake_case로 직렬화·왕복된다")
    void noteWithAliasesRoundTrip() throws Exception {
        Note original = new Note(
                "ethiopia-chelbesa",
                Sourced.user("Ethiopia Chelbesa"),
                Sourced.user("FroB"),
                List.of(new Bean(Sourced.search("에티오피아"), null)),
                new Sourced<>(null, Source.SEARCH),
                Sourced.search(List.of()),
                new Aliases(List.of("에티오피아 첼베사"), List.of("프롭", "프로브")),
                List.of(),
                List.of(),
                OffsetDateTime.of(2026, 7, 13, 21, 0, 23, 0, ZoneOffset.ofHours(9)),
                OffsetDateTime.of(2026, 7, 13, 21, 0, 23, 0, ZoneOffset.ofHours(9))
        );

        String json = mapper.writeValueAsString(original);
        Note restored = mapper.readValue(json, Note.class);

        assertThat(json).contains("\"aliases\"")
                .contains("\"coffee_name\":[\"에티오피아 첼베사\"]")
                .contains("\"roastery\":[\"프롭\",\"프로브\"]");
        assertThat(restored).isEqualTo(original);
    }

    @Test
    @DisplayName("0016-TΔ1/V-13: 정규화(소문자화·공백 제거) 기준 중복 제거 — 표시 형태는 첫 등장 보존, null·공백 드롭")
    void aliasesDedupByNormalizedKey() {
        // "Ethiopia Chelbesa" / "ethiopia chelbesa" / "ETHIOPIACHELBESA" 는 정규화 시 모두 같은 키.
        Aliases aliases = new Aliases(
                Arrays.asList("Ethiopia Chelbesa", "ethiopia chelbesa", " ETHIOPIACHELBESA ", "첼베사"),
                Arrays.asList("FroB", "  ", null, "frob")  // 공백·null·중복(frob) 드롭
        );

        assertThat(aliases.coffeeName()).containsExactly("Ethiopia Chelbesa", "첼베사"); // 첫 등장 보존
        assertThat(aliases.roastery()).containsExactly("FroB");                          // 공백·null·frob 중복 제거
    }

    @Test
    @DisplayName("0016-TΔ1: normalize는 소문자화 + 모든 공백 제거, null·공백은 빈 키")
    void aliasNormalizeContract() {
        assertThat(Aliases.normalize("Ethiopia Chelbesa")).isEqualTo("ethiopiachelbesa");
        assertThat(Aliases.normalize("  프롭  커피 ")).isEqualTo("프롭커피");
        assertThat(Aliases.normalize(null)).isEmpty();
        assertThat(Aliases.normalize("   ")).isEmpty();
    }

    // --- 0021 TΔ1a: beans 원두 구성 배열 (ADR-53, V-14, changes/0021) ---
    // 기존 노트 JSON은 삭제·재등록하므로(ADR-28 관례) 구 origin/process 형식 로드 호환 테스트는 두지 않는다.

    @Test
    @DisplayName("0021-TΔ1a: beans(원두별 description·process, 서브필드 출처)가 snake_case로 직렬화·왕복된다")
    void beansRoundTrip() throws Exception {
        // 블렌드: 원두별 가공방식이 각 요소에 붙는다(ADR-53 동기) — process 없는 원두는 null.
        Note original = new Note(
                "blend-note",
                Sourced.user("시그니처 블렌드"),
                Sourced.user("커피베라"),
                List.of(
                        new Bean(Sourced.user("에티오피아 예가체프"), Sourced.search("워시드")),
                        new Bean(Sourced.search("콜롬비아 후일라"), Sourced.search("내추럴")),
                        new Bean(Sourced.user("브라질"), null)),
                null,
                null,
                List.of(),
                List.of(),
                OffsetDateTime.of(2026, 7, 18, 9, 0, 0, 0, ZoneOffset.ofHours(9)),
                OffsetDateTime.of(2026, 7, 18, 9, 0, 0, 0, ZoneOffset.ofHours(9))
        );

        String json = mapper.writeValueAsString(original);
        Note restored = mapper.readValue(json, Note.class);

        assertThat(json).contains("\"beans\"")
                .contains("\"description\":{\"value\":\"에티오피아 예가체프\",\"source\":\"user\"}")
                .contains("\"process\":{\"value\":\"워시드\",\"source\":\"search\"}")
                .doesNotContain("\"origin\"");   // 구 노트 레벨 필드 폐지(ADR-53)
        assertThat(restored).isEqualTo(original);
        assertThat(restored.beans()).hasSize(3);
    }

    @Test
    @DisplayName("0021-TΔ1a/V-14: beans 키 부재·null JSON도 빈 배열로 로드된다(null 불가 기본값)")
    void beansDefaultsToEmptyList() throws Exception {
        String withoutBeans = "{\"slug\":\"s\",\"coffee_name\":{\"value\":\"커피\",\"source\":\"user\"},"
                + "\"roastery\":null,\"roast_level\":null,\"official_notes\":null,"
                + "\"aliases\":{\"coffee_name\":[],\"roastery\":[]},"
                + "\"sources\":[],\"entries\":[],\"created_at\":null,\"updated_at\":null}";
        String nullBeans = withoutBeans.replace("\"roastery\":null", "\"beans\":null,\"roastery\":null");

        assertThat(mapper.readValue(withoutBeans, Note.class).beans()).isEmpty();
        assertThat(mapper.readValue(nullBeans, Note.class).beans()).isEmpty();
    }

    @Test
    @DisplayName("0021-TΔ1a/V-14: normalize — description이 빈 요소만 드롭하고 나머지 원두는 유지한다(저장 거부 아님)")
    void beansNormalizeDropsEmptyDescription() {
        List<Bean> normalized = Bean.normalize(Arrays.asList(
                new Bean(Sourced.user("  에티오피아 예가체프 "), Sourced.search("  워시드 ")),
                new Bean(new Sourced<>("   ", Source.USER), Sourced.search("내추럴")), // 빈 설명 → 드롭
                new Bean(null, Sourced.search("허니")),                                  // 설명 자체 부재 → 드롭
                null,                                                                    // null 요소 → 드롭
                new Bean(Sourced.user("브라질"), new Sourced<>("  ", Source.SEARCH)))); // 빈 process → null 정규화

        assertThat(normalized).containsExactly(
                new Bean(Sourced.user("에티오피아 예가체프"), Sourced.search("워시드")),
                new Bean(Sourced.user("브라질"), null));
    }

    @Test
    @DisplayName("0021-TΔ1a/V-14: normalize — null 배열은 빈 배열로 정규화된다")
    void beansNormalizeNullToEmpty() {
        assertThat(Bean.normalize(null)).isEmpty();
    }

    @Test
    @DisplayName("0016-TΔ3/AC-Δ4: accumulate — 다른 관측 표기는 더하고, 표시값·중복·빈 표기는 더하지 않는다")
    void aliasesAccumulateObservedNotation() {
        Aliases base = new Aliases(List.of("에티오피아 첼베사"), List.of("프롭"));

        // 표시값과 다른 신규 표기 → 기존 별칭 뒤에 축적(첫 등장 순서 보존).
        Aliases added = base.accumulate("이디오피아 첼베사", "Ethiopia Chelbesa", "프로브", "FroB");
        assertThat(added.coffeeName()).containsExactly("에티오피아 첼베사", "이디오피아 첼베사");
        assertThat(added.roastery()).containsExactly("프롭", "프로브");

        // 표시값과 정규화 일치하는 관측 표기 → 별칭에 넣지 않는다(V-13 표시값 비중복).
        Aliases sameAsDisplay = base.accumulate("ethiopia  chelbesa", "Ethiopia Chelbesa", "FROB", "FroB");
        assertThat(sameAsDisplay.coffeeName()).containsExactly("에티오피아 첼베사");
        assertThat(sameAsDisplay.roastery()).containsExactly("프롭");

        // 기존 별칭과 정규화 중복 → 미추가. null·빈 관측 표기 → 미추가.
        Aliases dupOrBlank = base.accumulate(" 에티오피아첼베사 ", "Ethiopia Chelbesa", null, "FroB");
        assertThat(dupOrBlank.coffeeName()).containsExactly("에티오피아 첼베사");
        assertThat(dupOrBlank.roastery()).containsExactly("프롭");
    }
}
