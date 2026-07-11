package com.devwuu.mocha.render;

import com.devwuu.mocha.config.RenderConfig;
import com.devwuu.mocha.domain.Entry;
import com.devwuu.mocha.domain.NoteMeta;
import com.devwuu.mocha.domain.Rating;
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
 * T5-1: SiteRenderer 골격 검증. JSON 셋(@TempDir 실 파일 I/O, CLAUDE.md §5.2·5.4) → site/ 산출 파일 존재·
 * 링크 상대성(AC-11), site/ 삭제 후 재렌더 동일 산출(AC-6), 테마 선택(type-a/type-b) 분기를 본다.
 * <p>렌더러는 JSON 외 상태를 읽지 않으므로(ADR-1) 저장소만 채우면 결정론적으로 검증된다.
 */
class ThymeleafSiteRendererTest {

    private final SpringTemplateEngine engine = RenderConfig.offlineTemplateEngine();

    // 두 노트를 저장소에 심는다. note1=검색 보강 필드/GOOD, note2=PERFECT. 최근 기록일 내림차순은 note1→note2.
    private NoteRepository seedRepository(Path dataDir) {
        NoteRepository repo = new JsonFileNoteRepository(dataDir, MochaObjectMapper.create());
        OffsetDateTime now = OffsetDateTime.parse("2026-07-10T09:00:00+09:00");
        NoteMeta meta1 = new NoteMeta(
                "예가체프 G1 워시드",
                Sourced.user("커피베라"),
                Sourced.search("에티오피아 예가체프"),   // origin = 검색 → (검색) 표기 대상(AC-2)
                Sourced.user("워시드"),
                Sourced.search("라이트"),
                Sourced.search(List.of("자몽", "베르가못", "홍차")),
                List.of("https://coffeevera.example/yirgacheffe"));
        repo.upsertEntry("2026-07-10", meta1,
                new Entry(LocalDate.parse("2026-07-10"), "새콤하고 좋았다.\n다음엔 물 온도를 낮춰봐야지.", Rating.GOOD, List.of(), now));

        NoteMeta meta2 = new NoteMeta(
                "콜롬비아 게이샤 워시드",
                Sourced.user("프릳츠"),
                Sourced.search("콜롬비아"),
                null, null, Sourced.search(List.of()), List.of());
        repo.upsertEntry("2026-07-04", meta2,
                new Entry(LocalDate.parse("2026-07-04"), "화사하다.", Rating.PERFECT, List.of(), now));
        return repo;
    }

    @Test
    @DisplayName("AC-11: 노트/인덱스 파일이 생기고 내부 링크·이미지가 상대 경로다")
    void rendersRelativeLinkedPages(@TempDir Path dataDir, @TempDir Path siteDir) {
        NoteRepository repo = seedRepository(dataDir);
        new ThymeleafSiteRenderer(repo, engine, siteDir, Theme.TYPE_B).renderAll();

        Path index = siteDir.resolve("index.html");
        Path note1 = siteDir.resolve("notes/2026-07-10.html");
        Path note2 = siteDir.resolve("notes/2026-07-04.html");
        assertTrue(Files.isRegularFile(index), "index.html 생성");
        assertTrue(Files.isRegularFile(note1), "notes/2026-07-10.html 생성");
        assertTrue(Files.isRegularFile(note2), "notes/2026-07-04.html 생성");
        assertTrue(Files.isRegularFile(siteDir.resolve("mascot-face.png")), "마스코트 자산 복사");

        String indexHtml = read(index);
        // 인덱스 → 노트 링크는 상대(notes/<slug>.html), 내부 자원에 절대 경로/ file:// 없음(AC-11).
        assertTrue(indexHtml.contains("href=\"notes/2026-07-10.html\""), "상대 노트 링크");
        assertTrue(indexHtml.contains("href=\"notes/2026-07-04.html\""), "상대 노트 링크");
        assertFalse(indexHtml.contains("file:"), "file:// 없음");
        assertFalse(indexHtml.contains(siteDir.toString()), "절대 경로 유출 없음");
        assertTrue(indexHtml.contains("원두 2개 · 기록 2번"), "집계 표시");

        String noteHtml = read(note1);
        // 노트 → 인덱스·자산은 상위 상대 경로(../).
        assertTrue(noteHtml.contains("href=\"../index.html\""), "상대 인덱스 링크");
        assertTrue(noteHtml.contains("../mascot-face.png"), "상대 마스코트 참조");
        assertTrue(noteHtml.contains("예가체프 G1 워시드"), "커피명");
        assertTrue(noteHtml.contains("에티오피아 예가체프"), "보강 원산지");
        assertTrue(noteHtml.contains("새콤하고 좋았다"), "감상");
        // AC-2 재료: 검색 보강 필드(origin)는 (검색) 표기, 사용자 필드(process=워시드)는 미표기.
        assertTrue(noteHtml.contains("·검색"), "검색 필드 (검색) 표기");
    }

