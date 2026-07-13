package com.devwuu.mocha.render;

import com.devwuu.mocha.config.RenderConfig;
import com.devwuu.mocha.domain.Entry;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.NoteMeta;
import com.devwuu.mocha.domain.Rating;
import com.devwuu.mocha.domain.Recipe;
import com.devwuu.mocha.domain.Sourced;
import com.devwuu.mocha.json.MochaObjectMapper;
import com.devwuu.mocha.repository.JsonFileNoteRepository;
import com.devwuu.mocha.repository.NoteRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TΔ3/TΔ4(changes/0002-instagram-share-card): 렌더 산출이 노트→<b>시음 엔트리</b> 단위로 전환됨을 검증한다.
 * JSON 셋(@TempDir 실 파일 I/O, CLAUDE.md §5.2·5.4) → index 목록 + 엔트리 카드 JPG. 실 래스터화는
 * {@link FakeCardImageRenderer}로 대체해 경로/링크 구조·카드 HTML 계약을 결정론적으로 본다(실 Chromium은 태그 분리).
 * <ul>
 *   <li>AC-Δ5: {@code notes/<slug>.html} 부재, index가 엔트리별 {@code cards/<slug>/<date>.jpg}로 링크</li>
 *   <li>AC-Δ4: 같은 커피 다일자 → 각각의 카드, 카드 HTML은 대상 엔트리 1건만</li>
 *   <li>AC-Δ7: artifact/ 삭제 후 재렌더 동일 산출(재현성)</li>
 *   <li>템플릿: 4:5(1080×1350)·로컬 폰트 참조·단일 엔트리·print/A시리즈 부재·FR-7 2영역</li>
 * </ul>
 */
class ThymeleafNoteRendererTest {

    private final SpringTemplateEngine engine = RenderConfig.offlineTemplateEngine();

    // 두 노트(각 엔트리 1건). note1=검색 보강/GOOD(2026-07-10), note2=PERFECT(2026-07-04). 엔트리 최신순은 note1→note2.
    private NoteRepository seedRepository(Path dataDir) {
        NoteRepository repo = new JsonFileNoteRepository(dataDir, MochaObjectMapper.create());
        OffsetDateTime now = OffsetDateTime.parse("2026-07-10T09:00:00+09:00");
        NoteMeta meta1 = new NoteMeta(
                Sourced.user("예가체프 G1 워시드"),
                Sourced.user("커피베라"),
                Sourced.search("에티오피아 예가체프"),   // origin = 검색 → (검색) 표기 대상(AC-2)
                Sourced.user("워시드"),
                Sourced.search("라이트"),
                Sourced.search(List.of("자몽", "베르가못", "홍차")),
                List.of("https://coffeevera.example/yirgacheffe"));
        repo.upsertEntry("2026-07-10", meta1,
                new Entry(LocalDate.parse("2026-07-10"), "새콤하고 좋았다.\n다음엔 물 온도를 낮춰봐야지.", Rating.GOOD, null, List.of(), now));

        NoteMeta meta2 = new NoteMeta(
                Sourced.user("콜롬비아 게이샤 워시드"),
                Sourced.user("프릳츠"),
                Sourced.search("콜롬비아"),
                null, null, Sourced.search(List.of()), List.of());
        repo.upsertEntry("2026-07-04", meta2,
                new Entry(LocalDate.parse("2026-07-04"), "화사하다.", Rating.PERFECT, null, List.of(), now));
        return repo;
    }

