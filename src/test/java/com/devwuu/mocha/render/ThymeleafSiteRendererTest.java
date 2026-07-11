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