    @Test
    @DisplayName("T5-2/FR-7/AC-13: 노트 상세가 official_notes·my_taste 2단과 날짜 엔트리 타임라인(시간순)·출처 링크를 표시한다")
    void notePageShowsTwoSectionsAndEntryTimeline(@TempDir Path dataDir, @TempDir Path siteDir) {
        NoteRepository repo = new JsonFileNoteRepository(dataDir, MochaObjectMapper.create());
        OffsetDateTime now = OffsetDateTime.parse("2026-07-10T09:00:00+09:00");
        NoteMeta meta = new NoteMeta(
                "예가체프 G1 워시드",
                Sourced.user("커피베라"),
                Sourced.search("에티오피아 예가체프"),
                Sourced.user("워시드"),
                Sourced.search("라이트"),
                Sourced.search(List.of("자몽", "베르가못", "홍차")),
                List.of("https://coffeevera.example/yirgacheffe"));
        // 같은 노트(slug)에 다른 날짜 엔트리 2건 — 저장 순서는 뒤죽박죽이지만 렌더는 시간순이어야 한다(AC-13).
        repo.upsertEntry("2026-07-04", meta,
                new Entry(LocalDate.parse("2026-07-10"), "둘째 날: 물 온도를 낮추니 부드럽다.", Rating.PERFECT, List.of(), now));
        repo.upsertEntry("2026-07-04", meta,
                new Entry(LocalDate.parse("2026-07-04"), "첫날: 새콤하고 좋았다.", Rating.GOOD, List.of(), now));

        new ThymeleafSiteRenderer(repo, engine, siteDir, Theme.TYPE_B).renderAll();
        String noteHtml = read(siteDir.resolve("notes/2026-07-04.html"));

        // FR-7: 두 영역이 모두 있다.
        assertTrue(noteHtml.contains("로스터리가 말하길"), "official_notes 영역");
        assertTrue(noteHtml.contains("내가 느끼길"), "my_taste 영역");

        // AC-13: 두 엔트리가 모두 나오고, 앞선 날짜(07-04)가 뒤 날짜(07-10)보다 먼저 나타난다(시간순).
        assertTrue(noteHtml.contains("첫날: 새콤하고 좋았다."), "첫 엔트리 감상");
        assertTrue(noteHtml.contains("둘째 날: 물 온도를 낮추니 부드럽다."), "둘째 엔트리 감상");
        assertTrue(noteHtml.indexOf("첫날") < noteHtml.indexOf("둘째 날"), "엔트리 시간순(오래된 것 먼저)");
        // 각 엔트리가 제 날짜·rating을 표시한다(4범주, FR-11).
        assertTrue(noteHtml.contains("맛있다") && noteHtml.contains("완전 내스타일"), "엔트리별 rating 표시");

        // FR-12: 검색 참조 링크(sources)가 출처로 노출된다.
        assertTrue(noteHtml.contains("https://coffeevera.example/yirgacheffe"), "출처 링크");
    }