    @Test
    @DisplayName("AC-Δ5: index가 엔트리별 cards/<slug>/<date>.jpg로 링크하고 notes/<slug>.html은 남지 않는다")
    void indexLinksEntryCardsAndDropsNoteHtml(@TempDir Path dataDir, @TempDir Path artifactDir) {
        NoteRepository repo = seedRepository(dataDir);
        FakeCardImageRenderer cards = new FakeCardImageRenderer();
        new ThymeleafNoteRenderer(repo, engine, artifactDir, Theme.TYPE_B, cards).renderAll();

        // AC-Δ5: 노트 상세 HTML은 파일로 남지 않는다(중간 입력).
        assertFalse(Files.exists(artifactDir.resolve("notes")), "notes/ 디렉터리 미생성");

        // index.html 존재 + 엔트리별 카드 JPG 링크(상대 경로).
        Path index = artifactDir.resolve("index.html");
        assertTrue(Files.isRegularFile(index), "index.html 생성");
        String indexHtml = read(index);
        assertTrue(indexHtml.contains("href=\"cards/2026-07-10/2026-07-10.jpg\""), "엔트리1 카드 링크");
        assertTrue(indexHtml.contains("href=\"cards/2026-07-04/2026-07-04.jpg\""), "엔트리2 카드 링크");
        assertFalse(indexHtml.contains("notes/"), "노트 HTML 링크 없음");
        assertFalse(indexHtml.contains("file:"), "file:// 없음");
        assertFalse(indexHtml.contains(artifactDir.toString()), "절대 경로 유출 없음");
        assertTrue(indexHtml.contains("원두 2개 · 기록 2번"), "헤더 집계 유지");

        // 엔트리마다 카드 JPG가 굽혔다(fake 스텁 → 존재). 카드 굽기 호출도 엔트리 수만큼.
        assertTrue(Files.isRegularFile(artifactDir.resolve("cards/2026-07-10/2026-07-10.jpg")), "엔트리1 카드 JPG");
        assertTrue(Files.isRegularFile(artifactDir.resolve("cards/2026-07-04/2026-07-04.jpg")), "엔트리2 카드 JPG");
        assertEquals(2, cards.calls.size(), "엔트리 2건 → 카드 굽기 2회");
        // 카드는 artifact 루트를 base로 굽는다(상대 자원 해석 기준, AC-Δ5).
        assertTrue(cards.calls.stream().allMatch(c -> c.baseDir().equals(artifactDir)), "baseDir = artifact 루트");
    }

    @Test
    @DisplayName("TΔ3: 카드 HTML이 4:5·로컬 폰트·단일 엔트리·FR-7 2영역이고 print/A시리즈가 없다")
    void cardHtmlIsFourFiveSingleEntryWithLocalFont(@TempDir Path dataDir, @TempDir Path artifactDir) {
        NoteRepository repo = seedRepository(dataDir);
        FakeCardImageRenderer cards = new FakeCardImageRenderer();
        new ThymeleafNoteRenderer(repo, engine, artifactDir, Theme.TYPE_B, cards).renderAll();

        String cardHtml = capturedHtml(cards, "cards/2026-07-10/2026-07-10.jpg");

        // 4:5 = 1080×1350 뷰포트.
        assertTrue(cardHtml.contains("1080px") && cardHtml.contains("1350px"), "4:5(1080×1350) 규칙");
        // 로컬 폰트 참조(ADR-11) — CDN 미의존.
        assertTrue(cardHtml.contains("fonts/GowunDodum-Regular.ttf"), "로컬 폰트 @font-face 참조");
        assertTrue(cardHtml.contains("@font-face"), "@font-face 선언");
        assertFalse(cardHtml.contains("fonts.googleapis.com"), "CDN 폰트 링크 없음");
        // FR-7 2영역 유지(카드 안에서).
        assertTrue(cardHtml.contains("로스터리가 말하길"), "official_notes 영역");
        assertTrue(cardHtml.contains("내가 느끼길"), "my_taste 영역");
        assertTrue(cardHtml.contains("새콤하고 좋았다"), "엔트리 감상 표시");
        // print/A시리즈 잔재 부재(회귀 가드 — 재유입 방지).
        assertFalse(cardHtml.contains("@page"), "@page 없음");
        assertFalse(cardHtml.contains("@media print"), "@media print 없음");
        assertFalse(cardHtml.contains("A4") || cardHtml.contains("A5"), "A4/A5 인쇄 잔재 없음");
        // 카드도 상대 경로만(파일 없음/절대 경로 없음).
        assertFalse(cardHtml.contains("file:"), "file:// 없음");
        assertFalse(cardHtml.contains(artifactDir.toString()), "절대 경로 유출 없음");
    }

