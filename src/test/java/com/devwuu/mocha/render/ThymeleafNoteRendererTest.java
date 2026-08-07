package com.devwuu.mocha.render;

import com.devwuu.mocha.domain.Source;
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
import com.devwuu.mocha.domain.Review;
import com.devwuu.mocha.service.NoteService;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TΔ5a(changes/0021): 렌더 파이프라인 회차화 — 산출 단위가 <b>회차 파트</b>(감상/레시피 카드,
 * {@code cards/<접미>/<date>-taste-<n>.jpg}·{@code <date>-recipe-<n>.jpg})로 전환됨을 검증한다.
 * 저장소 조회 결과(도메인 노트) → 회차 카드 JPG. 실 래스터화는 {@link FakeCardImageRenderer}로 대체해
 * 경로/파일명 규칙·카드 HTML 계약을 결정론적으로 본다(실 Chromium은 태그 분리).
 * <ul>
 *   <li>AC-Δ6(렌더 파트): 회차 2개 엔트리 → 카드 4장, 파일명 규칙(n = 회차)</li>
 *   <li>AC-78: 레시피/감상 없는 회차는 해당 카드 미생성</li>
 *   <li>회차 감소 재저장 → 옛 번호 카드 잔존 없음, AC-39: 날짜 이동 시 옛 날짜 카드 전부 삭제</li>
 *   <li>AC-Δ7: artifact/ 삭제 후 재렌더 동일 산출(재현성), 고아 카드 정리</li>
 *   <li>AC-67(TΔ6, ADR-55): 저장·--rerender 어느 경로로도 index.html 미생성 — artifact/ 아래 HTML 산출 0건</li>
 *   <li>템플릿 계약: 4:5(1080×1350)·로컬 폰트·회차 파트 1건·note.html 폐기(ADR-54)</li>
 *   <li>changes/0028 TΔ6c: 대상 지정이 노트 id, 카드 폴더가 {@code <id>-<로스터리>-<커피명>} 접미(AC-Δ9)</li>
 * </ul>
 * <p>저장소는 인메모리 fake다 — 렌더 검증의 입력은 "조회 결과"이고 저장 정책(TΔ5b·5c)은 검증 대상이 아니다
 * (모듈 CLAUDE.md §5.2 — 외부 의존은 인터페이스 stub/fake).
 */
class ThymeleafNoteRendererTest {

    private static final OffsetDateTime SAVED_AT = OffsetDateTime.parse("2026-07-10T09:00:00+09:00");

    // 기대 카드 폴더 접미 — <id>-<로스터리>-<커피명>(공백은 '-', TΔ7 NoteFolderName).
    // 리터럴로 박는다: 생성기를 안 지나고 id·slug를 그대로 쓰면 여기서 어긋난다.
    private static final String N1 = "1-커피베라-예가체프-G1-워시드";
    private static final String N1_SHORT = "1-커피베라-예가체프-G1";
    private static final String N2 = "2-프릳츠-콜롬비아-게이샤-워시드";
    private static final String RAINBOW = "3-커피가게-동경-레인보우-블렌드";

    private final SpringTemplateEngine engine = RenderConfig.offlineTemplateEngine();

    // 회차 구조(changes/0021 ADR-59) 픽스처 — 단일 감상(+레시피)을 회차 1개로 담는다.
    private static Entry entry(LocalDate date, String taste, Rating rating, Recipe recipe, OffsetDateTime ts) {
        return new Entry(date, List.of(new Brew(recipe, new Review(taste, null, rating))), ts);
    }

    private static Entry entry(LocalDate date, String taste, String original, Rating rating, Recipe recipe,
                               OffsetDateTime ts) {
        return new Entry(date, List.of(new Brew(recipe, new Review(taste, original, rating))), ts);
    }

    // 실사용 샘플(ideas/sample.md 07-18) 수준의 회차 2개 엔트리 — 시도별 레시피·감상이 갈린다(AC-Δ6).
    private static Entry twoBrewEntry(LocalDate date, OffsetDateTime ts) {
        Brew first = new Brew(
                new Recipe("핸드드립", 15.0, 240.0, null, 160.0, 92.0, "210클릭 (매버릭 2.0)", null,
                        "뜸 40ml 30초 → 100ml → 100ml", "다음엔 분쇄를 더 굵게 갈 것"),
                new Review("첫 시도는 새콤함", null, Rating.GOOD));
        Brew second = new Brew(
                new Recipe(null, 18.0, 250.0, null, null, null, "중간", null, null, null),
                new Review("두 번째는 부드러움", null, Rating.PERFECT));
        return new Entry(date, List.of(first, second), ts);
    }

    // 저장된 노트 — 메타는 그대로 옮기고 id·엔트리만 얹는다(저장소 쓰기 경로를 지나지 않는다).
    private static Note noteOf(long id, NoteMeta meta, Aliases aliases, Entry... entries) {
        return new Note(id, meta.coffeeName(), meta.roastery(), meta.beans(), meta.roastLevel(),
                meta.officialNotes(), aliases, meta.sources(), List.of(entries), SAVED_AT, SAVED_AT);
    }

    // 두 노트(각 엔트리 1건·회차 1개, 감상만). id=1 검색 보강/GOOD(2026-07-10), id=2 PERFECT(2026-07-04).
    private InMemoryNoteService seedRepository() {
        OffsetDateTime now = OffsetDateTime.parse("2026-07-10T09:00:00+09:00");
        NoteMeta meta1 = new NoteMeta(
                new Sourced<>("예가체프 G1 워시드", Source.USER),
                new Sourced<>("커피베라", Source.USER),
                // description = 검색 → 감상 카드는 값만 평문 렌더(출처 표기 없음, NoteView.TasteCard)
                List.of(new Bean(new Sourced<>("에티오피아 예가체프", Source.SEARCH), new Sourced<>("워시드", Source.USER))),
                new Sourced<>("라이트", Source.SEARCH),
                new Sourced<>(List.of("자몽", "베르가못", "홍차"), Source.SEARCH),
                List.of("https://coffeevera.example/yirgacheffe"));
        NoteMeta meta2 = new NoteMeta(
                new Sourced<>("콜롬비아 게이샤 워시드", Source.USER),
                new Sourced<>("프릳츠", Source.USER),
                List.of(new Bean(new Sourced<>("콜롬비아", Source.SEARCH), null)),
                null, new Sourced<>(List.of(), Source.SEARCH), List.of());

        return new InMemoryNoteService()
                .put(noteOf(1, meta1, Aliases.empty(),
                        entry(LocalDate.parse("2026-07-10"), "새콤하고 좋았다.\n다음엔 물 온도를 낮춰봐야지.",
                                Rating.GOOD, null, now)))
                .put(noteOf(2, meta2, Aliases.empty(),
                        entry(LocalDate.parse("2026-07-04"), "화사하다.", Rating.PERFECT, null, now)));
    }

