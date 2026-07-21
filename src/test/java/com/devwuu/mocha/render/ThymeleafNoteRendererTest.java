package com.devwuu.mocha.render;

import com.devwuu.mocha.config.RenderConfig;
import com.devwuu.mocha.domain.Aliases;
import com.devwuu.mocha.domain.Bean;
import com.devwuu.mocha.domain.Brew;
import com.devwuu.mocha.domain.Entry;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.NoteMeta;
import com.devwuu.mocha.domain.Rating;
import com.devwuu.mocha.domain.Recipe;
import com.devwuu.mocha.domain.Sourced;
import com.devwuu.mocha.domain.Tasting;
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
 * TΔ5a(changes/0021): 렌더 파이프라인 회차화 — 산출 단위가 <b>회차 파트</b>(감상/레시피 카드,
 * {@code cards/<slug>/<date>-taste-<n>.jpg}·{@code <date>-recipe-<n>.jpg})로 전환됨을 검증한다.
 * JSON 셋(@TempDir 실 파일 I/O, CLAUDE.md §5.2·5.4) → 회차 카드 JPG. 실 래스터화는
 * {@link FakeCardImageRenderer}로 대체해 경로/파일명 규칙·카드 HTML 계약을 결정론적으로 본다(실 Chromium은 태그 분리).
 * <ul>
 *   <li>AC-Δ6(렌더 파트): 회차 2개 엔트리 → 카드 4장, 파일명 규칙(n = 회차)</li>
 *   <li>AC-78: 레시피/감상 없는 회차는 해당 카드 미생성</li>
 *   <li>회차 감소 재저장 → 옛 번호 카드 잔존 없음, AC-39: 날짜 이동 시 옛 날짜 카드 전부 삭제</li>
 *   <li>AC-Δ7: artifact/ 삭제 후 재렌더 동일 산출(재현성), 고아 카드 정리</li>
 *   <li>AC-67(TΔ6, ADR-55): 저장·--rerender 어느 경로로도 index.html 미생성 — artifact/ 아래 HTML 산출 0건</li>
 *   <li>템플릿 계약: 4:5(1080×1350)·로컬 폰트·회차 파트 1건·note.html 폐기(ADR-54)</li>
 * </ul>
 */
class ThymeleafNoteRendererTest {

    private final SpringTemplateEngine engine = RenderConfig.offlineTemplateEngine();

    // 회차 구조(changes/0021 ADR-59) 픽스처 — 단일 감상(+레시피)을 회차 1개로 담는다.
    private static Entry entry(LocalDate date, String taste, Rating rating, Recipe recipe, OffsetDateTime ts) {
        return new Entry(date, List.of(new Brew(recipe, new Tasting(taste, null, rating))), ts);
    }

    private static Entry entry(LocalDate date, String taste, String original, Rating rating, Recipe recipe,
                               OffsetDateTime ts) {
        return new Entry(date, List.of(new Brew(recipe, new Tasting(taste, original, rating))), ts);
    }

    // 실사용 샘플(ideas/sample.md 07-18) 수준의 회차 2개 엔트리 — 시도별 레시피·감상이 갈린다(AC-Δ6).
    private static Entry twoBrewEntry(LocalDate date, OffsetDateTime ts) {
        Brew first = new Brew(
                new Recipe("핸드드립", 15.0, 240.0, null, 160.0, 92.0, "210클릭 (매버릭 2.0)", null,
                        "뜸 40ml 30초 → 100ml → 100ml", "다음엔 분쇄를 더 굵게 갈 것"),
                new Tasting("첫 시도는 새콤함", null, Rating.GOOD));
        Brew second = new Brew(
                new Recipe(18.0, 250.0, "중간"),
                new Tasting("두 번째는 부드러움", null, Rating.PERFECT));
        return new Entry(date, List.of(first, second), ts);
    }

    // 두 노트(각 엔트리 1건·회차 1개, 감상만). note1=검색 보강/GOOD(2026-07-10), note2=PERFECT(2026-07-04).
    private NoteRepository seedRepository(Path dataDir) {
        NoteRepository repo = new JsonFileNoteRepository(dataDir, MochaObjectMapper.create());
        OffsetDateTime now = OffsetDateTime.parse("2026-07-10T09:00:00+09:00");
        NoteMeta meta1 = new NoteMeta(
                Sourced.user("예가체프 G1 워시드"),
                Sourced.user("커피베라"),
                // description = 검색 → 감상 카드는 값만 평문 렌더(출처 표기 없음, NoteView.TasteCard)
                List.of(new Bean(Sourced.search("에티오피아 예가체프"), Sourced.user("워시드"))),
                Sourced.search("라이트"),
                Sourced.search(List.of("자몽", "베르가못", "홍차")),
                List.of("https://coffeevera.example/yirgacheffe"));
        repo.upsertEntry("2026-07-10", meta1,
                entry(LocalDate.parse("2026-07-10"), "새콤하고 좋았다.\n다음엔 물 온도를 낮춰봐야지.", Rating.GOOD, null, now));

        NoteMeta meta2 = new NoteMeta(
                Sourced.user("콜롬비아 게이샤 워시드"),
                Sourced.user("프릳츠"),
                List.of(new Bean(Sourced.search("콜롬비아"), null)),
                null, Sourced.search(List.of()), List.of());
        repo.upsertEntry("2026-07-04", meta2,
                entry(LocalDate.parse("2026-07-04"), "화사하다.", Rating.PERFECT, null, now));
        return repo;
    }

    // --- TΔ5a 핵심: 회차 카드 산출·파일명 규칙 ---