    @Test
    @DisplayName("AC-Δ5(changes/0013): 카드는 my_taste(정규화)만 렌더하고 my_taste_original(원문)은 노출하지 않는다")
    void cardRendersNormalizedTasteAndHidesOriginal(@TempDir Path dataDir, @TempDir Path artifactDir) {
        NoteRepository repo = new JsonFileNoteRepository(dataDir, MochaObjectMapper.create());
        OffsetDateTime now = OffsetDateTime.parse("2026-07-10T09:00:00+09:00");
        NoteMeta meta = new NoteMeta(
                Sourced.user("예가체프 G1"),
                Sourced.user("커피베라"), Sourced.search("에티오피아"),
                null, null, Sourced.search(List.of()), List.of());
        // 정규화본(음슴체)과 발화 원문을 서로 다른 문자열로 둔다 — 원문 유출을 판별 가능하게.
        repo.upsertEntry("2026-07-10", meta,
                new Entry(LocalDate.parse("2026-07-10"), "새콤하고 좋았음", "새콤하고 좋았다구우",
                        Rating.GOOD, null, List.of(), now));

        FakeCardImageRenderer cards = new FakeCardImageRenderer();
        new ThymeleafNoteRenderer(repo, engine, artifactDir, Theme.TYPE_B, cards).renderAll();

        String cardHtml = capturedHtml(cards, "cards/2026-07-10/2026-07-10.jpg");
        assertTrue(cardHtml.contains("새콤하고 좋았음"), "정규화본(my_taste) 렌더");
        assertFalse(cardHtml.contains("새콤하고 좋았다구우"), "원문(my_taste_original)은 카드에 노출 안 됨(V-11)");
    }

    @Test
    @DisplayName("AC-Δ4: 같은 커피를 다른 날 기록하면 엔트리마다 별도 카드가 생기고, 카드 HTML은 대상 엔트리 1건만 담는다")
    void sameCoffeeDifferentDatesYieldSeparateSingleEntryCards(@TempDir Path dataDir, @TempDir Path artifactDir) {
        NoteRepository repo = new JsonFileNoteRepository(dataDir, MochaObjectMapper.create());
        OffsetDateTime now = OffsetDateTime.parse("2026-07-10T09:00:00+09:00");
        NoteMeta meta = new NoteMeta(
                Sourced.user("예가체프 G1 워시드"),
                Sourced.user("커피베라"), Sourced.search("에티오피아 예가체프"),
                Sourced.user("워시드"), Sourced.search("라이트"),
                Sourced.search(List.of("자몽", "홍차")),
                List.of("https://coffeevera.example/yirgacheffe"));
        // 같은 노트(slug)에 다른 날짜 엔트리 2건.
        repo.upsertEntry("yirgacheffe", meta,
                new Entry(LocalDate.parse("2026-07-04"), "첫날: 새콤하고 좋았다.", Rating.GOOD, null, List.of(), now));
        repo.upsertEntry("yirgacheffe", meta,
                new Entry(LocalDate.parse("2026-07-10"), "둘째 날: 물 온도를 낮추니 부드럽다.", Rating.PERFECT, null, List.of(), now));

        FakeCardImageRenderer cards = new FakeCardImageRenderer();
        new ThymeleafNoteRenderer(repo, engine, artifactDir, Theme.TYPE_B, cards).renderAll();

        // 엔트리마다 별도 카드(같은 slug, 다른 date).
        assertTrue(Files.isRegularFile(artifactDir.resolve("cards/yirgacheffe/2026-07-04.jpg")), "첫날 카드");
        assertTrue(Files.isRegularFile(artifactDir.resolve("cards/yirgacheffe/2026-07-10.jpg")), "둘째 날 카드");

        // 각 카드 HTML은 자기 엔트리 감상만 담는다 — 노트 전체 타임라인이 아니다(AC-Δ4).
        String firstCard = capturedHtml(cards, "cards/yirgacheffe/2026-07-04.jpg");
        String secondCard = capturedHtml(cards, "cards/yirgacheffe/2026-07-10.jpg");
        assertTrue(firstCard.contains("첫날: 새콤하고 좋았다."), "첫날 카드에 첫날 감상");
        assertFalse(firstCard.contains("둘째 날"), "첫날 카드에 둘째 날 감상 없음(단일 엔트리)");
        assertTrue(secondCard.contains("둘째 날: 물 온도를 낮추니 부드럽다."), "둘째 날 카드에 둘째 날 감상");
        assertFalse(secondCard.contains("첫날"), "둘째 날 카드에 첫날 감상 없음(단일 엔트리)");

        // index: 노트 1개지만 엔트리 2행, 최신순(07-10 먼저).
        String indexHtml = read(artifactDir.resolve("index.html"));
        assertTrue(indexHtml.contains("원두 1개 · 기록 2번"), "원두 1·기록 2 집계");
        assertTrue(indexHtml.indexOf("cards/yirgacheffe/2026-07-10.jpg")
                < indexHtml.indexOf("cards/yirgacheffe/2026-07-04.jpg"), "엔트리 최신순(07-10 먼저)");
    }