    @Test
    @DisplayName("T5-4/FR-8: 인덱스가 노트 N건→카드 N개, 대표 썸네일·기록 수·노트 상대 링크를 표시한다")
    void indexRendersThumbnailCards(@TempDir Path dataDir, @TempDir Path siteDir) {
        NoteRepository repo = new JsonFileNoteRepository(dataDir, MochaObjectMapper.create());
        OffsetDateTime now = OffsetDateTime.parse("2026-07-10T09:00:00+09:00");

        NoteMeta metaA = new NoteMeta(
                "예가체프 G1 워시드",
                Sourced.user("커피베라"), Sourced.search("에티오피아"),
                null, null, Sourced.search(List.of()), List.of());
        // 노트 A: 단일 엔트리 + 사진 1장 → 대표 썸네일은 그 사진.
        repo.upsertEntry("2026-07-10", metaA,
                new Entry(LocalDate.parse("2026-07-10"), "새콤하다.", Rating.GOOD,
                        List.of("photos/2026-07-10/2026-07-10/a.jpg"), now));

        NoteMeta metaB = new NoteMeta(
                "콜롬비아 게이샤 워시드",
                Sourced.user("프릳츠"), Sourced.search("콜롬비아"),
                null, null, Sourced.search(List.of()), List.of());
        // 노트 B: 이전 엔트리에만 사진이 있고 최근 엔트리는 무사진 → 대표 썸네일은 이전 엔트리 사진으로 폴백(FR-8).
        repo.upsertEntry("2026-07-04", metaB,
                new Entry(LocalDate.parse("2026-07-04"), "첫날.", Rating.GOOD,
                        List.of("photos/2026-07-04/2026-07-04/b.jpg"), now));
        repo.upsertEntry("2026-07-04", metaB,
                new Entry(LocalDate.parse("2026-07-05"), "둘째 날.", Rating.PERFECT, List.of(), now));

        new ThymeleafSiteRenderer(repo, engine, siteDir, Theme.TYPE_B).renderAll();
        String indexHtml = read(siteDir.resolve("index.html"));

        // 노트 2건 → 카드 2개.
        assertEquals(2, countOccurrences(indexHtml, "class=\"row\""), "노트 N건 → 카드 N개");

        // 대표 썸네일: A는 자기 사진, B는 최근 엔트리 무사진이므로 이전 엔트리 사진으로 폴백(썸네일 상대 경로).
        assertTrue(indexHtml.contains("thumbs/2026-07-10/2026-07-10/a.jpg"), "노트 A 대표 썸네일");
        assertTrue(indexHtml.contains("thumbs/2026-07-04/2026-07-04/b.jpg"), "노트 B 대표 썸네일 폴백");

        // 기록 수 표시(A=1번, B=2번).
        assertTrue(indexHtml.contains("기록 1번"), "노트 A 기록 수");
        assertTrue(indexHtml.contains("기록 2번"), "노트 B 기록 수");

        // 각 카드 → 노트 상대 링크(AC-11).
        assertTrue(indexHtml.contains("href=\"notes/2026-07-10.html\""), "노트 A 상대 링크");
        assertTrue(indexHtml.contains("href=\"notes/2026-07-04.html\""), "노트 B 상대 링크");
        assertFalse(indexHtml.contains("file:"), "file:// 없음");
        assertFalse(indexHtml.contains(siteDir.toString()), "절대 경로 유출 없음");
    }