    @Test
    @DisplayName("AC-Δ6/AC-74: 회차 2개(각 레시피+감상) 엔트리 → 카드 4장, <date>-taste-<n>·<date>-recipe-<n> 파일명 규칙")
    void twoBrewEntryBakesFourCardsWithBrewNumberedNames(@TempDir Path dataDir, @TempDir Path artifactDir) {
        NoteRepository repo = seedRepository(dataDir);
        OffsetDateTime now = OffsetDateTime.parse("2026-07-18T09:00:00+09:00");
        NoteMeta meta = new NoteMeta(
                Sourced.user("레인보우 블렌드"), Sourced.user("커피가게 동경"),
                List.of(new Bean(Sourced.user("에티오피아 예가체프"), Sourced.user("워시드"))),
                null, Sourced.search(List.of()), List.of());
        repo.upsertEntry("rainbow", meta, twoBrewEntry(LocalDate.parse("2026-07-18"), now));

        FakeCardImageRenderer cards = new FakeCardImageRenderer();
        ThymeleafNoteRenderer renderer = new ThymeleafNoteRenderer(repo, engine, artifactDir, Theme.TYPE_A, cards);

        List<Path> baked = renderer.renderEntryCard("rainbow", LocalDate.parse("2026-07-18"));

        // 반환 순서 = 회차 오름차순, 회차 안에서는 감상 → 레시피(CardFiles.expectedCards 계약).
        assertEquals(List.of(
                        artifactDir.resolve("cards/rainbow/2026-07-18-taste-1.jpg"),
                        artifactDir.resolve("cards/rainbow/2026-07-18-recipe-1.jpg"),
                        artifactDir.resolve("cards/rainbow/2026-07-18-taste-2.jpg"),
                        artifactDir.resolve("cards/rainbow/2026-07-18-recipe-2.jpg")),
                baked, "회차 2개 × (감상+레시피) = 4장, 파일명 n = 회차");
        baked.forEach(p -> assertTrue(Files.isRegularFile(p), "카드 JPG 존재: " + p));
        assertEquals(4, cards.calls.size(), "증분: 그 엔트리의 회차 카드만 굽는다");

        // 각 카드 HTML은 자기 회차 파트만 담는다 — 시도별 감상·피드백이 카드에 갈려 실린다(AC-75).
        String taste1 = capturedHtml(cards, "cards/rainbow/2026-07-18-taste-1.jpg");
        String taste2 = capturedHtml(cards, "cards/rainbow/2026-07-18-taste-2.jpg");
        assertTrue(taste1.contains("첫 시도는 새콤함"), "1회차 감상 카드 = 1회차 감상");
        assertFalse(taste1.contains("두 번째는 부드러움"), "1회차 카드에 2회차 감상 없음");
        assertTrue(taste2.contains("두 번째는 부드러움"), "2회차 감상 카드 = 2회차 감상");
        String recipe1 = capturedHtml(cards, "cards/rainbow/2026-07-18-recipe-1.jpg");
        assertTrue(recipe1.contains("다음엔 분쇄를 더 굵게 갈 것"), "1회차 레시피 카드 = 1회차 피드백");
        assertTrue(recipe1.contains("210클릭"), "1회차 분쇄값 렌더(grind 정규화 값)");
    }

    @Test
    @DisplayName("AC-78: 레시피만/감상만 있는 회차는 해당 카드만 생성된다(없는 파트 카드 미생성)")
    void partialBrewsBakeOnlyPresentPartCards(@TempDir Path dataDir, @TempDir Path artifactDir) {
        NoteRepository repo = new JsonFileNoteRepository(dataDir, MochaObjectMapper.create());
        OffsetDateTime now = OffsetDateTime.parse("2026-07-18T09:00:00+09:00");
        NoteMeta meta = new NoteMeta(
                Sourced.user("예가체프 G1"), Sourced.user("커피베라"),
                List.of(new Bean(Sourced.user("에티오피아"), null)),
                null, Sourced.search(List.of()), List.of());
        // 1회차 = 레시피만(감상 없음), 2회차 = 감상만(레시피 없음).
        Entry entry = new Entry(LocalDate.parse("2026-07-18"), List.of(
                new Brew(new Recipe(15.0, 240.0, "중간"), null),
                new Brew(null, new Tasting("두 번째 잔이 더 달다", null, Rating.GOOD))), now);
        repo.upsertEntry("partial", meta, entry);

        FakeCardImageRenderer cards = new FakeCardImageRenderer();
        List<Path> baked = new ThymeleafNoteRenderer(repo, engine, artifactDir, Theme.TYPE_A, cards)
                .renderEntryCard("partial", LocalDate.parse("2026-07-18"));

        assertEquals(List.of(
                        artifactDir.resolve("cards/partial/2026-07-18-recipe-1.jpg"),
                        artifactDir.resolve("cards/partial/2026-07-18-taste-2.jpg")),
                baked, "있는 파트만 — 1회차 레시피 카드 + 2회차 감상 카드");
        assertFalse(Files.exists(artifactDir.resolve("cards/partial/2026-07-18-taste-1.jpg")),
                "감상 없는 1회차의 감상 카드 미생성(AC-78)");
        assertFalse(Files.exists(artifactDir.resolve("cards/partial/2026-07-18-recipe-2.jpg")),
                "레시피 없는 2회차의 레시피 카드 미생성(AC-78)");
        assertEquals(2, cards.calls.size(), "카드 굽기 호출도 있는 파트 수만큼");
    }