    @Test
    @DisplayName("AC-Δ5: 엔트리 사진은 카드 HTML에 artifact 루트 상대 썸네일(thumbs/…)로 보존된다")
    void cardKeepsEntryPhotosAsRelativeThumbs(@TempDir Path dataDir, @TempDir Path artifactDir) {
        NoteRepository repo = new JsonFileNoteRepository(dataDir, MochaObjectMapper.create());
        OffsetDateTime now = OffsetDateTime.parse("2026-07-10T09:00:00+09:00");
        NoteMeta meta = new NoteMeta(
                Sourced.user("예가체프 G1 워시드"),
                Sourced.user("커피베라"), Sourced.search("에티오피아"),
                null, null, Sourced.search(List.of()), List.of());
        repo.upsertEntry("2026-07-10", meta,
                new Entry(LocalDate.parse("2026-07-10"), "새콤하다.", Rating.GOOD, null,
                        List.of("photos/2026-07-10/2026-07-10/a.jpg"), now));

        FakeCardImageRenderer cards = new FakeCardImageRenderer();
        new ThymeleafNoteRenderer(repo, engine, artifactDir, Theme.TYPE_B, cards).renderAll();

        String cardHtml = capturedHtml(cards, "cards/2026-07-10/2026-07-10.jpg");
        // 카드는 artifact 루트 base → 썸네일은 ../ 없이 thumbs/… (AC-Δ5).
        assertTrue(cardHtml.contains("thumbs/2026-07-10/2026-07-10/a.jpg"), "엔트리 사진 썸네일 상대 경로");
        assertFalse(cardHtml.contains("../thumbs/"), "notes/ 하위 잔재(../) 없음");
        // index 행 대표 썸네일도 artifact 루트 상대.
        String indexHtml = read(artifactDir.resolve("index.html"));
        assertTrue(indexHtml.contains("thumbs/2026-07-10/2026-07-10/a.jpg"), "index 대표 썸네일");
    }

    @Test
    @DisplayName("AC-Δ7: artifact/ 삭제 후 재렌더하면 index·카드 산출이 동일하게 복원된다")
    void reRenderIsReproducible(@TempDir Path dataDir, @TempDir Path artifactDir) {
        NoteRepository repo = seedRepository(dataDir);

        FakeCardImageRenderer cards1 = new FakeCardImageRenderer();
        ThymeleafNoteRenderer renderer1 = new ThymeleafNoteRenderer(repo, engine, artifactDir, Theme.TYPE_A, cards1);
        renderer1.renderAll();
        String indexFirst = read(artifactDir.resolve("index.html"));
        String cardFirst = capturedHtml(cards1, "cards/2026-07-10/2026-07-10.jpg");

        deleteRecursively(artifactDir);
        assertFalse(Files.exists(artifactDir.resolve("index.html")), "artifact/ 비워짐");

        FakeCardImageRenderer cards2 = new FakeCardImageRenderer();
        new ThymeleafNoteRenderer(repo, engine, artifactDir, Theme.TYPE_A, cards2).renderAll();
        assertEquals(indexFirst, read(artifactDir.resolve("index.html")), "인덱스 재현성");
        assertEquals(cardFirst, capturedHtml(cards2, "cards/2026-07-10/2026-07-10.jpg"), "카드 HTML 재현성");
        assertEquals(cards1.calls.size(), cards2.calls.size(), "카드 굽기 횟수 재현성");
    }