    // --- TΔ5a 핵심: 회차 카드 산출·파일명 규칙 ---

    @Test
    @DisplayName("AC-Δ6/AC-74: 회차 2개(각 레시피+감상) 엔트리 → 카드 4장, <date>-taste-<n>·<date>-recipe-<n> 파일명 규칙")
    void twoBrewEntryBakesFourCardsWithBrewNumberedNames(@TempDir Path artifactDir) {
        OffsetDateTime now = OffsetDateTime.parse("2026-07-18T09:00:00+09:00");
        NoteMeta meta = new NoteMeta(
                new Sourced<>("레인보우 블렌드", Source.USER), new Sourced<>("커피가게 동경", Source.USER),
                List.of(new Bean(new Sourced<>("에티오피아 예가체프", Source.USER), new Sourced<>("워시드", Source.USER))),
                null, new Sourced<>(List.of(), Source.SEARCH), List.of());
        NoteService repo = seedRepository()
                .put(noteOf(3, meta, Aliases.empty(), twoBrewEntry(LocalDate.parse("2026-07-18"), now)));

        FakeCardImageRenderer cards = new FakeCardImageRenderer();
        ThymeleafNoteRenderer renderer = new ThymeleafNoteRenderer(repo, engine, artifactDir, Theme.TYPE_A, cards);

        List<Path> baked = renderer.renderEntryCard(3, LocalDate.parse("2026-07-18"));

        // 반환 순서 = 회차 오름차순, 회차 안에서는 감상 → 레시피(CardFiles.expectedCards 계약).
        assertEquals(List.of(
                        artifactDir.resolve("cards/" + RAINBOW + "/2026-07-18-taste-1.jpg"),
                        artifactDir.resolve("cards/" + RAINBOW + "/2026-07-18-recipe-1.jpg"),
                        artifactDir.resolve("cards/" + RAINBOW + "/2026-07-18-taste-2.jpg"),
                        artifactDir.resolve("cards/" + RAINBOW + "/2026-07-18-recipe-2.jpg")),
                baked, "회차 2개 × (감상+레시피) = 4장, 파일명 n = 회차");
        baked.forEach(p -> assertTrue(Files.isRegularFile(p), "카드 JPG 존재: " + p));
        assertEquals(4, cards.calls.size(), "증분: 그 엔트리의 회차 카드만 굽는다");

        // 각 카드 HTML은 자기 회차 파트만 담는다 — 시도별 감상·피드백이 카드에 갈려 실린다(AC-75).
        String taste1 = capturedHtml(cards, "cards/" + RAINBOW + "/2026-07-18-taste-1.jpg");
        String taste2 = capturedHtml(cards, "cards/" + RAINBOW + "/2026-07-18-taste-2.jpg");
        assertTrue(taste1.contains("첫 시도는 새콤함"), "1회차 감상 카드 = 1회차 감상");
        assertFalse(taste1.contains("두 번째는 부드러움"), "1회차 카드에 2회차 감상 없음");
        assertTrue(taste2.contains("두 번째는 부드러움"), "2회차 감상 카드 = 2회차 감상");
        String recipe1 = capturedHtml(cards, "cards/" + RAINBOW + "/2026-07-18-recipe-1.jpg");
        assertTrue(recipe1.contains("다음엔 분쇄를 더 굵게 갈 것"), "1회차 레시피 카드 = 1회차 피드백");
        assertTrue(recipe1.contains("210클릭"), "1회차 분쇄값 렌더(grind 정규화 값)");
    }

    @Test
    @DisplayName("AC-Δ9(changes/0028): 카드 폴더는 <id>-<로스터리>-<커피명> 접미 — 로스터리가 없으면 세그먼트를 생략한다")
    void cardFolderUsesGeneratedSuffix(@TempDir Path artifactDir) {
        // 로스터리 미상 노트 — 접미가 <id>-<커피명>으로 줄고 빈 세그먼트(`7--첼베사`)가 생기지 않는다(파생 결정 1).
        NoteMeta meta = new NoteMeta(
                new Sourced<>("첼베사 내추럴", Source.USER), null, List.of(),
                null, new Sourced<>(List.of(), Source.SEARCH), List.of());
        NoteService repo = new InMemoryNoteService().put(noteOf(7, meta, Aliases.empty(),
                entry(LocalDate.parse("2026-07-18"), "달다.", Rating.GOOD, null, SAVED_AT)));

        List<Path> baked = new ThymeleafNoteRenderer(repo, engine, artifactDir, Theme.TYPE_A, new FakeCardImageRenderer())
                .renderEntryCard(7, LocalDate.parse("2026-07-18"));

        assertEquals(List.of(artifactDir.resolve("cards/7-첼베사-내추럴/2026-07-18-taste-1.jpg")), baked,
                "접미 = <id>-<커피명>(로스터리 생략), 한글 그대로");
        assertTrue(Files.isRegularFile(baked.getFirst()), "카드 JPG 존재");
    }

    @Test
    @DisplayName("AC-78: 레시피만/감상만 있는 회차는 해당 카드만 생성된다(없는 파트 카드 미생성)")
    void partialBrewsBakeOnlyPresentPartCards(@TempDir Path artifactDir) {
        OffsetDateTime now = OffsetDateTime.parse("2026-07-18T09:00:00+09:00");
        NoteMeta meta = new NoteMeta(
                new Sourced<>("예가체프 G1", Source.USER), new Sourced<>("커피베라", Source.USER),
                List.of(new Bean(new Sourced<>("에티오피아", Source.USER), null)),
                null, new Sourced<>(List.of(), Source.SEARCH), List.of());
        // 1회차 = 레시피만(감상 없음), 2회차 = 감상만(레시피 없음).
        Entry entry = new Entry(LocalDate.parse("2026-07-18"), List.of(
                new Brew(new Recipe(null, 15.0, 240.0, null, null, null, "중간", null, null, null), null),
                new Brew(null, new Review("두 번째 잔이 더 달다", null, Rating.GOOD))), now);
        NoteService repo = new InMemoryNoteService().put(noteOf(1, meta, Aliases.empty(), entry));

        FakeCardImageRenderer cards = new FakeCardImageRenderer();
        List<Path> baked = new ThymeleafNoteRenderer(repo, engine, artifactDir, Theme.TYPE_A, cards)
                .renderEntryCard(1, LocalDate.parse("2026-07-18"));

        assertEquals(List.of(
                        artifactDir.resolve("cards/" + N1_SHORT + "/2026-07-18-recipe-1.jpg"),
                        artifactDir.resolve("cards/" + N1_SHORT + "/2026-07-18-taste-2.jpg")),
                baked, "있는 파트만 — 1회차 레시피 카드 + 2회차 감상 카드");
        assertFalse(Files.exists(artifactDir.resolve("cards/" + N1_SHORT + "/2026-07-18-taste-1.jpg")),
                "감상 없는 1회차의 감상 카드 미생성(AC-78)");
        assertFalse(Files.exists(artifactDir.resolve("cards/" + N1_SHORT + "/2026-07-18-recipe-2.jpg")),
                "레시피 없는 2회차의 레시피 카드 미생성(AC-78)");
        assertEquals(2, cards.calls.size(), "카드 굽기 호출도 있는 파트 수만큼");
    }