    @Test
    @DisplayName("TΔ5a: 회차 감소 재저장 후 renderEntryCard → 옛 번호 카드가 잔존하지 않는다")
    void rerenderAfterBrewShrinkLeavesNoStaleCards(@TempDir Path dataDir, @TempDir Path artifactDir) {
        NoteRepository repo = new JsonFileNoteRepository(dataDir, MochaObjectMapper.create());
        OffsetDateTime now = OffsetDateTime.parse("2026-07-18T09:00:00+09:00");
        NoteMeta meta = new NoteMeta(
                Sourced.user("레인보우 블렌드"), Sourced.user("커피가게 동경"),
                List.of(new Bean(Sourced.user("에티오피아"), null)),
                null, Sourced.search(List.of()), List.of());
        LocalDate date = LocalDate.parse("2026-07-18");
        repo.upsertEntry("rainbow", meta, twoBrewEntry(date, now));

        FakeCardImageRenderer cards = new FakeCardImageRenderer();
        ThymeleafNoteRenderer renderer = new ThymeleafNoteRenderer(repo, engine, artifactDir, Theme.TYPE_A, cards);
        renderer.renderEntryCard("rainbow", date);
        assertTrue(Files.exists(artifactDir.resolve("cards/rainbow/2026-07-18-taste-2.jpg")), "감소 전 2회차 카드 존재");

        // 수정 커밋으로 회차가 2개 → 1개로 줄었다(brews 통째 교체 — ADR-59 patch 의미론).
        Note origin = repo.findBySlug("rainbow").orElseThrow();
        Entry shrunk = new Entry(date, List.of(origin.entries().getFirst().brews().getFirst()), now);
        repo.applyEdit("rainbow", date, withOnlyEntry(origin, shrunk));

        List<Path> baked = renderer.renderEntryCard("rainbow", date);

        assertEquals(List.of(
                        artifactDir.resolve("cards/rainbow/2026-07-18-taste-1.jpg"),
                        artifactDir.resolve("cards/rainbow/2026-07-18-recipe-1.jpg")),
                baked, "남은 회차 카드만 산출");
        assertFalse(Files.exists(artifactDir.resolve("cards/rainbow/2026-07-18-taste-2.jpg")),
                "옛 2회차 감상 카드 잔존 없음");
        assertFalse(Files.exists(artifactDir.resolve("cards/rainbow/2026-07-18-recipe-2.jpg")),
                "옛 2회차 레시피 카드 잔존 없음");
    }

    // --- index 폐기(TΔ6, ADR-55)·기본 산출 구조 ---

    @Test
    @DisplayName("AC-67: renderAll(--rerender)이 index.html을 생성하지 않는다 — artifact/ 아래 HTML 산출 0건, 산출은 cards/ JPG뿐")
    void renderAllProducesNoIndexHtml(@TempDir Path dataDir, @TempDir Path artifactDir) {
        NoteRepository repo = seedRepository(dataDir);
        FakeCardImageRenderer cards = new FakeCardImageRenderer();
        new ThymeleafNoteRenderer(repo, engine, artifactDir, Theme.TYPE_B, cards).renderAll();

        // artifact/ 아래 HTML 산출 금지(ADR-55 POLICY) — index.html도, 노트 상세 HTML도 없다.
        assertFalse(Files.exists(artifactDir.resolve("index.html")), "index.html 미생성(AC-67)");
        assertFalse(Files.exists(artifactDir.resolve("notes")), "notes/ 디렉터리 미생성");
        assertEquals(java.util.Set.of(), htmlFiles(artifactDir), "artifact/ 아래 HTML 파일 0건");

        // 감상만 있는 회차 1개 엔트리 2건 → 감상 카드 2장(레시피 카드 없음 — AC-78).
        assertTrue(Files.isRegularFile(artifactDir.resolve("cards/2026-07-10/2026-07-10-taste-1.jpg")), "엔트리1 감상 카드");
        assertTrue(Files.isRegularFile(artifactDir.resolve("cards/2026-07-04/2026-07-04-taste-1.jpg")), "엔트리2 감상 카드");
        assertEquals(2, cards.calls.size(), "엔트리 2건(감상 회차 1개씩) → 카드 굽기 2회");
        // 카드는 artifact 루트를 base로 굽는다(상대 자원 해석 기준, AC-Δ5).
        assertTrue(cards.calls.stream().allMatch(c -> c.baseDir().equals(artifactDir)), "baseDir = artifact 루트");
    }

    @Test
    @DisplayName("ADR-54: 감상 카드 HTML이 4:5·로컬 폰트·회차 파트 1건이고 print/A시리즈가 없다")
    void tasteCardHtmlIsFourFiveWithLocalFont(@TempDir Path dataDir, @TempDir Path artifactDir) {
        NoteRepository repo = seedRepository(dataDir);
        FakeCardImageRenderer cards = new FakeCardImageRenderer();
        new ThymeleafNoteRenderer(repo, engine, artifactDir, Theme.TYPE_B, cards).renderAll();

        String cardHtml = capturedHtml(cards, "cards/2026-07-10/2026-07-10-taste-1.jpg");

        // 4:5 = 1080×1350 뷰포트.
        assertTrue(cardHtml.contains("1080px") && cardHtml.contains("1350px"), "4:5(1080×1350) 규칙");
        // 로컬 폰트 참조(ADR-11) — CDN 미의존.
        assertTrue(cardHtml.contains("fonts/GowunDodum-Regular.ttf"), "로컬 폰트 @font-face 참조");
        assertTrue(cardHtml.contains("@font-face"), "@font-face 선언");
        assertFalse(cardHtml.contains("fonts.googleapis.com"), "CDN 폰트 링크 없음");
        // FR-7 감상 카드 영역.
        assertTrue(cardHtml.contains("로스터리가 말하길"), "official_notes 영역");
        assertTrue(cardHtml.contains("내가 느끼길"), "my_taste 영역");
        assertTrue(cardHtml.contains("새콤하고 좋았다"), "회차 감상 표시");
        // print/A시리즈 잔재 부재(회귀 가드 — 재유입 방지).
        assertFalse(cardHtml.contains("@page"), "@page 없음");
        assertFalse(cardHtml.contains("@media print"), "@media print 없음");
        assertFalse(cardHtml.contains("A4") || cardHtml.contains("A5"), "A4/A5 인쇄 잔재 없음");
        // 카드도 상대 경로만(파일 없음/절대 경로 없음).
        assertFalse(cardHtml.contains("file:"), "file:// 없음");
        assertFalse(cardHtml.contains(artifactDir.toString()), "절대 경로 유출 없음");
    }