    @Test
    @DisplayName("AC-Δ7(증분): renderEntryCard는 대상 엔트리 1장만 새로 굽고 경로를 반환하며 index를 갱신한다")
    void renderEntryCardBakesSingleCardAndReturnsPath(@TempDir Path dataDir, @TempDir Path artifactDir) {
        NoteRepository repo = seedRepository(dataDir);
        FakeCardImageRenderer cards = new FakeCardImageRenderer();
        ThymeleafNoteRenderer renderer = new ThymeleafNoteRenderer(repo, engine, artifactDir, Theme.TYPE_B, cards);

        Path path = renderer.renderEntryCard("2026-07-10", LocalDate.parse("2026-07-10"));

        assertEquals(artifactDir.resolve("cards/2026-07-10/2026-07-10.jpg"), path, "반환 경로 = 카드 JPG");
        assertTrue(Files.isRegularFile(path), "카드 JPG 존재");
        assertEquals(1, cards.calls.size(), "증분: 대상 엔트리 1장만 굽는다(전체 재래스터화 없음)");
        // index는 전체 엔트리 최신순으로 갱신된다(방금 것 포함).
        String indexHtml = read(artifactDir.resolve("index.html"));
        assertTrue(indexHtml.contains("href=\"cards/2026-07-10/2026-07-10.jpg\""), "index에 방금 카드 링크");
        assertTrue(indexHtml.contains("href=\"cards/2026-07-04/2026-07-04.jpg\""), "index에 다른 엔트리도 유지");
    }

    @Test
    @DisplayName("TΔ3: mocha.artifact.theme(type-a/type-b)로 카드 디자인이 갈린다")
    void themeSelectsCardDesign(@TempDir Path dataDir, @TempDir Path artifactA, @TempDir Path artifactB) {
        NoteRepository repo = seedRepository(dataDir);
        FakeCardImageRenderer cardsA = new FakeCardImageRenderer();
        FakeCardImageRenderer cardsB = new FakeCardImageRenderer();
        new ThymeleafNoteRenderer(repo, engine, artifactA, Theme.TYPE_A, cardsA).renderAll();
        new ThymeleafNoteRenderer(repo, engine, artifactB, Theme.TYPE_B, cardsB).renderAll();

        String serifCard = capturedHtml(cardsA, "cards/2026-07-10/2026-07-10.jpg");
        String cuteCard = capturedHtml(cardsB, "cards/2026-07-10/2026-07-10.jpg");
        assertTrue(serifCard.contains("Gowun Batang") && serifCard.contains("COFFEE NOTE"), "type-a=세리프");
        assertTrue(cuteCard.contains("Gowun Dodum") && cuteCard.contains("🐾 내가 느끼길"), "type-b=귀여운");
        // 공통: 두 테마 모두 4:5 + FR-7 2영역.
        assertTrue(serifCard.contains("1080px") && cuteCard.contains("1080px"), "두 테마 4:5");
        assertTrue(serifCard.contains("로스터리가 말하길") && serifCard.contains("내가 느끼길"), "세리프 2영역");
        assertTrue(cuteCard.contains("로스터리가 말하길") && cuteCard.contains("내가 느끼길"), "귀여운 2영역");
    }

    // --- TΔ6: 레시피 영역(있는 항목만·전무 시 미출력) + coffeeName Sourced 승격(제목 무표기) ---

    // recipe·coffeeName만 갈아끼우는 단일 엔트리 노트 seed. 나머지 메타는 고정.
    private NoteRepository seedWithRecipe(Path dataDir, Sourced<String> coffeeName, Recipe recipe) {
        NoteRepository repo = new JsonFileNoteRepository(dataDir, MochaObjectMapper.create());
        OffsetDateTime now = OffsetDateTime.parse("2026-07-10T09:00:00+09:00");
        NoteMeta meta = new NoteMeta(
                coffeeName,
                Sourced.user("커피베라"), Sourced.search("에티오피아 예가체프"),
                Sourced.user("워시드"), Sourced.search("라이트"),
                Sourced.search(List.of("자몽", "홍차")), List.of());
        repo.upsertEntry("2026-07-10", meta,
                new Entry(LocalDate.parse("2026-07-10"), "새콤하다.", Rating.GOOD, recipe, List.of(), now));
        return repo;
    }