    @Test
    @DisplayName("TΔ5a: 회차 감소 재저장 후 renderEntryCard → 옛 번호 카드가 잔존하지 않는다")
    void rerenderAfterBrewShrinkLeavesNoStaleCards(@TempDir Path artifactDir) {
        OffsetDateTime now = OffsetDateTime.parse("2026-07-18T09:00:00+09:00");
        NoteMeta meta = new NoteMeta(
                new Sourced<>("레인보우 블렌드", Source.USER), new Sourced<>("커피가게 동경", Source.USER),
                List.of(new Bean(new Sourced<>("에티오피아", Source.USER), null)),
                null, new Sourced<>(List.of(), Source.SEARCH), List.of());
        LocalDate date = LocalDate.parse("2026-07-18");
        InMemoryNoteService repo = new InMemoryNoteService()
                .put(noteOf(3, meta, Aliases.empty(), twoBrewEntry(date, now)));

        FakeCardImageRenderer cards = new FakeCardImageRenderer();
        ThymeleafNoteRenderer renderer = new ThymeleafNoteRenderer(repo, engine, artifactDir, Theme.TYPE_A, cards);
        renderer.renderEntryCard(3, date);
        assertTrue(Files.exists(artifactDir.resolve("cards/" + RAINBOW + "/2026-07-18-taste-2.jpg")),
                "감소 전 2회차 카드 존재");

        // 수정 커밋으로 회차가 2개 → 1개로 줄었다(brews 통째 교체 — ADR-59 patch 의미론).
        Note origin = repo.findById(3).orElseThrow();
        Entry shrunk = new Entry(date, List.of(origin.entries().getFirst().brews().getFirst()), now);
        repo.put(withOnlyEntry(origin, shrunk));

        List<Path> baked = renderer.renderEntryCard(3, date);

        assertEquals(List.of(
                        artifactDir.resolve("cards/" + RAINBOW + "/2026-07-18-taste-1.jpg"),
                        artifactDir.resolve("cards/" + RAINBOW + "/2026-07-18-recipe-1.jpg")),
                baked, "남은 회차 카드만 산출");
        assertFalse(Files.exists(artifactDir.resolve("cards/" + RAINBOW + "/2026-07-18-taste-2.jpg")),
                "옛 2회차 감상 카드 잔존 없음");
        assertFalse(Files.exists(artifactDir.resolve("cards/" + RAINBOW + "/2026-07-18-recipe-2.jpg")),
                "옛 2회차 레시피 카드 잔존 없음");
    }

    // --- index 폐기(TΔ6, ADR-55)·기본 산출 구조 ---

    @Test
    @DisplayName("AC-67: renderAll(--rerender)이 index.html을 생성하지 않는다 — artifact/ 아래 HTML 산출 0건, 산출은 cards/ JPG뿐")
    void renderAllProducesNoIndexHtml(@TempDir Path artifactDir) {
        NoteService repo = seedRepository();
        FakeCardImageRenderer cards = new FakeCardImageRenderer();
        new ThymeleafNoteRenderer(repo, engine, artifactDir, Theme.TYPE_B, cards).renderAll();

        // artifact/ 아래 HTML 산출 금지(ADR-55 POLICY) — index.html도, 노트 상세 HTML도 없다.
        assertFalse(Files.exists(artifactDir.resolve("index.html")), "index.html 미생성(AC-67)");
        assertFalse(Files.exists(artifactDir.resolve("notes")), "notes/ 디렉터리 미생성");
        assertEquals(java.util.Set.of(), htmlFiles(artifactDir), "artifact/ 아래 HTML 파일 0건");

        // 감상만 있는 회차 1개 엔트리 2건 → 감상 카드 2장(레시피 카드 없음 — AC-78).
        assertTrue(Files.isRegularFile(artifactDir.resolve("cards/" + N1 + "/2026-07-10-taste-1.jpg")), "엔트리1 감상 카드");
        assertTrue(Files.isRegularFile(artifactDir.resolve("cards/" + N2 + "/2026-07-04-taste-1.jpg")), "엔트리2 감상 카드");
        assertEquals(2, cards.calls.size(), "엔트리 2건(감상 회차 1개씩) → 카드 굽기 2회");
        // 카드는 artifact 루트를 base로 굽는다(상대 자원 해석 기준, AC-Δ5).
        assertTrue(cards.calls.stream().allMatch(c -> c.baseDir().equals(artifactDir)), "baseDir = artifact 루트");
    }

    @Test
    @DisplayName("AC-Δ7/E-1(changes/0028): renderAll 산출 순서는 엔트리 date 내림차순 + 노트 id 오름차순으로 결정적이다")
    void renderAllOrdersByDateThenNoteId(@TempDir Path artifactDir) {
        // 같은 날짜 엔트리를 가진 노트 둘 + 더 최근 날짜 노트 하나. 두 노트의 커피명 순서를 id 순서와
        // 일부러 어긋나게 둔다(게이샤 < 히비스커스) — 2차 키가 표기로 새면 여기서 잡힌다.
        NoteService repo = new InMemoryNoteService()
                .put(oneTasteNote(2, "게이샤 워시드", "프릳츠", LocalDate.parse("2026-07-10")))
                .put(oneTasteNote(3, "레인보우 블렌드", "커피가게 동경", LocalDate.parse("2026-07-11")))
                .put(oneTasteNote(1, "히비스커스 블렌드", "커피베라", LocalDate.parse("2026-07-10")));

        FakeCardImageRenderer cards = new FakeCardImageRenderer();
        new ThymeleafNoteRenderer(repo, engine, artifactDir, Theme.TYPE_A, cards).renderAll();

        // 2차 키가 id가 아니면(구 slug 자리에 커피명 등이 들어오면) 이 순서가 어긋난다 — E-1의 승계 지점.
        assertEquals(List.of(
                        artifactDir.resolve("cards/" + RAINBOW + "/2026-07-11-taste-1.jpg"),
                        artifactDir.resolve("cards/1-커피베라-히비스커스-블렌드/2026-07-10-taste-1.jpg"),
                        artifactDir.resolve("cards/2-프릳츠-게이샤-워시드/2026-07-10-taste-1.jpg")),
                cards.calls.stream().map(FakeCardImageRenderer.Call::out).toList(),
                "date 내림차순 → 같은 date는 노트 id 오름차순");
    }