    @Test
    @DisplayName("AC-Δ5(changes/0013): 감상 카드는 my_taste(정규화)만 렌더하고 my_taste_original(원문)은 노출하지 않는다")
    void tasteCardRendersNormalizedTasteAndHidesOriginal(@TempDir Path dataDir, @TempDir Path artifactDir) {
        NoteRepository repo = new JsonFileNoteRepository(dataDir, MochaObjectMapper.create());
        OffsetDateTime now = OffsetDateTime.parse("2026-07-10T09:00:00+09:00");
        NoteMeta meta = new NoteMeta(
                Sourced.user("예가체프 G1"),
                Sourced.user("커피베라"), List.of(new Bean(Sourced.search("에티오피아"), null)),
                null, Sourced.search(List.of()), List.of());
        // 정규화본(음슴체)과 발화 원문을 서로 다른 문자열로 둔다 — 원문 유출을 판별 가능하게.
        repo.upsertEntry("2026-07-10", meta,
                entry(LocalDate.parse("2026-07-10"), "새콤하고 좋았음", "새콤하고 좋았다구우",
                        Rating.GOOD, null, now));

        FakeCardImageRenderer cards = new FakeCardImageRenderer();
        new ThymeleafNoteRenderer(repo, engine, artifactDir, Theme.TYPE_B, cards).renderAll();

        String cardHtml = capturedHtml(cards, "cards/2026-07-10/2026-07-10-taste-1.jpg");
        assertTrue(cardHtml.contains("새콤하고 좋았음"), "정규화본(my_taste) 렌더");
        assertFalse(cardHtml.contains("새콤하고 좋았다구우"), "원문(my_taste_original)은 카드에 노출 안 됨(V-11)");
    }

    @Test
    @DisplayName("같은 커피를 다른 날 기록하면 엔트리마다 별도 카드가 생기고, 카드 HTML은 자기 날짜 회차만 담는다")
    void sameCoffeeDifferentDatesYieldSeparateCards(@TempDir Path dataDir, @TempDir Path artifactDir) {
        NoteRepository repo = new JsonFileNoteRepository(dataDir, MochaObjectMapper.create());
        OffsetDateTime now = OffsetDateTime.parse("2026-07-10T09:00:00+09:00");
        NoteMeta meta = new NoteMeta(
                Sourced.user("예가체프 G1 워시드"),
                Sourced.user("커피베라"),
                List.of(new Bean(Sourced.search("에티오피아 예가체프"), Sourced.user("워시드"))),
                Sourced.search("라이트"),
                Sourced.search(List.of("자몽", "홍차")),
                List.of("https://coffeevera.example/yirgacheffe"));
        // 같은 노트(slug)에 다른 날짜 엔트리 2건.
        repo.upsertEntry("yirgacheffe", meta,
                entry(LocalDate.parse("2026-07-04"), "첫날: 새콤하고 좋았다.", Rating.GOOD, null, now));
        repo.upsertEntry("yirgacheffe", meta,
                entry(LocalDate.parse("2026-07-10"), "둘째 날: 물 온도를 낮추니 부드럽다.", Rating.PERFECT, null, now));

        FakeCardImageRenderer cards = new FakeCardImageRenderer();
        new ThymeleafNoteRenderer(repo, engine, artifactDir, Theme.TYPE_B, cards).renderAll();

        // 엔트리마다 별도 카드(같은 slug, 다른 date).
        assertTrue(Files.isRegularFile(artifactDir.resolve("cards/yirgacheffe/2026-07-04-taste-1.jpg")), "첫날 카드");
        assertTrue(Files.isRegularFile(artifactDir.resolve("cards/yirgacheffe/2026-07-10-taste-1.jpg")), "둘째 날 카드");

        // 각 카드 HTML은 자기 엔트리(날짜) 회차 감상만 담는다(AC-Δ4).
        String firstCard = capturedHtml(cards, "cards/yirgacheffe/2026-07-04-taste-1.jpg");
        String secondCard = capturedHtml(cards, "cards/yirgacheffe/2026-07-10-taste-1.jpg");
        assertTrue(firstCard.contains("첫날: 새콤하고 좋았다."), "첫날 카드에 첫날 감상");
        assertFalse(firstCard.contains("둘째 날"), "첫날 카드에 둘째 날 감상 없음");
        assertTrue(secondCard.contains("둘째 날: 물 온도를 낮추니 부드럽다."), "둘째 날 카드에 둘째 날 감상");
        assertFalse(secondCard.contains("첫날"), "둘째 날 카드에 첫날 감상 없음");
    }

    @Test
    @DisplayName("AC-Δ2(changes/0014): 사진이 있는 엔트리도 카드 HTML에 사진·썸네일 요소가 없다(아카이브 전용)")
    void cardHasNoPhotoOrThumbElements(@TempDir Path dataDir, @TempDir Path artifactDir) {
        NoteRepository repo = new JsonFileNoteRepository(dataDir, MochaObjectMapper.create());
        OffsetDateTime now = OffsetDateTime.parse("2026-07-10T09:00:00+09:00");
        NoteMeta meta = new NoteMeta(
                Sourced.user("예가체프 G1 워시드"),
                Sourced.user("커피베라"), List.of(new Bean(Sourced.search("에티오피아"), null)),
                null, Sourced.search(List.of()), List.of());
        // 렌더는 사진을 읽지 않는다 — 엔트리에 사진 필드가 없고 템플릿에도 사진 슬롯이 없다(changes/0014 ADR-32, AC-Δ2).
        repo.upsertEntry("2026-07-10", meta,
                entry(LocalDate.parse("2026-07-10"), "새콤하다.", Rating.GOOD, null, now));

        FakeCardImageRenderer cards = new FakeCardImageRenderer();
        new ThymeleafNoteRenderer(repo, engine, artifactDir, Theme.TYPE_B, cards).renderAll();

        String cardHtml = capturedHtml(cards, "cards/2026-07-10/2026-07-10-taste-1.jpg");
        // 사진·썸네일 경로/요소가 산출 HTML에 전혀 없다(사진 유무 무관 동일 레이아웃).
        assertFalse(cardHtml.contains("photos/"), "카드에 사진 경로 없음");
        assertFalse(cardHtml.contains("thumbs/"), "카드에 썸네일 경로 없음");
        assertFalse(cardHtml.contains("class=\"thumb\""), "카드에 썸네일 요소 없음");
        assertFalse(cardHtml.contains("alt=\"사진\""), "카드에 사진 img 없음");
        // 감상 텍스트는 그대로 렌더된다(사진만 빠짐).
        assertTrue(cardHtml.contains("새콤하다."), "감상 텍스트는 유지");
    }