    private String bakeCardHtml(NoteRepository repo, Theme theme, Path artifactDir) {
        FakeCardImageRenderer cards = new FakeCardImageRenderer();
        new ThymeleafNoteRenderer(repo, engine, artifactDir, theme, cards).renderAll();
        return capturedHtml(cards, "cards/2026-07-10/2026-07-10.jpg");
    }

    @Test
    @DisplayName("AC-Δ1/TΔ6: recipe 3항목이 있으면 두 테마 카드 모두 '이렇게 내렸어요'에 원두·물·분쇄도를 표시한다")
    void recipeSectionRendersWhenPresent(@TempDir Path dataDir, @TempDir Path artA, @TempDir Path artB) {
        Recipe recipe = new Recipe(15.0, 240.0, "중간");
        for (Theme theme : new Theme[]{Theme.TYPE_A, Theme.TYPE_B}) {
            NoteRepository repo = seedWithRecipe(dataDir, Sourced.user("예가체프 G1 워시드"), recipe);
            String card = bakeCardHtml(repo, theme, theme == Theme.TYPE_A ? artA : artB);
            // "이렇게 내렸어요" 라벨은 type-b만(시안: type-a는 라벨 없이 인라인 — findings ④). 값은 두 테마 공통.
            if (theme == Theme.TYPE_B) {
                assertTrue(card.contains("이렇게 내렸어요"), theme + ": 레시피 라벨");
            }
            assertTrue(card.contains("15g"), theme + ": 원두 15g(소수점 없이)");
            assertTrue(card.contains("240ml"), theme + ": 물 240ml");
            assertTrue(card.contains("중간"), theme + ": 분쇄도 중간");
            // AC-10 자동 가드: 레시피 영역이 늘어도 4:5 프레임·overflow 하드클램프는 유지된다(픽셀 무잘림은 수동 체크).
            assertTrue(card.contains("1080px") && card.contains("1350px"), theme + ": 레시피 추가 후에도 4:5 유지");
            assertTrue(card.contains("overflow:hidden"), theme + ": overflow 하드클램프 유지");
        }
    }

    @Test
    @DisplayName("AC-Δ2/TΔ6: recipe가 null이면 두 테마 카드 모두 '이렇게 내렸어요' 영역이 나오지 않는다")
    void recipeSectionOmittedWhenAbsent(@TempDir Path dataDir, @TempDir Path artA, @TempDir Path artB) {
        for (Theme theme : new Theme[]{Theme.TYPE_A, Theme.TYPE_B}) {
            NoteRepository repo = seedWithRecipe(dataDir, Sourced.user("예가체프 G1 워시드"), null);
            String card = bakeCardHtml(repo, theme, theme == Theme.TYPE_A ? artA : artB);
            // type-b 라벨 부재 + 두 테마 공통으로 레시피 항목("원두") 부재 = 영역 자체가 없음(AC-Δ2).
            assertFalse(card.contains("이렇게 내렸어요"), theme + ": 레시피 전무 시 라벨 미출력");
            assertFalse(card.contains("원두"), theme + ": 레시피 전무 시 항목 미출력");
        }
    }

    @Test
    @DisplayName("AC-Δ2/TΔ6: recipe가 일부만 있으면(원두만) 있는 항목만 표시하고 없는 항목은 나오지 않는다")
    void recipePartialRendersOnlyPresentItems(@TempDir Path dataDir, @TempDir Path artA, @TempDir Path artB) {
        Recipe partial = Recipe.normalize(15.0, null, null); // 원두만
        for (Theme theme : new Theme[]{Theme.TYPE_A, Theme.TYPE_B}) {
            NoteRepository repo = seedWithRecipe(dataDir, Sourced.user("예가체프 G1 워시드"), partial);
            String card = bakeCardHtml(repo, theme, theme == Theme.TYPE_A ? artA : artB);
            if (theme == Theme.TYPE_B) {
                assertTrue(card.contains("이렇게 내렸어요"), theme + ": 부분 레시피도 영역(라벨) 표시");
            }
            assertTrue(card.contains("15g"), theme + ": 원두 표시");
            assertFalse(card.contains("240ml"), theme + ": 물 항목 미출력");
            assertFalse(card.contains("분쇄도"), theme + ": 분쇄도 항목 미출력");
        }
    }