    @Test
    @DisplayName("ADR-54: 감상 카드 HTML이 4:5·로컬 폰트·회차 파트 1건이고 print/A시리즈가 없다")
    void tasteCardHtmlIsFourFiveWithLocalFont(@TempDir Path artifactDir) {
        NoteService repo = seedRepository();
        FakeCardImageRenderer cards = new FakeCardImageRenderer();
        new ThymeleafNoteRenderer(repo, engine, artifactDir, Theme.TYPE_B, cards).renderAll();

        String cardHtml = capturedHtml(cards, "cards/" + N1 + "/2026-07-10-taste-1.jpg");

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
    void tasteCardRendersNormalizedTasteAndHidesOriginal(@TempDir Path artifactDir) {
        OffsetDateTime now = OffsetDateTime.parse("2026-07-10T09:00:00+09:00");
        NoteMeta meta = new NoteMeta(
                new Sourced<>("예가체프 G1", Source.USER),
                new Sourced<>("커피베라", Source.USER), List.of(new Bean(new Sourced<>("에티오피아", Source.SEARCH), null)),
                null, new Sourced<>(List.of(), Source.SEARCH), List.of());
        // 정규화본(음슴체)과 발화 원문을 서로 다른 문자열로 둔다 — 원문 유출을 판별 가능하게.
        NoteService repo = new InMemoryNoteService().put(noteOf(1, meta, Aliases.empty(),
                entry(LocalDate.parse("2026-07-10"), "새콤하고 좋았음", "새콤하고 좋았다구우",
                        Rating.GOOD, null, now)));

        FakeCardImageRenderer cards = new FakeCardImageRenderer();
        new ThymeleafNoteRenderer(repo, engine, artifactDir, Theme.TYPE_B, cards).renderAll();

        String cardHtml = capturedHtml(cards, "cards/" + N1_SHORT + "/2026-07-10-taste-1.jpg");
        assertTrue(cardHtml.contains("새콤하고 좋았음"), "정규화본(my_taste) 렌더");
        assertFalse(cardHtml.contains("새콤하고 좋았다구우"), "원문(my_taste_original)은 카드에 노출 안 됨(V-11)");
    }

    @Test
    @DisplayName("같은 커피를 다른 날 기록하면 엔트리마다 별도 카드가 생기고, 카드 HTML은 자기 날짜 회차만 담는다")
    void sameCoffeeDifferentDatesYieldSeparateCards(@TempDir Path artifactDir) {
        OffsetDateTime now = OffsetDateTime.parse("2026-07-10T09:00:00+09:00");
        NoteMeta meta = new NoteMeta(
                new Sourced<>("예가체프 G1 워시드", Source.USER),
                new Sourced<>("커피베라", Source.USER),
                List.of(new Bean(new Sourced<>("에티오피아 예가체프", Source.SEARCH), new Sourced<>("워시드", Source.USER))),
                new Sourced<>("라이트", Source.SEARCH),
                new Sourced<>(List.of("자몽", "홍차"), Source.SEARCH),
                List.of("https://coffeevera.example/yirgacheffe"));
        // 같은 노트(id)에 다른 날짜 엔트리 2건.
        NoteService repo = new InMemoryNoteService().put(noteOf(1, meta, Aliases.empty(),
                entry(LocalDate.parse("2026-07-04"), "첫날: 새콤하고 좋았다.", Rating.GOOD, null, now),
                entry(LocalDate.parse("2026-07-10"), "둘째 날: 물 온도를 낮추니 부드럽다.", Rating.PERFECT, null, now)));

        FakeCardImageRenderer cards = new FakeCardImageRenderer();
        new ThymeleafNoteRenderer(repo, engine, artifactDir, Theme.TYPE_B, cards).renderAll();

        // 엔트리마다 별도 카드(같은 노트, 다른 date).
        assertTrue(Files.isRegularFile(artifactDir.resolve("cards/" + N1 + "/2026-07-04-taste-1.jpg")), "첫날 카드");
        assertTrue(Files.isRegularFile(artifactDir.resolve("cards/" + N1 + "/2026-07-10-taste-1.jpg")), "둘째 날 카드");

        // 각 카드 HTML은 자기 엔트리(날짜) 회차 감상만 담는다(AC-Δ4).
        String firstCard = capturedHtml(cards, "cards/" + N1 + "/2026-07-04-taste-1.jpg");
        String secondCard = capturedHtml(cards, "cards/" + N1 + "/2026-07-10-taste-1.jpg");
        assertTrue(firstCard.contains("첫날: 새콤하고 좋았다."), "첫날 카드에 첫날 감상");
        assertFalse(firstCard.contains("둘째 날"), "첫날 카드에 둘째 날 감상 없음");
        assertTrue(secondCard.contains("둘째 날: 물 온도를 낮추니 부드럽다."), "둘째 날 카드에 둘째 날 감상");
        assertFalse(secondCard.contains("첫날"), "둘째 날 카드에 첫날 감상 없음");
    }

    @Test
    @DisplayName("AC-Δ2(changes/0014): 사진이 있는 엔트리도 카드 HTML에 사진·썸네일 요소가 없다(아카이브 전용)")
    void cardHasNoPhotoOrThumbElements(@TempDir Path artifactDir) {
        OffsetDateTime now = OffsetDateTime.parse("2026-07-10T09:00:00+09:00");
        NoteMeta meta = new NoteMeta(
                new Sourced<>("예가체프 G1 워시드", Source.USER),
                new Sourced<>("커피베라", Source.USER), List.of(new Bean(new Sourced<>("에티오피아", Source.SEARCH), null)),
                null, new Sourced<>(List.of(), Source.SEARCH), List.of());
        // 렌더는 사진을 읽지 않는다 — 엔트리에 사진 필드가 없고 템플릿에도 사진 슬롯이 없다(changes/0014 ADR-32, AC-Δ2).
        NoteService repo = new InMemoryNoteService().put(noteOf(1, meta, Aliases.empty(),
                entry(LocalDate.parse("2026-07-10"), "새콤하다.", Rating.GOOD, null, now)));

        FakeCardImageRenderer cards = new FakeCardImageRenderer();
        new ThymeleafNoteRenderer(repo, engine, artifactDir, Theme.TYPE_B, cards).renderAll();

        String cardHtml = capturedHtml(cards, "cards/" + N1 + "/2026-07-10-taste-1.jpg");
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
    void aliasesNeverAppearInRenderedOutput(@TempDir Path artA, @TempDir Path artB) {
        OffsetDateTime now = OffsetDateTime.parse("2026-07-10T09:00:00+09:00");
        NoteMeta meta = new NoteMeta(
                new Sourced<>("예가체프 G1 워시드", Source.USER),
                new Sourced<>("커피베라", Source.USER),
                List.of(new Bean(new Sourced<>("에티오피아 예가체프", Source.SEARCH), new Sourced<>("워시드", Source.USER))),
                new Sourced<>("라이트", Source.SEARCH),
                new Sourced<>(List.of("자몽", "홍차"), Source.SEARCH), List.of());
        // 별칭은 표시값(커피명·로스터리)과 명확히 다른 마커 문자열 — 렌더에 새면 반드시 검출된다(V-13).
        Aliases aliases = new Aliases(
                List.of("yirgacheffe-ALIAS-LEAK", "이르가체프이표기마커"),
                List.of("coffeevera-ALIAS-LEAK"));
        Note saved = noteOf(1, meta, aliases,
                entry(LocalDate.parse("2026-07-10"), "새콤하다.", Rating.GOOD, null, now));
        NoteService repo = new InMemoryNoteService().put(saved);
        // 가드 유효성 전제: 별칭이 실제로 노트에 심겼다(빈 별칭이면 가드가 무의미해진다).
        assertFalse(saved.aliases().coffeeName().isEmpty(), "별칭이 노트에 심겼다(가드 유효성 전제)");

        List<String> markers = List.of("yirgacheffe-ALIAS-LEAK", "이르가체프이표기마커", "coffeevera-ALIAS-LEAK");
        // 두 테마 템플릿 모두 별칭을 참조할 슬롯이 없다(구조적 불변) — 회귀 가드로 두 산출을 확인.
        for (Theme theme : new Theme[]{Theme.TYPE_A, Theme.TYPE_B}) {
            Path artifactDir = theme == Theme.TYPE_A ? artA : artB;
            FakeCardImageRenderer cards = new FakeCardImageRenderer();
            new ThymeleafNoteRenderer(repo, engine, artifactDir, theme, cards).renderAll();

            String cardHtml = capturedHtml(cards, "cards/" + N1 + "/2026-07-10-taste-1.jpg");
            for (String marker : markers) {
                assertFalse(cardHtml.contains(marker), theme + " 카드에 별칭 미출현(V-13): " + marker);
            }
            // 별칭만 빠진 것이지 노트 자체가 안 나오는 게 아니다 — 표시값은 정상 렌더.
            assertTrue(cardHtml.contains("예가체프 G1 워시드"), theme + ": 커피명 표시값은 정상 렌더");
        }
    }

    @Test
    @DisplayName("AC-Δ3(changes/0014): data/photos/를 통째로 옮겨둬도 renderAll 산출(카드)이 동일하다 — 리렌더 입력은 저장소뿐")
    void renderAllIsIndependentOfPhotosDirectory(
            @TempDir Path dataDir, @TempDir Path withPhotosDir, @TempDir Path withoutPhotosDir) throws IOException {
        OffsetDateTime now = OffsetDateTime.parse("2026-07-10T09:00:00+09:00");
        NoteMeta meta = new NoteMeta(
                new Sourced<>("예가체프 G1 워시드", Source.USER),
                new Sourced<>("커피베라", Source.USER), List.of(new Bean(new Sourced<>("에티오피아", Source.SEARCH), null)),
                null, new Sourced<>(List.of(), Source.SEARCH), List.of());
        // 엔트리 + 실제 사진 파일을 data/photos/에 둔다 — 사진은 노트가 아닌 폴더에만 존재(리렌더 입력 후보로서의 파일 존재를 재현).
        NoteService repo = new InMemoryNoteService().put(noteOf(1, meta, Aliases.empty(),
                entry(LocalDate.parse("2026-07-10"), "새콤하다.", Rating.GOOD, null, now)));
        Path photosDir = dataDir.resolve("photos/" + N1 + "/2026-07-10");
        Files.createDirectories(photosDir);
        Files.write(photosDir.resolve("a.jpg"), new byte[]{1, 2, 3});

        // (1) 사진 폴더가 있는 상태로 렌더.
        FakeCardImageRenderer cardsWith = new FakeCardImageRenderer();
        new ThymeleafNoteRenderer(repo, engine, withPhotosDir, Theme.TYPE_B, cardsWith).renderAll();
        String cardWith = capturedHtml(cardsWith, "cards/" + N1 + "/2026-07-10-taste-1.jpg");

        // data/photos/를 통째로 옮겨둔다(삭제로 갈음) — 리렌더 입력에서 사진이 사라진 상태.
        deleteRecursively(dataDir.resolve("photos"));
        assertFalse(Files.exists(dataDir.resolve("photos")), "photos/ 옮겨둠");

        // (2) 사진 폴더가 없는 상태로 재렌더 — 저장소만으로 카드가 생성돼야 한다(NFR-3, AC-6).
        FakeCardImageRenderer cardsWithout = new FakeCardImageRenderer();
        new ThymeleafNoteRenderer(repo, engine, withoutPhotosDir, Theme.TYPE_B, cardsWithout).renderAll();
        assertTrue(Files.isRegularFile(withoutPhotosDir.resolve("cards/" + N1 + "/2026-07-10-taste-1.jpg")),
                "사진 없이도 카드 생성");
        String cardWithout = capturedHtml(cardsWithout, "cards/" + N1 + "/2026-07-10-taste-1.jpg");

        // 산출 동일 — 렌더는 저장소만 읽고 data/photos/ 존재 여부에 의존하지 않는다(AC-Δ3).
        assertEquals(cardWith, cardWithout, "카드 HTML이 사진 폴더 유무와 무관하게 동일");
        assertEquals(cardFiles(withPhotosDir), cardFiles(withoutPhotosDir), "카드 파일 집합 동일");
    }

    @Test
    @DisplayName("AC-Δ7/AC-6(개정형): artifact/ 삭제 후 재렌더하면 저장소만으로 전체 회차 카드가 동일하게 복원된다")
    void reRenderIsReproducible(@TempDir Path artifactDir) {
        NoteService repo = seedRepository();

        FakeCardImageRenderer cards1 = new FakeCardImageRenderer();
        ThymeleafNoteRenderer renderer1 = new ThymeleafNoteRenderer(repo, engine, artifactDir, Theme.TYPE_A, cards1);
        renderer1.renderAll();
        java.util.Set<String> cardFilesFirst = cardFiles(artifactDir);
        String cardFirst = capturedHtml(cards1, "cards/" + N1 + "/2026-07-10-taste-1.jpg");

        deleteRecursively(artifactDir);
        assertFalse(Files.exists(artifactDir.resolve("cards")), "artifact/ 비워짐");

        FakeCardImageRenderer cards2 = new FakeCardImageRenderer();
        new ThymeleafNoteRenderer(repo, engine, artifactDir, Theme.TYPE_A, cards2).renderAll();
        assertEquals(cardFilesFirst, cardFiles(artifactDir), "카드 파일 집합 재현성");
        assertEquals(cardFirst, capturedHtml(cards2, "cards/" + N1 + "/2026-07-10-taste-1.jpg"), "카드 HTML 재현성");
        assertEquals(cards1.calls.size(), cards2.calls.size(), "카드 굽기 횟수 재현성");
    }

    @Test
    @DisplayName("AC-Δ7(증분)/AC-67: renderEntryCard는 대상 엔트리 카드만 새로 굽고 경로 목록을 반환하며 index.html을 만들지 않는다")
    void renderEntryCardBakesEntryCardsAndReturnsPaths(@TempDir Path artifactDir) {
        NoteService repo = seedRepository();
        FakeCardImageRenderer cards = new FakeCardImageRenderer();
        ThymeleafNoteRenderer renderer = new ThymeleafNoteRenderer(repo, engine, artifactDir, Theme.TYPE_B, cards);

        List<Path> baked = renderer.renderEntryCard(1, LocalDate.parse("2026-07-10"));

        assertEquals(List.of(artifactDir.resolve("cards/" + N1 + "/2026-07-10-taste-1.jpg")), baked,
                "반환 목록 = 그 엔트리 회차 카드(감상 회차 1개 → 1장)");
        assertTrue(Files.isRegularFile(baked.getFirst()), "카드 JPG 존재");
        assertEquals(1, cards.calls.size(), "증분: 대상 엔트리 카드만 굽는다(전체 재래스터화 없음)");
        // 저장([저장] 커밋 → 증분 렌더) 경로에서도 HTML 산출이 없다(AC-67).
        assertFalse(Files.exists(artifactDir.resolve("index.html")), "index.html 미생성(AC-67)");
        assertEquals(java.util.Set.of(), htmlFiles(artifactDir), "artifact/ 아래 HTML 파일 0건");
    }

    @Test
    @DisplayName("ADR-54: mocha.artifact.theme(type-a/type-b)로 카드 디자인이 갈린다")
    void themeSelectsCardDesign(@TempDir Path artifactA, @TempDir Path artifactB) {
        NoteService repo = seedRepository();
        FakeCardImageRenderer cardsA = new FakeCardImageRenderer();
        FakeCardImageRenderer cardsB = new FakeCardImageRenderer();
        new ThymeleafNoteRenderer(repo, engine, artifactA, Theme.TYPE_A, cardsA).renderAll();
        new ThymeleafNoteRenderer(repo, engine, artifactB, Theme.TYPE_B, cardsB).renderAll();

        String serifCard = capturedHtml(cardsA, "cards/" + N1 + "/2026-07-10-taste-1.jpg");
        String cuteCard = capturedHtml(cardsB, "cards/" + N1 + "/2026-07-10-taste-1.jpg");
        assertTrue(serifCard.contains("Gowun Batang") && serifCard.contains("COFFEE NOTE"), "type-a=세리프");
        assertTrue(cuteCard.contains("Gowun Dodum") && cuteCard.contains("mascot-face.png"), "type-b=귀여운(마스코트)");
        // 공통: 두 테마 모두 4:5 + 감상 영역, 이모티콘 없는 rating 뱃지(ADR-54 편차①).
        assertTrue(serifCard.contains("1080px") && cuteCard.contains("1080px"), "두 테마 4:5");
        assertTrue(serifCard.contains("내가 느끼길") && cuteCard.contains("내가 느끼길"), "감상 영역");
        assertFalse(serifCard.contains("😋") || cuteCard.contains("😋"), "rating 뱃지 이모티콘 없음");
    }

    // --- 레시피 카드(회차 파트) 생성 분기 ---

    // recipe·coffeeName만 갈아끼우는 단일 엔트리(회차 1개: 감상+recipe) 노트 seed. 나머지 메타는 고정.
    private NoteService seedWithRecipe(Sourced<String> coffeeName, Recipe recipe) {
        OffsetDateTime now = OffsetDateTime.parse("2026-07-10T09:00:00+09:00");
        NoteMeta meta = new NoteMeta(
                coffeeName,
                new Sourced<>("커피베라", Source.USER),
                List.of(new Bean(new Sourced<>("에티오피아 예가체프", Source.SEARCH), new Sourced<>("워시드", Source.USER))),
                new Sourced<>("라이트", Source.SEARCH),
                new Sourced<>(List.of("자몽", "홍차"), Source.SEARCH), List.of());
        return new InMemoryNoteService().put(noteOf(1, meta, Aliases.empty(),
                entry(LocalDate.parse("2026-07-10"), "새콤하다.", Rating.GOOD, recipe, now)));
    }

    private FakeCardImageRenderer renderAllWith(NoteService repo, Theme theme, Path artifactDir) {
        FakeCardImageRenderer cards = new FakeCardImageRenderer();
        new ThymeleafNoteRenderer(repo, engine, artifactDir, theme, cards).renderAll();
        return cards;
    }

    @Test
    @DisplayName("ADR-54·59: recipe 있는 회차는 두 테마 모두 레시피 카드가 별도로 구워지고 수치가 렌더된다")
    void recipeBrewBakesRecipeCard(@TempDir Path artA, @TempDir Path artB) {
        Recipe recipe = new Recipe(null, 15.0, 240.0, null, null, null, "중간", null, null, null);
        for (Theme theme : new Theme[]{Theme.TYPE_A, Theme.TYPE_B}) {
            NoteService repo = seedWithRecipe(new Sourced<>("예가체프 G1 워시드", Source.USER), recipe);
            Path artifactDir = theme == Theme.TYPE_A ? artA : artB;
            FakeCardImageRenderer cards = renderAllWith(repo, theme, artifactDir);

            assertTrue(Files.isRegularFile(artifactDir.resolve("cards/" + N1 + "/2026-07-10-recipe-1.jpg")),
                    theme + ": 레시피 카드 별도 산출");
            String recipeCard = capturedHtml(cards, "cards/" + N1 + "/2026-07-10-recipe-1.jpg");
            assertTrue(recipeCard.contains("15g"), theme + ": 원두 15g(소수점 없이)");
            assertTrue(recipeCard.contains("240ml"), theme + ": 물 240ml");
            assertTrue(recipeCard.contains("중간"), theme + ": 분쇄도 중간");
            assertTrue(recipeCard.contains("1080px") && recipeCard.contains("1350px"), theme + ": 레시피 카드도 4:5");
            // 감상 카드에는 레시피 영역이 없다 — 레시피는 레시피 카드로 완전 이관(ADR-54).
            String tasteCard = capturedHtml(cards, "cards/" + N1 + "/2026-07-10-taste-1.jpg");
            assertFalse(tasteCard.contains("240ml"), theme + ": 감상 카드에 레시피 수치 없음");
        }
    }

    @Test
    @DisplayName("changes/0025 리뷰 후속: 반올림 0초 timeSec은 두 테마 모두 시간 타일·행이 생략된다('총 null' 누수 차단)")
    void recipeCardOmitsUnrenderableTime(@TempDir Path artA, @TempDir Path artB) {
        // 0.3초는 V-8(양수 유한)을 통과해 저장까지 오지만 amt.time이 null — 템플릿 가드가 표기 결과 기준이어야 한다.
        Recipe recipe = new Recipe(null, 15.0, 240.0, null, 0.3, null, null, null, "뜸 40ml 30초 → 200ml", null);
        for (Theme theme : new Theme[]{Theme.TYPE_A, Theme.TYPE_B}) {
            NoteService repo = seedWithRecipe(new Sourced<>("예가체프 G1 워시드", Source.USER), recipe);
            Path artifactDir = theme == Theme.TYPE_A ? artA : artB;
            FakeCardImageRenderer cards = renderAllWith(repo, theme, artifactDir);

            String recipeCard = capturedHtml(cards, "cards/" + N1 + "/2026-07-10-recipe-1.jpg");
            assertTrue(recipeCard.contains("뜸 40ml 30초"), theme + ": 푸어링 행은 유지");
            assertFalse(recipeCard.contains("총 null"), theme + ": null 문자열 병기 누수 없음");
            assertFalse(recipeCard.contains("추출 시간"), theme + ": 표기 불가 시간의 타일·행 생략");
        }
    }

    @Test
    @DisplayName("AC-78: recipe가 null인 회차는 레시피 카드가 생성되지 않는다(감상 카드만)")
    void recipeCardOmittedWhenRecipeAbsent(@TempDir Path artA, @TempDir Path artB) {
        for (Theme theme : new Theme[]{Theme.TYPE_A, Theme.TYPE_B}) {
            NoteService repo = seedWithRecipe(new Sourced<>("예가체프 G1 워시드", Source.USER), null);
            Path artifactDir = theme == Theme.TYPE_A ? artA : artB;
            FakeCardImageRenderer cards = renderAllWith(repo, theme, artifactDir);

            assertTrue(Files.isRegularFile(artifactDir.resolve("cards/" + N1 + "/2026-07-10-taste-1.jpg")),
                    theme + ": 감상 카드는 산출");
            assertFalse(Files.exists(artifactDir.resolve("cards/" + N1 + "/2026-07-10-recipe-1.jpg")),
                    theme + ": 레시피 없는 회차의 레시피 카드 미생성");
            assertEquals(1, cards.calls.size(), theme + ": 굽기 호출도 1회뿐");
        }
    }

    @Test
    @DisplayName("FR-7: recipe가 일부만 있으면(원두만) 레시피 카드에 있는 항목만 표시한다")
    void recipeCardRendersOnlyPresentItems(@TempDir Path artA, @TempDir Path artB) {
        Recipe partial = Recipe.normalize(new Recipe(null, 15.0, null, null, null, null, null, null, null, null)); // 원두만
        for (Theme theme : new Theme[]{Theme.TYPE_A, Theme.TYPE_B}) {
            NoteService repo = seedWithRecipe(new Sourced<>("예가체프 G1 워시드", Source.USER), partial);
            Path artifactDir = theme == Theme.TYPE_A ? artA : artB;
            FakeCardImageRenderer cards = renderAllWith(repo, theme, artifactDir);

            String recipeCard = capturedHtml(cards, "cards/" + N1 + "/2026-07-10-recipe-1.jpg");
            assertTrue(recipeCard.contains("15g"), theme + ": 원두 표시");
            assertFalse(recipeCard.contains("240ml"), theme + ": 물 항목 미출력");
        }
    }

    @Test
    @DisplayName("NoteView.TasteCard: coffeeName source가 photo여도 카드 제목은 값만 쓰고 (사진) 표기를 달지 않는다")
    void cardTitleHasNoPhotoTag(@TempDir Path artA, @TempDir Path artB) {
        for (Theme theme : new Theme[]{Theme.TYPE_A, Theme.TYPE_B}) {
            NoteService repo = seedWithRecipe(new Sourced<>("게이샤 내추럴", Source.PHOTO), null);
            Path artifactDir = theme == Theme.TYPE_A ? artA : artB;
            FakeCardImageRenderer cards = renderAllWith(repo, theme, artifactDir);
            String card = capturedHtml(cards, "cards/1-커피베라-게이샤-내추럴/2026-07-10-taste-1.jpg");
            assertTrue(card.contains("게이샤 내추럴"), theme + ": 제목에 커피명 값");
            assertFalse(card.contains("(사진)"), theme + ": 제목=정체성 → (사진) 무표기");
        }
    }

    // --- 온디맨드 카드 + renderAll 고아 카드 정리(TΔ9·AC-Δ7) ---

    // 감상 회차 1개짜리 엔트리 1건 노트 — 정렬·고아 정리 픽스처.
    private static Note oneTasteNote(long id, String coffeeName, String roastery, LocalDate date) {
        NoteMeta meta = new NoteMeta(
                new Sourced<>(coffeeName, Source.USER), new Sourced<>(roastery, Source.USER),
                List.of(new Bean(new Sourced<>("에티오피아", Source.SEARCH), null)),
                null, new Sourced<>(List.of(), Source.SEARCH), List.of());
        return noteOf(id, meta, Aliases.empty(), entry(date, "새콤하다.", Rating.GOOD, null, SAVED_AT));
    }

    // 원본 노트의 단일 엔트리를 replacement로 바꾼 노트 — 수정 커밋 결과의 대역.
    private static Note withOnlyEntry(Note origin, Entry replacement) {
        return new Note(origin.id(), origin.coffeeName(), origin.roastery(), origin.beans(),
                origin.roastLevel(), origin.officialNotes(), origin.sources(),
                List.of(replacement), origin.createdAt(), origin.updatedAt());
    }

    private static Note movedDate(Note origin, LocalDate newDate) {
        Entry e = origin.entries().get(0);
        return withOnlyEntry(origin, new Entry(newDate, e.brews(), e.updatedAt()));
    }

    @Test
    @DisplayName("TΔ9: 캐시 미스는 그 엔트리의 카드를 전부 굽고 요청한 한 장을 돌려준다(브라우저 기동 > 카드 장수)")
    void onDemandCacheMissBakesWholeEntry(@TempDir Path artifactDir) {
        OffsetDateTime now = OffsetDateTime.parse("2026-07-18T09:00:00+09:00");
        NoteMeta meta = new NoteMeta(
                new Sourced<>("레인보우 블렌드", Source.USER), new Sourced<>("커피가게 동경", Source.USER),
                List.of(new Bean(new Sourced<>("에티오피아", Source.USER), null)),
                null, new Sourced<>(List.of(), Source.SEARCH), List.of());
        LocalDate date = LocalDate.parse("2026-07-18");
        NoteService repo = new InMemoryNoteService().put(noteOf(3, meta, Aliases.empty(), twoBrewEntry(date, now)));

        FakeCardImageRenderer cards = new FakeCardImageRenderer();
        ThymeleafNoteRenderer renderer =
                new ThymeleafNoteRenderer(repo, engine, artifactDir, Theme.TYPE_A, cards);

        Optional<Path> card = renderer.entryCard(3, date, CardType.RECIPE, 2);

        assertEquals(Optional.of(artifactDir.resolve("cards/" + RAINBOW + "/2026-07-18-recipe-2.jpg")), card,
                "요청한 종류·회차의 카드를 돌려준다");
        assertTrue(Files.isRegularFile(card.orElseThrow()), "카드 JPG 존재");
        assertEquals(4, cards.calls.size(), "미스는 엔트리 단위로 채운다 — 회차 2개 × (감상+레시피)");
    }

    @Test
    @DisplayName("TΔ9: 캐시 히트는 굽지 않는다 — 같은 카드를 다시 요청하면 렌더 호출이 늘지 않는다")
    void onDemandCacheHitDoesNotBake(@TempDir Path artifactDir) {
        NoteService repo = seedRepository();
        FakeCardImageRenderer cards = new FakeCardImageRenderer();
        ThymeleafNoteRenderer renderer =
                new ThymeleafNoteRenderer(repo, engine, artifactDir, Theme.TYPE_B, cards);
        LocalDate date = LocalDate.parse("2026-07-10");

        Optional<Path> first = renderer.entryCard(1, date, CardType.TASTE, 1);
        int afterMiss = cards.calls.size();
        Optional<Path> second = renderer.entryCard(1, date, CardType.TASTE, 1);

        assertEquals(1, afterMiss, "첫 요청은 굽는다(감상 1회차뿐인 엔트리)");
        assertEquals(first, second, "같은 경로");
        assertEquals(afterMiss, cards.calls.size(), "두 번째 요청은 캐시에서 답한다 — 렌더 호출 무증가");
    }

    @Test
    @DisplayName("AC-78/TΔ9: 없는 파트·회차·엔트리·노트는 빈 결과다 — 굽지도 않는다(오류가 아니라 없는 자원)")
    void onDemandReturnsEmptyForAbsentTargets(@TempDir Path artifactDir) {
        NoteService repo = seedRepository(); // 노트 1 = 07-10, 감상만 있는 회차 1개
        FakeCardImageRenderer cards = new FakeCardImageRenderer();
        ThymeleafNoteRenderer renderer =
                new ThymeleafNoteRenderer(repo, engine, artifactDir, Theme.TYPE_B, cards);
        LocalDate date = LocalDate.parse("2026-07-10");

        assertTrue(renderer.entryCard(1, date, CardType.RECIPE, 1).isEmpty(), "레시피 없는 회차의 레시피 카드");
        assertTrue(renderer.entryCard(1, date, CardType.TASTE, 2).isEmpty(), "없는 회차");
        assertTrue(renderer.entryCard(1, date, CardType.TASTE, 0).isEmpty(), "회차는 1부터다");
        assertTrue(renderer.entryCard(1, LocalDate.parse("2026-07-11"), CardType.TASTE, 1).isEmpty(), "없는 엔트리");
        assertTrue(renderer.entryCard(999, date, CardType.TASTE, 1).isEmpty(), "없는 노트");
        assertTrue(cards.calls.isEmpty(), "없는 대상에 브라우저를 띄우지 않는다");
    }

    @Test
    @DisplayName("AC-Δ7: 캐시 무효화 실패로 남은 고아 카드는 renderAll이 정리해 산출이 신규 렌더와 동일해진다")
    void renderAllPrunesOrphanCardsForReproducibility(@TempDir Path artifactDir, @TempDir Path freshDir) {
        InMemoryNoteService repo = seedRepository();
        ThymeleafNoteRenderer renderer =
                new ThymeleafNoteRenderer(repo, engine, artifactDir, Theme.TYPE_B, new FakeCardImageRenderer());
        renderer.renderAll();

        // 날짜 이동 후 캐시 무효화(NoteService)가 실패했다고 치자 — 옛 카드가 고아로 남는다(plan §7 실패 모드).
        repo.put(movedDate(repo.findById(1).orElseThrow(), LocalDate.parse("2026-07-11")));
        renderer.renderEntryCard(1, LocalDate.parse("2026-07-11"));
        assertTrue(Files.isRegularFile(artifactDir.resolve("cards/" + N1 + "/2026-07-10-taste-1.jpg")), "고아 카드가 남아 있다");

        renderer.renderAll(); // --rerender

        assertFalse(Files.exists(artifactDir.resolve("cards/" + N1 + "/2026-07-10-taste-1.jpg")), "renderAll이 고아 카드를 정리(plan §7)");
        // 재현 동일성(AC-Δ7): 같은 저장소를 빈 디렉토리에 새로 렌더한 산출과 카드 집합이 동일하다.
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

    /**
     * 렌더러 입력 전용 service fake — 렌더 검증의 입력은 "조회 결과"이고 저장 정책(ADR-4·59, V-9·V-10)은
     * 검증 대상이 아니다. 픽스처는 {@link #put}으로 완성된 노트를 직접 심는다.
     * <p>0029 TΔ4a: 렌더러가 저장소 포트 대신 {@link NoteService}를 잡게 되며 seam도 여기로 옮겼다.
     * 협력자를 null로 세운 것은 의도다 — 조회 외 경로를 건드리면 NPE로 즉시 드러난다.
     */
    private static final class InMemoryNoteService extends NoteService {
        private final Map<Long, Note> notes = new LinkedHashMap<>();

        InMemoryNoteService() {
            super(null, null, null, null);
        }

        InMemoryNoteService put(Note note) {
            notes.put(note.id(), note);
            return this;
        }

        @Override
        public List<Note> findAll() {
            // 저장소 계약 = id 오름차순(결정적 순서). 삽입 순서로 돌려주면 렌더러 정렬 검증이 무의미해진다.
            return notes.values().stream().sorted(Comparator.comparing(Note::id)).toList();
        }

        @Override
        public Optional<Note> findById(long id) {
            return Optional.ofNullable(notes.get(id));
        }
    }
}