    @Test
    @DisplayName("AC-Δ10⑥/V-13(changes/0016): aliases를 채운 노트도 카드 HTML에 별칭 문자열이 나타나지 않는다")
    void aliasesNeverAppearInRenderedOutput(@TempDir Path dataDir, @TempDir Path artA, @TempDir Path artB) {
        NoteRepository repo = new JsonFileNoteRepository(dataDir, MochaObjectMapper.create());
        OffsetDateTime now = OffsetDateTime.parse("2026-07-10T09:00:00+09:00");
        NoteMeta meta = new NoteMeta(
                Sourced.user("예가체프 G1 워시드"),
                Sourced.user("커피베라"),
                List.of(new Bean(Sourced.search("에티오피아 예가체프"), Sourced.user("워시드"))),
                Sourced.search("라이트"),
                Sourced.search(List.of("자몽", "홍차")), List.of());
        // 별칭은 표시값(커피명·로스터리)과 명확히 다른 마커 문자열 — 렌더에 새면 반드시 검출된다(V-13).
        Aliases aliases = new Aliases(
                List.of("yirgacheffe-ALIAS-LEAK", "이르가체프이표기마커"),
                List.of("coffeevera-ALIAS-LEAK"));
        // 신규 slug → 별칭이 노트 초기 별칭으로 심긴다(upsertEntry 4-arg, ADR-37).
        Note saved = repo.upsertEntry("2026-07-10", meta,
                entry(LocalDate.parse("2026-07-10"), "새콤하다.", Rating.GOOD, null, now), aliases);
        // 가드 유효성 전제: 별칭이 실제로 노트에 심겼다(빈 별칭이면 가드가 무의미해진다).
        assertFalse(saved.aliases().coffeeName().isEmpty(), "별칭이 노트에 심겼다(가드 유효성 전제)");

        List<String> markers = List.of("yirgacheffe-ALIAS-LEAK", "이르가체프이표기마커", "coffeevera-ALIAS-LEAK");
        // 두 테마 템플릿 모두 별칭을 참조할 슬롯이 없다(구조적 불변) — 회귀 가드로 두 산출을 확인.
        for (Theme theme : new Theme[]{Theme.TYPE_A, Theme.TYPE_B}) {
            Path artifactDir = theme == Theme.TYPE_A ? artA : artB;
            FakeCardImageRenderer cards = new FakeCardImageRenderer();
            new ThymeleafNoteRenderer(repo, engine, artifactDir, theme, cards).renderAll();

            String cardHtml = capturedHtml(cards, "cards/2026-07-10/2026-07-10-taste-1.jpg");
            for (String marker : markers) {
                assertFalse(cardHtml.contains(marker), theme + " 카드에 별칭 미출현(V-13): " + marker);
            }
            // 별칭만 빠진 것이지 노트 자체가 안 나오는 게 아니다 — 표시값은 정상 렌더.
            assertTrue(cardHtml.contains("예가체프 G1 워시드"), theme + ": 커피명 표시값은 정상 렌더");
        }
    }

    @Test
    @DisplayName("AC-Δ3(changes/0014): data/photos/를 통째로 옮겨둬도 renderAll 산출(카드)이 동일하다 — 리렌더 입력은 JSON뿐")
    void renderAllIsIndependentOfPhotosDirectory(
            @TempDir Path dataDir, @TempDir Path withPhotosDir, @TempDir Path withoutPhotosDir) throws IOException {
        NoteRepository repo = new JsonFileNoteRepository(dataDir, MochaObjectMapper.create());
        OffsetDateTime now = OffsetDateTime.parse("2026-07-10T09:00:00+09:00");
        NoteMeta meta = new NoteMeta(
                Sourced.user("예가체프 G1 워시드"),
                Sourced.user("커피베라"), List.of(new Bean(Sourced.search("에티오피아"), null)),
                null, Sourced.search(List.of()), List.of());
        // 엔트리 + 실제 사진 파일을 data/photos/에 둔다 — 사진은 노트가 아닌 폴더에만 존재(리렌더 입력 후보로서의 파일 존재를 재현).
        repo.upsertEntry("2026-07-10", meta,
                entry(LocalDate.parse("2026-07-10"), "새콤하다.", Rating.GOOD, null, now));
        Path photosDir = dataDir.resolve("photos/2026-07-10/2026-07-10");
        Files.createDirectories(photosDir);
        Files.write(photosDir.resolve("a.jpg"), new byte[]{1, 2, 3});

        // (1) 사진 폴더가 있는 상태로 렌더.
        FakeCardImageRenderer cardsWith = new FakeCardImageRenderer();
        new ThymeleafNoteRenderer(repo, engine, withPhotosDir, Theme.TYPE_B, cardsWith).renderAll();
        String cardWith = capturedHtml(cardsWith, "cards/2026-07-10/2026-07-10-taste-1.jpg");

        // data/photos/를 통째로 옮겨둔다(삭제로 갈음) — 리렌더 입력에서 사진이 사라진 상태.
        deleteRecursively(dataDir.resolve("photos"));
        assertFalse(Files.exists(dataDir.resolve("photos")), "photos/ 옮겨둠");

        // (2) 사진 폴더가 없는 상태로 재렌더 — JSON만으로 카드가 생성돼야 한다(NFR-3, AC-6).
        FakeCardImageRenderer cardsWithout = new FakeCardImageRenderer();
        new ThymeleafNoteRenderer(repo, engine, withoutPhotosDir, Theme.TYPE_B, cardsWithout).renderAll();
        assertTrue(Files.isRegularFile(withoutPhotosDir.resolve("cards/2026-07-10/2026-07-10-taste-1.jpg")),
                "사진 없이도 카드 생성");
        String cardWithout = capturedHtml(cardsWithout, "cards/2026-07-10/2026-07-10-taste-1.jpg");

        // 산출 동일 — 렌더는 JSON만 읽고 data/photos/ 존재 여부에 의존하지 않는다(AC-Δ3).
        assertEquals(cardWith, cardWithout, "카드 HTML이 사진 폴더 유무와 무관하게 동일");
        assertEquals(cardFiles(withPhotosDir), cardFiles(withoutPhotosDir), "카드 파일 집합 동일");
    }