    @Test
    @DisplayName("AC-Δ4/TΔ6: coffeeName source가 photo여도 카드 제목은 값만 쓰고 (사진) 표기를 달지 않는다")
    void cardTitleHasNoPhotoTag(@TempDir Path dataDir, @TempDir Path artA, @TempDir Path artB) {
        for (Theme theme : new Theme[]{Theme.TYPE_A, Theme.TYPE_B}) {
            NoteRepository repo = seedWithRecipe(dataDir, Sourced.photo("게이샤 내추럴"), null);
            String card = bakeCardHtml(repo, theme, theme == Theme.TYPE_A ? artA : artB);
            assertTrue(card.contains("게이샤 내추럴"), theme + ": 제목에 커피명 값");
            assertFalse(card.contains("(사진)"), theme + ": 제목=정체성 → (사진) 무표기");
        }
    }

    // --- TΔ3(changes/0012): removeEntryCard + renderAll 고아 카드 정리(AC-Δ3·Δ7) ---

    // 원본 노트의 단일 엔트리를 newDate로 옮긴 edit draft(엔트리 1건) — applyEdit 입력.
    private static Note moveDateDraft(Note origin, LocalDate newDate) {
        Entry e = origin.entries().get(0);
        Entry moved = new Entry(newDate, e.myTaste(), e.rating(), e.recipe(), e.photos(), e.updatedAt());
        return new Note(origin.slug(), origin.coffeeName(), origin.roastery(), origin.origin(),
                origin.process(), origin.roastLevel(), origin.officialNotes(), origin.sources(),
                List.of(moved), origin.createdAt(), origin.updatedAt());
    }

    @Test
    @DisplayName("AC-Δ3: 날짜 이동 커밋 후 옛 카드 삭제→새 카드 증분 렌더 → 옛 카드 부재·새 카드 존재·index 링크 갱신")
    void dateMoveCleansOldCardAndRefreshesIndex(@TempDir Path dataDir, @TempDir Path artifactDir) {
        NoteRepository repo = seedRepository(dataDir);
        ThymeleafNoteRenderer renderer =
                new ThymeleafNoteRenderer(repo, engine, artifactDir, Theme.TYPE_B, new FakeCardImageRenderer());
        renderer.renderAll();
        assertTrue(Files.isRegularFile(artifactDir.resolve("cards/2026-07-10/2026-07-10.jpg")), "이동 전 카드 존재");

        // edit 커밋(07-10 → 07-11) 후 파생물 정리 순서: 옛 카드 삭제 → 새 카드 증분 렌더(index 갱신 흡수).
        Note origin = repo.findBySlug("2026-07-10").orElseThrow();
        repo.applyEdit("2026-07-10", LocalDate.parse("2026-07-10"), moveDateDraft(origin, LocalDate.parse("2026-07-11")));
        renderer.removeEntryCard("2026-07-10", LocalDate.parse("2026-07-10"));
        renderer.renderEntryCard("2026-07-10", LocalDate.parse("2026-07-11"));

        assertFalse(Files.exists(artifactDir.resolve("cards/2026-07-10/2026-07-10.jpg")), "옛 date 카드 부재");
        assertTrue(Files.isRegularFile(artifactDir.resolve("cards/2026-07-10/2026-07-11.jpg")), "새 date 카드 존재");
        String indexHtml = read(artifactDir.resolve("index.html"));
        assertTrue(indexHtml.contains("href=\"cards/2026-07-10/2026-07-11.jpg\""), "index가 새 카드로 링크");
        assertFalse(indexHtml.contains("cards/2026-07-10/2026-07-10.jpg"), "index에서 옛 카드 링크 제거");
        assertTrue(indexHtml.contains("href=\"cards/2026-07-04/2026-07-04.jpg\""), "다른 엔트리 링크는 유지");
    }