    @Test
    @DisplayName("TΔ1/AC-Δ1,AC-Δ2: 갤러리 페이지는 안 생기고 노트 사진 영역은 상대 썸네일로 보존된다")
    void dropsGalleryButKeepsNotePhotos(@TempDir Path dataDir, @TempDir Path siteDir) {
        NoteRepository repo = new JsonFileNoteRepository(dataDir, MochaObjectMapper.create());
        OffsetDateTime now = OffsetDateTime.parse("2026-07-10T09:00:00+09:00");
        NoteMeta meta = new NoteMeta(
                "예가체프 G1 워시드",
                Sourced.user("커피베라"), Sourced.search("에티오피아"),
                null, null, Sourced.search(List.of()), List.of());
        // 사진 경로를 가진 엔트리 — 노트 상세(FR-7)의 사진 영역이 렌더 대상.
        repo.upsertEntry("2026-07-10", meta,
                new Entry(LocalDate.parse("2026-07-10"), "새콤하다.", Rating.GOOD,
                        List.of("photos/2026-07-10/2026-07-10/a.jpg"), now));

        new ThymeleafSiteRenderer(repo, engine, siteDir, Theme.TYPE_B).renderAll();

        // AC-Δ1: 독립 갤러리 페이지는 생성되지 않는다(갤러리 폐기, delta 0001).
        assertFalse(Files.exists(siteDir.resolve("gallery.html")), "site/gallery.html 미생성");

        // AC-Δ2: 노트 상세의 사진 영역은 그대로 — entries[].photos가 상대 썸네일 경로(../thumbs/…)로 표시된다(AC-11).
        String noteHtml = read(siteDir.resolve("notes/2026-07-10.html"));
        assertTrue(noteHtml.contains("../thumbs/2026-07-10/2026-07-10/a.jpg"), "노트 사진 썸네일 상대 경로");
        assertFalse(noteHtml.contains("file:"), "file:// 없음");
        assertFalse(noteHtml.contains(siteDir.toString()), "절대 경로 유출 없음");
    }

    @Test
    @DisplayName("AC-6: site/ 삭제 후 재렌더하면 동일 산출이 복원된다")
    void reRenderIsReproducible(@TempDir Path dataDir, @TempDir Path siteDir) {
        NoteRepository repo = seedRepository(dataDir);
        ThymeleafSiteRenderer renderer = new ThymeleafSiteRenderer(repo, engine, siteDir, Theme.TYPE_A);

        renderer.renderAll();
        String indexFirst = read(siteDir.resolve("index.html"));
        String noteFirst = read(siteDir.resolve("notes/2026-07-10.html"));

        deleteRecursively(siteDir);
        assertFalse(Files.exists(siteDir.resolve("index.html")), "site/ 비워짐");

        renderer.renderAll();
        assertEquals(indexFirst, read(siteDir.resolve("index.html")), "인덱스 재현성");
        assertEquals(noteFirst, read(siteDir.resolve("notes/2026-07-10.html")), "노트 재현성");
    }

    @Test
    @DisplayName("T5-1: mocha.site.theme(type-a/type-b)로 디자인이 갈린다")
    void themeSelectsDesign(@TempDir Path dataDir, @TempDir Path siteA, @TempDir Path siteB) {
        NoteRepository repo = seedRepository(dataDir);
        new ThymeleafSiteRenderer(repo, engine, siteA, Theme.TYPE_A).renderAll();
        new ThymeleafSiteRenderer(repo, engine, siteB, Theme.TYPE_B).renderAll();

        String serifNote = read(siteA.resolve("notes/2026-07-10.html"));
        String cuteNote = read(siteB.resolve("notes/2026-07-10.html"));
        assertTrue(serifNote.contains("Gowun Batang") && serifNote.contains("COFFEE NOTE"), "type-a=세리프");
        assertTrue(cuteNote.contains("Gowun Dodum") && cuteNote.contains("🐾 내가 느끼길"), "type-b=귀여운");
        // 공통: 두 테마 모두 2단(로스터리가 말하길 / 내가 느끼길)을 표시한다(FR-7).
        assertTrue(serifNote.contains("로스터리가 말하길") && serifNote.contains("내가 느끼길"), "세리프 2단");
        assertTrue(cuteNote.contains("로스터리가 말하길") && cuteNote.contains("내가 느끼길"), "귀여운 2단");
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + needle.length())) {
            count++;
        }
        return count;
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