    @Test
    @DisplayName("AC-Δ7/AC-6(개정형): artifact/ 삭제 후 재렌더하면 JSON만으로 전체 회차 카드가 동일하게 복원된다")
    void reRenderIsReproducible(@TempDir Path dataDir, @TempDir Path artifactDir) {
        NoteRepository repo = seedRepository(dataDir);

        FakeCardImageRenderer cards1 = new FakeCardImageRenderer();
        ThymeleafNoteRenderer renderer1 = new ThymeleafNoteRenderer(repo, engine, artifactDir, Theme.TYPE_A, cards1);
        renderer1.renderAll();
        java.util.Set<String> cardFilesFirst = cardFiles(artifactDir);
        String cardFirst = capturedHtml(cards1, "cards/2026-07-10/2026-07-10-taste-1.jpg");

        deleteRecursively(artifactDir);
        assertFalse(Files.exists(artifactDir.resolve("cards")), "artifact/ 비워짐");

        FakeCardImageRenderer cards2 = new FakeCardImageRenderer();
        new ThymeleafNoteRenderer(repo, engine, artifactDir, Theme.TYPE_A, cards2).renderAll();
        assertEquals(cardFilesFirst, cardFiles(artifactDir), "카드 파일 집합 재현성");
        assertEquals(cardFirst, capturedHtml(cards2, "cards/2026-07-10/2026-07-10-taste-1.jpg"), "카드 HTML 재현성");
        assertEquals(cards1.calls.size(), cards2.calls.size(), "카드 굽기 횟수 재현성");
    }

    @Test
    @DisplayName("AC-Δ7(증분)/AC-67: renderEntryCard는 대상 엔트리 카드만 새로 굽고 경로 목록을 반환하며 index.html을 만들지 않는다")
    void renderEntryCardBakesEntryCardsAndReturnsPaths(@TempDir Path dataDir, @TempDir Path artifactDir) {
        NoteRepository repo = seedRepository(dataDir);
        FakeCardImageRenderer cards = new FakeCardImageRenderer();
        ThymeleafNoteRenderer renderer = new ThymeleafNoteRenderer(repo, engine, artifactDir, Theme.TYPE_B, cards);

        List<Path> baked = renderer.renderEntryCard("2026-07-10", LocalDate.parse("2026-07-10"));

        assertEquals(List.of(artifactDir.resolve("cards/2026-07-10/2026-07-10-taste-1.jpg")), baked,
                "반환 목록 = 그 엔트리 회차 카드(감상 회차 1개 → 1장)");
        assertTrue(Files.isRegularFile(baked.getFirst()), "카드 JPG 존재");
        assertEquals(1, cards.calls.size(), "증분: 대상 엔트리 카드만 굽는다(전체 재래스터화 없음)");
        // 저장([저장] 커밋 → 증분 렌더) 경로에서도 HTML 산출이 없다(AC-67).
        assertFalse(Files.exists(artifactDir.resolve("index.html")), "index.html 미생성(AC-67)");
        assertEquals(java.util.Set.of(), htmlFiles(artifactDir), "artifact/ 아래 HTML 파일 0건");
    }

    @Test
    @DisplayName("ADR-54: mocha.artifact.theme(type-a/type-b)로 카드 디자인이 갈린다")
    void themeSelectsCardDesign(@TempDir Path dataDir, @TempDir Path artifactA, @TempDir Path artifactB) {
        NoteRepository repo = seedRepository(dataDir);
        FakeCardImageRenderer cardsA = new FakeCardImageRenderer();
        FakeCardImageRenderer cardsB = new FakeCardImageRenderer();
        new ThymeleafNoteRenderer(repo, engine, artifactA, Theme.TYPE_A, cardsA).renderAll();
        new ThymeleafNoteRenderer(repo, engine, artifactB, Theme.TYPE_B, cardsB).renderAll();

        String serifCard = capturedHtml(cardsA, "cards/2026-07-10/2026-07-10-taste-1.jpg");
        String cuteCard = capturedHtml(cardsB, "cards/2026-07-10/2026-07-10-taste-1.jpg");
        assertTrue(serifCard.contains("Gowun Batang") && serifCard.contains("COFFEE NOTE"), "type-a=세리프");
        assertTrue(cuteCard.contains("Gowun Dodum") && cuteCard.contains("mascot-face.png"), "type-b=귀여운(마스코트)");
        // 공통: 두 테마 모두 4:5 + 감상 영역, 이모티콘 없는 rating 뱃지(ADR-54 편차①).
        assertTrue(serifCard.contains("1080px") && cuteCard.contains("1080px"), "두 테마 4:5");
        assertTrue(serifCard.contains("내가 느끼길") && cuteCard.contains("내가 느끼길"), "감상 영역");
        assertFalse(serifCard.contains("😋") || cuteCard.contains("😋"), "rating 뱃지 이모티콘 없음");
    }

    // --- 레시피 카드(회차 파트) 생성 분기 ---

    // recipe·coffeeName만 갈아끼우는 단일 엔트리(회차 1개: 감상+recipe) 노트 seed. 나머지 메타는 고정.
    private NoteRepository seedWithRecipe(Path dataDir, Sourced<String> coffeeName, Recipe recipe) {
        NoteRepository repo = new JsonFileNoteRepository(dataDir, MochaObjectMapper.create());
        OffsetDateTime now = OffsetDateTime.parse("2026-07-10T09:00:00+09:00");
        NoteMeta meta = new NoteMeta(
                coffeeName,
                Sourced.user("커피베라"),
                List.of(new Bean(Sourced.search("에티오피아 예가체프"), Sourced.user("워시드"))),
                Sourced.search("라이트"),
                Sourced.search(List.of("자몽", "홍차")), List.of());
        repo.upsertEntry("2026-07-10", meta,
                entry(LocalDate.parse("2026-07-10"), "새콤하다.", Rating.GOOD, recipe, now));
        return repo;
    }