    @Test
    @DisplayName("removeEntryCard: 대상 카드 파일이 없어도 예외 없이 지나간다(멱등)")
    void removeEntryCardIsHarmlessWhenAbsent(@TempDir Path dataDir, @TempDir Path artifactDir) {
        ThymeleafNoteRenderer renderer = new ThymeleafNoteRenderer(
                seedRepository(dataDir), engine, artifactDir, Theme.TYPE_B, new FakeCardImageRenderer());
        // 산출 전(cards/ 자체가 없음)에도, 같은 호출을 반복해도 무해해야 한다.
        renderer.removeEntryCard("2026-07-10", LocalDate.parse("2026-07-10"));
        renderer.removeEntryCard("2026-07-10", LocalDate.parse("2026-07-10"));
        assertFalse(Files.exists(artifactDir.resolve("cards/2026-07-10/2026-07-10.jpg")));
    }

    @Test
    @DisplayName("AC-Δ7: 옛 카드 삭제 실패로 남은 고아 카드는 renderAll이 정리해 산출이 신규 렌더와 동일해진다")
    void renderAllPrunesOrphanCardsForReproducibility(@TempDir Path dataDir, @TempDir Path artifactDir, @TempDir Path freshDir) {
        NoteRepository repo = seedRepository(dataDir);
        ThymeleafNoteRenderer renderer =
                new ThymeleafNoteRenderer(repo, engine, artifactDir, Theme.TYPE_B, new FakeCardImageRenderer());
        renderer.renderAll();

        // 날짜 이동 커밋 후 removeEntryCard가 실패했다고 치자 — 옛 카드가 고아로 남는다(plan §7 실패 모드).
        Note origin = repo.findBySlug("2026-07-10").orElseThrow();
        repo.applyEdit("2026-07-10", LocalDate.parse("2026-07-10"), moveDateDraft(origin, LocalDate.parse("2026-07-11")));
        renderer.renderEntryCard("2026-07-10", LocalDate.parse("2026-07-11"));
        assertTrue(Files.isRegularFile(artifactDir.resolve("cards/2026-07-10/2026-07-10.jpg")), "고아 카드가 남아 있다");

        renderer.renderAll(); // --rerender

        assertFalse(Files.exists(artifactDir.resolve("cards/2026-07-10/2026-07-10.jpg")), "renderAll이 고아 카드를 정리(plan §7)");
        // 재현 동일성(AC-Δ7): 같은 JSON을 빈 디렉토리에 새로 렌더한 산출과 카드 집합·index가 동일하다.
        new ThymeleafNoteRenderer(repo, engine, freshDir, Theme.TYPE_B, new FakeCardImageRenderer()).renderAll();
        assertEquals(cardFiles(freshDir), cardFiles(artifactDir), "카드 파일 집합 동일");
        assertEquals(read(freshDir.resolve("index.html")), read(artifactDir.resolve("index.html")), "index 동일");
    }

    // artifact/cards 하위 실제 카드 파일들의 상대 경로 집합.
    private static java.util.Set<String> cardFiles(Path artifactDir) {
        Path cardsDir = artifactDir.resolve("cards");
        try (Stream<Path> walk = Files.walk(cardsDir)) {
            return walk.filter(Files::isRegularFile)
                    .map(p -> cardsDir.relativize(p).toString().replace(java.io.File.separatorChar, '/'))
                    .collect(java.util.stream.Collectors.toSet());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    // fake가 캡처한 호출 중 out이 주어진 상대 경로로 끝나는 카드의 HTML을 돌려준다.
    private String capturedHtml(FakeCardImageRenderer cards, String relOut) {
        String needle = relOut.replace('/', java.io.File.separatorChar);
        return cards.calls.stream()
                .filter(c -> c.out().toString().endsWith(needle) || c.out().toString().endsWith(relOut))
                .map(FakeCardImageRenderer.Call::html)
                .findFirst()
                .orElseThrow(() -> new AssertionError("카드 굽기 호출 없음: " + relOut));
    }

    private static String read(Path p) {
        try {
            return Files.readString(p);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void deleteRecursively(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