    private FakeCardImageRenderer renderAllWith(NoteRepository repo, Theme theme, Path artifactDir) {
        FakeCardImageRenderer cards = new FakeCardImageRenderer();
        new ThymeleafNoteRenderer(repo, engine, artifactDir, theme, cards).renderAll();
        return cards;
    }

    @Test
    @DisplayName("ADR-54·59: recipe 있는 회차는 두 테마 모두 레시피 카드가 별도로 구워지고 수치가 렌더된다")
    void recipeBrewBakesRecipeCard(@TempDir Path dataDir, @TempDir Path artA, @TempDir Path artB) {
        Recipe recipe = new Recipe(15.0, 240.0, "중간");
        for (Theme theme : new Theme[]{Theme.TYPE_A, Theme.TYPE_B}) {
            NoteRepository repo = seedWithRecipe(dataDir, Sourced.user("예가체프 G1 워시드"), recipe);
            Path artifactDir = theme == Theme.TYPE_A ? artA : artB;
            FakeCardImageRenderer cards = renderAllWith(repo, theme, artifactDir);

            assertTrue(Files.isRegularFile(artifactDir.resolve("cards/2026-07-10/2026-07-10-recipe-1.jpg")),
                    theme + ": 레시피 카드 별도 산출");
            String recipeCard = capturedHtml(cards, "cards/2026-07-10/2026-07-10-recipe-1.jpg");
            assertTrue(recipeCard.contains("15g"), theme + ": 원두 15g(소수점 없이)");
            assertTrue(recipeCard.contains("240ml"), theme + ": 물 240ml");
            assertTrue(recipeCard.contains("중간"), theme + ": 분쇄도 중간");
            assertTrue(recipeCard.contains("1080px") && recipeCard.contains("1350px"), theme + ": 레시피 카드도 4:5");
            // 감상 카드에는 레시피 영역이 없다 — 레시피는 레시피 카드로 완전 이관(ADR-54).
            String tasteCard = capturedHtml(cards, "cards/2026-07-10/2026-07-10-taste-1.jpg");
            assertFalse(tasteCard.contains("240ml"), theme + ": 감상 카드에 레시피 수치 없음");
        }
    }

    @Test
    @DisplayName("AC-78: recipe가 null인 회차는 레시피 카드가 생성되지 않는다(감상 카드만)")
    void recipeCardOmittedWhenRecipeAbsent(@TempDir Path dataDir, @TempDir Path artA, @TempDir Path artB) {
        for (Theme theme : new Theme[]{Theme.TYPE_A, Theme.TYPE_B}) {
            NoteRepository repo = seedWithRecipe(dataDir, Sourced.user("예가체프 G1 워시드"), null);
            Path artifactDir = theme == Theme.TYPE_A ? artA : artB;
            FakeCardImageRenderer cards = renderAllWith(repo, theme, artifactDir);

            assertTrue(Files.isRegularFile(artifactDir.resolve("cards/2026-07-10/2026-07-10-taste-1.jpg")),
                    theme + ": 감상 카드는 산출");
            assertFalse(Files.exists(artifactDir.resolve("cards/2026-07-10/2026-07-10-recipe-1.jpg")),
                    theme + ": 레시피 없는 회차의 레시피 카드 미생성");
            assertEquals(1, cards.calls.size(), theme + ": 굽기 호출도 1회뿐");
        }
    }

    @Test
    @DisplayName("FR-7: recipe가 일부만 있으면(원두만) 레시피 카드에 있는 항목만 표시한다")
    void recipeCardRendersOnlyPresentItems(@TempDir Path dataDir, @TempDir Path artA, @TempDir Path artB) {
        Recipe partial = Recipe.normalize(new Recipe(15.0, null, null)); // 원두만
        for (Theme theme : new Theme[]{Theme.TYPE_A, Theme.TYPE_B}) {
            NoteRepository repo = seedWithRecipe(dataDir, Sourced.user("예가체프 G1 워시드"), partial);
            Path artifactDir = theme == Theme.TYPE_A ? artA : artB;
            FakeCardImageRenderer cards = renderAllWith(repo, theme, artifactDir);

            String recipeCard = capturedHtml(cards, "cards/2026-07-10/2026-07-10-recipe-1.jpg");
            assertTrue(recipeCard.contains("15g"), theme + ": 원두 표시");
            assertFalse(recipeCard.contains("240ml"), theme + ": 물 항목 미출력");
        }
    }

    @Test
    @DisplayName("NoteView.TasteCard: coffeeName source가 photo여도 카드 제목은 값만 쓰고 (사진) 표기를 달지 않는다")
    void cardTitleHasNoPhotoTag(@TempDir Path dataDir, @TempDir Path artA, @TempDir Path artB) {
        for (Theme theme : new Theme[]{Theme.TYPE_A, Theme.TYPE_B}) {
            NoteRepository repo = seedWithRecipe(dataDir, Sourced.photo("게이샤 내추럴"), null);
            Path artifactDir = theme == Theme.TYPE_A ? artA : artB;
            FakeCardImageRenderer cards = renderAllWith(repo, theme, artifactDir);
            String card = capturedHtml(cards, "cards/2026-07-10/2026-07-10-taste-1.jpg");
            assertTrue(card.contains("게이샤 내추럴"), theme + ": 제목에 커피명 값");
            assertFalse(card.contains("(사진)"), theme + ": 제목=정체성 → (사진) 무표기");
        }
    }

    // --- removeEntryCard + renderAll 고아 카드 정리(AC-39·AC-Δ7) ---

    // 원본 노트의 단일 엔트리를 replacement로 바꾼 edit draft(엔트리 1건) — applyEdit 입력.
    private static Note withOnlyEntry(Note origin, Entry replacement) {
        return new Note(origin.slug(), origin.coffeeName(), origin.roastery(), origin.beans(),
                origin.roastLevel(), origin.officialNotes(), origin.sources(),
                List.of(replacement), origin.createdAt(), origin.updatedAt());
    }

    private static Note moveDateDraft(Note origin, LocalDate newDate) {
        Entry e = origin.entries().get(0);
        return withOnlyEntry(origin, new Entry(newDate, e.brews(), e.updatedAt()));
    }

    @Test
    @DisplayName("AC-39: 날짜 이동 커밋 후 옛 카드 삭제→새 카드 증분 렌더 → 옛 날짜 카드 부재·새 카드 존재")
    void dateMoveCleansOldCards(@TempDir Path dataDir, @TempDir Path artifactDir) {
        NoteRepository repo = seedRepository(dataDir);
        ThymeleafNoteRenderer renderer =
                new ThymeleafNoteRenderer(repo, engine, artifactDir, Theme.TYPE_B, new FakeCardImageRenderer());
        renderer.renderAll();
        assertTrue(Files.isRegularFile(artifactDir.resolve("cards/2026-07-10/2026-07-10-taste-1.jpg")), "이동 전 카드 존재");

        // edit 커밋(07-10 → 07-11) 후 파생물 정리 순서: 옛 카드 삭제 → 새 카드 증분 렌더.
        Note origin = repo.findBySlug("2026-07-10").orElseThrow();
        repo.applyEdit("2026-07-10", LocalDate.parse("2026-07-10"), moveDateDraft(origin, LocalDate.parse("2026-07-11")));
        renderer.removeEntryCard("2026-07-10", LocalDate.parse("2026-07-10"));
        renderer.renderEntryCard("2026-07-10", LocalDate.parse("2026-07-11"));

        assertFalse(Files.exists(artifactDir.resolve("cards/2026-07-10/2026-07-10-taste-1.jpg")), "옛 date 카드 부재");
        assertTrue(Files.isRegularFile(artifactDir.resolve("cards/2026-07-10/2026-07-11-taste-1.jpg")), "새 date 카드 존재");
        assertTrue(Files.isRegularFile(artifactDir.resolve("cards/2026-07-04/2026-07-04-taste-1.jpg")), "다른 엔트리 카드는 유지");
    }

    @Test
    @DisplayName("AC-39(회차화): removeEntryCard는 그 엔트리의 회차 카드 전부를 삭제한다")
    void removeEntryCardDeletesAllBrewCardsOfEntry(@TempDir Path dataDir, @TempDir Path artifactDir) {
        NoteRepository repo = new JsonFileNoteRepository(dataDir, MochaObjectMapper.create());
        OffsetDateTime now = OffsetDateTime.parse("2026-07-18T09:00:00+09:00");
        NoteMeta meta = new NoteMeta(
                Sourced.user("레인보우 블렌드"), Sourced.user("커피가게 동경"),
                List.of(new Bean(Sourced.user("에티오피아"), null)),
                null, Sourced.search(List.of()), List.of());
        LocalDate date = LocalDate.parse("2026-07-18");
        repo.upsertEntry("rainbow", meta, twoBrewEntry(date, now));

        ThymeleafNoteRenderer renderer =
                new ThymeleafNoteRenderer(repo, engine, artifactDir, Theme.TYPE_A, new FakeCardImageRenderer());
        List<Path> baked = renderer.renderEntryCard("rainbow", date);
        assertEquals(4, baked.size(), "회차 2개 → 4장(전제)");

        renderer.removeEntryCard("rainbow", date);

        baked.forEach(p -> assertFalse(Files.exists(p), "회차 카드 전부 삭제: " + p));
    }

    @Test
    @DisplayName("removeEntryCard: 대상 카드 파일이 없어도 예외 없이 지나간다(멱등)")
    void removeEntryCardIsHarmlessWhenAbsent(@TempDir Path dataDir, @TempDir Path artifactDir) {
        ThymeleafNoteRenderer renderer = new ThymeleafNoteRenderer(
                seedRepository(dataDir), engine, artifactDir, Theme.TYPE_B, new FakeCardImageRenderer());
        // 산출 전(cards/ 자체가 없음)에도, 같은 호출을 반복해도 무해해야 한다.
        renderer.removeEntryCard("2026-07-10", LocalDate.parse("2026-07-10"));
        renderer.removeEntryCard("2026-07-10", LocalDate.parse("2026-07-10"));
        assertFalse(Files.exists(artifactDir.resolve("cards/2026-07-10/2026-07-10-taste-1.jpg")));
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
        assertTrue(Files.isRegularFile(artifactDir.resolve("cards/2026-07-10/2026-07-10-taste-1.jpg")), "고아 카드가 남아 있다");

        renderer.renderAll(); // --rerender

        assertFalse(Files.exists(artifactDir.resolve("cards/2026-07-10/2026-07-10-taste-1.jpg")), "renderAll이 고아 카드를 정리(plan §7)");
        // 재현 동일성(AC-Δ7): 같은 JSON을 빈 디렉토리에 새로 렌더한 산출과 카드 집합이 동일하다.
        new ThymeleafNoteRenderer(repo, engine, freshDir, Theme.TYPE_B, new FakeCardImageRenderer()).renderAll();
        assertEquals(cardFiles(freshDir), cardFiles(artifactDir), "카드 파일 집합 동일");
    }

    // artifact/ 하위 전체에서 .html 파일의 상대 경로 집합 — HTML 산출 금지(AC-67, ADR-55 POLICY) 가드.
    private static java.util.Set<String> htmlFiles(Path artifactDir) {
        try (Stream<Path> walk = Files.walk(artifactDir)) {
            return walk.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".html"))
                    .map(p -> artifactDir.relativize(p).toString().replace(java.io.File.separatorChar, '/'))
                    .collect(java.util.stream.Collectors.toSet());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
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
