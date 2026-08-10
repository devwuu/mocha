package com.devwuu.mocha.render;

import static org.assertj.core.api.Assertions.assertThat;

import com.devwuu.mocha.config.RenderConfig;
import com.devwuu.mocha.domain.Aliases;
import com.devwuu.mocha.domain.Bean;
import com.devwuu.mocha.domain.Cup;
import com.devwuu.mocha.domain.TastingDay;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.NoteMeta;
import com.devwuu.mocha.domain.Rating;
import com.devwuu.mocha.domain.Recipe;
import com.devwuu.mocha.domain.Source;
import com.devwuu.mocha.domain.Sourced;
import com.devwuu.mocha.domain.Review;
import com.devwuu.mocha.service.NoteTxService;
import com.devwuu.mocha.service.NoteService;
import com.devwuu.mocha.repository.NoteFolderName;
import com.devwuu.mocha.repository.jpa.NoteEntityRepository;
import com.devwuu.mocha.support.PostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.thymeleaf.spring6.SpringTemplateEngine;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * TΔ15 (changes/0028-rdb-storage) — 재생성 재현성 (AC-Δ5 / NFR-3 · AC-6).
 *
 * <p>판정은 하나다: <b>{@code artifact/}를 통째로 지운 뒤 재렌더하면 DB만을 입력으로 같은 산출이 복원된다.</b>
 *
 * <p>{@link ThymeleafNoteRendererTest#reRenderIsReproducible}와 갈리는 지점이 이 클래스의 존재 이유다.
 * 그쪽은 저장소가 인메모리 fake라 <b>렌더러가 결정적인가</b>까지만 답한다 — 노트가 어느 매체에서 오는지는
 * 검증 밖이고, 파일 시절에도 같은 그린이 났다. 여기서는 노트를 실 Postgres에 <b>운영 쓰기 경로</b>
 * ({@code upsertTastingDay})로 심고, 두 번째 렌더 전에 영속성 컨텍스트를 비워 조회가 실제로 행을 지나게 한다.
 * 그래야 그린이 "DB만으로 복원된다"를 붙잡는다.
 *
 * <p>재현 동일성만으로는 <b>입력이 DB라는 것</b>이 관측되지 않는다 — 아무것도 읽지 않고 상수를 굽는
 * 렌더러도 두 번 같은 산출을 낸다. 그래서 두 번째 테스트가 지운 뒤 DB를 바꾸고, 산출이 그 변경을 따라가는지
 * 본다(추가된 엔트리는 나타나고 삭제된 노트는 되살아나지 않는다).
 *
 * <p>카드 자산(마스코트·폰트)은 <b>클래스패스 리소스</b>라 DB에서 오지 않는다. 이것은 예외가 아니라 규칙의
 * 반대편이다 — 자산은 코드와 함께 배포되는 정적 입력이지 기록된 데이터가 아니다. 삭제 후 함께 복원되는지는
 * 산출 파일 집합 비교에 포함돼 있다.
 */
@Transactional
class RerenderFromDatabaseTest extends PostgresIntegrationTest {

    // 도메인 record가 요구하지만 저장이 발급하는 값 — 입력이 실어 보내도 무시된다(TΔ5a insert 계약).
    private static final OffsetDateTime IGNORED = OffsetDateTime.of(2000, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    private final SpringTemplateEngine engine = RenderConfig.offlineTemplateEngine();

    @Autowired
    EntityManager em;

    @Autowired
    NoteEntityRepository notes;

    @Test
    @DisplayName("TΔ15/AC-Δ5·AC-6: artifact/ 전체 삭제 후 재렌더하면 DB만을 입력으로 전체 카드가 동일하게 복원된다")
    void rerenderAfterWipingArtifactRestoresIdenticalOutput(@TempDir Path tempDir) {
        Path artifactDir = tempDir.resolve("artifact");
        NoteTxService repo = new NoteTxService(notes);
        seedTwoNotes(repo);
        em.clear();

        FakeCardImageRenderer firstCards = new FakeCardImageRenderer();
        new ThymeleafNoteRenderer(serviceOver(repo), engine, artifactDir, Theme.TYPE_B, firstCards).renderAll();
        Set<String> firstFiles = artifactFiles(artifactDir);
        List<String> firstOrder = bakeOrder(artifactDir, firstCards);
        Map<String, String> firstHtml = capturedHtmlByCard(artifactDir, firstCards);
        // 카드가 실제로 나와야 비교가 의미를 갖는다 — 빈 산출끼리는 어떤 재현성 단언도 통과한다.
        // 파일 집합 전체로 재면 안 된다: 마스코트·폰트는 노트가 0건이어도 깔린다(자산은 클래스패스에서 온다).
        assertThat(firstOrder).as("첫 렌더가 구운 카드").isNotEmpty();

        deleteRecursively(artifactDir);
        assertThat(Files.exists(artifactDir)).as("artifact/ 통째로 삭제됨").isFalse();

        // 저장소·렌더러를 새로 조립하고 영속성 컨텍스트를 비운다 — 두 번째 렌더의 입력이 DB 행뿐이게 한다.
        // 비우지 않으면 identity map이 첫 렌더가 올린 객체를 되돌려줘 DB를 지나지 않은 채 그린이 된다.
        em.clear();
        FakeCardImageRenderer secondCards = new FakeCardImageRenderer();
        new ThymeleafNoteRenderer(serviceOver(new NoteTxService(notes)), engine, artifactDir, Theme.TYPE_B, secondCards)
                .renderAll();

        // 파일 집합에는 카드뿐 아니라 마스코트·폰트도 들어간다 — artifact/ 전체가 복원돼야 한다.
        assertThat(artifactFiles(artifactDir)).as("artifact/ 산출 파일 집합 재현").isEqualTo(firstFiles);
        assertThat(capturedHtmlByCard(artifactDir, secondCards)).as("카드 HTML 재현").isEqualTo(firstHtml);
        // 굽는 순서까지 같아야 한다 — 집합·맵 비교는 순서를 보지 않아 정렬이 비결정적이어도 그린이다.
        // (어떤 순서가 옳은지는 ThymeleafNoteRendererTest#renderAllOrdersByDateThenNoteId가 소유한다.)
        assertThat(bakeOrder(artifactDir, secondCards)).as("카드 굽기 순서 재현").isEqualTo(firstOrder);
    }

    @Test
    @DisplayName("TΔ15/AC-Δ5: 재렌더의 입력은 DB 현재 상태다 — 삭제 뒤 DB가 바뀌면 산출이 그대로 따라간다")
    void rerenderReflectsCurrentDatabaseState(@TempDir Path tempDir) {
        Path artifactDir = tempDir.resolve("artifact");
        NoteTxService repo = new NoteTxService(notes);
        List<Note> seeded = seedTwoNotes(repo);
        Note kept = seeded.getFirst();
        Note removed = seeded.getLast();
        em.clear();

        new ThymeleafNoteRenderer(serviceOver(repo), engine, artifactDir, Theme.TYPE_B, new FakeCardImageRenderer()).renderAll();
        assertThat(cardFiles(artifactDir)).contains(
                cardPath(kept, "2026-07-10-review-1.jpg"),
                cardPath(removed, "2026-07-04-review-1.jpg"));

        deleteRecursively(artifactDir);

        // DB만 바꾼다 — 파일에는 아무것도 남아 있지 않다. 한쪽은 늘리고 한쪽은 지워 방향을 둘 다 본다.
        repo.commit(kept.id(), metaOf(kept), reviewTastingDay(LocalDate.of(2026, 7, 20), "다음 날은 더 달았다."),
                Aliases.empty());
        repo.delete(removed.id());
        em.clear();

        new ThymeleafNoteRenderer(serviceOver(new NoteTxService(notes)), engine, artifactDir, Theme.TYPE_B,
                new FakeCardImageRenderer()).renderAll();

        assertThat(cardFiles(artifactDir))
                .as("DB에 추가된 엔트리는 카드로 나타나고, 삭제된 노트는 폴더째 되살아나지 않는다")
                .containsExactlyInAnyOrder(
                        cardPath(kept, "2026-07-10-review-1.jpg"),
                        cardPath(kept, "2026-07-10-recipe-1.jpg"),
                        cardPath(kept, "2026-07-20-review-1.jpg"));
    }

    // ────────────────────────────── 표본 ──────────────────────────────

    /**
     * 노트 2건을 <b>운영 쓰기 경로</b>({@code upsertTastingDay})로 심는다 — 실제 커밋이 남기는 것과 같은 행 모양이어야
     * 렌더 입력으로서의 DB가 재현된다. 한쪽은 레시피 있는 회차를 둬 카드 2종(감상·레시피)이 모두 산출되게 한다.
     */
    /**
     * 렌더러가 잡는 타입은 0029 TΔ4a부터 {@link NoteService}다 — 실 저장소를 그 뒤에 그대로 세운다.
     * 별칭 생성기는 null이다: 렌더 경로는 커밋을 지나지 않아 닿을 일이 없고, 닿으면 NPE로 드러나야 한다.
     */
    private static NoteService serviceOver(NoteTxService repo) {
        return new NoteService(repo, null, null, null);
    }

    private List<Note> seedTwoNotes(NoteTxService repo) {
        NoteMeta yirgacheffe = new NoteMeta(
                new Sourced<>("예가체프 G1 워시드", Source.USER),
                new Sourced<>("커피베라", Source.USER),
                List.of(new Bean(new Sourced<>("에티오피아 예가체프", Source.SEARCH), new Sourced<>("워시드", Source.USER))),
                new Sourced<>("라이트", Source.SEARCH),
                new Sourced<>(List.of("자몽", "베르가못"), Source.SEARCH),
                List.of("https://example.com/coffeevera"));
        NoteMeta geisha = new NoteMeta(
                new Sourced<>("콜롬비아 게이샤 워시드", Source.USER),
                new Sourced<>("프릳츠", Source.USER),
                List.of(new Bean(new Sourced<>("콜롬비아", Source.SEARCH), null)),
                null, new Sourced<>(List.of(), Source.SEARCH), List.of());

        TastingDay withRecipe = new TastingDay(
                LocalDate.of(2026, 7, 10),
                List.of(new Cup(
                        new Recipe("핸드드립", 15.0, 240.0, null, 160.0, 92.5, 21.0, "코만단테",
                                "V60 · 뜸 40ml 30초 → 100ml → 100ml", "다음엔 더 굵게"),
                        new Review("새콤하고 좋았다.", null, Rating.GOOD))),
                IGNORED);

        return List.of(
                repo.commit(null, yirgacheffe, withRecipe, Aliases.empty()),
                repo.commit(null, geisha, reviewTastingDay(LocalDate.of(2026, 7, 4), "화사하다."), Aliases.empty()));
    }

    private static TastingDay reviewTastingDay(LocalDate date, String myTaste) {
        return new TastingDay(date, List.of(new Cup(null, new Review(myTaste, null, Rating.PERFECT))), IGNORED);
    }

    /** 저장된 노트의 메타 — {@code upsertTastingDay} 재호출이 노트 단위 필드를 그대로 두게 한다. */
    private static NoteMeta metaOf(Note note) {
        return new NoteMeta(note.coffeeName(), note.roastery(), note.beans(), note.roastLevel(),
                note.officialNotes(), note.sources());
    }

    // ────────────────────────────── 산출 관측 ──────────────────────────────

    /** {@code cards/<접미>/<파일명>} 형태의 상대 경로 — 폴더 접미는 생성기가 단일 소유한다(TΔ7). */
    private static String cardPath(Note note, String fileName) {
        return "cards/" + NoteFolderName.of(note) + "/" + fileName;
    }

    /** artifact/ 하위 <b>전체</b> 파일의 상대 경로 집합 — 카드 + 마스코트 + 폰트. */
    private static Set<String> artifactFiles(Path artifactDir) {
        return relativeFiles(artifactDir, artifactDir);
    }

    /** artifact/cards 하위 카드 파일의 {@code cards/…} 상대 경로 집합. */
    private static Set<String> cardFiles(Path artifactDir) {
        return relativeFiles(artifactDir, artifactDir.resolve("cards"));
    }

    private static Set<String> relativeFiles(Path base, Path dir) {
        if (!Files.isDirectory(dir)) {
            return Set.of();
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            return walk.filter(Files::isRegularFile)
                    .map(p -> base.relativize(p).toString().replace(File.separatorChar, '/'))
                    .collect(Collectors.toSet());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** fake가 캡처한 (카드 상대 경로 → HTML) — 카드 내용의 재현 대조용. */
    private static Map<String, String> capturedHtmlByCard(Path artifactDir, FakeCardImageRenderer cards) {
        Map<String, String> byCard = new LinkedHashMap<>();
        for (FakeCardImageRenderer.Call call : cards.calls) {
            byCard.put(artifactDir.relativize(call.out()).toString().replace(File.separatorChar, '/'), call.html());
        }
        return byCard;
    }

    /**
     * 카드를 구운 순서(상대 경로 목록). {@link Map}·{@link Set} 비교는 순서를 보지 않으므로 별도로 잡는다 —
     * 붙잡는 것은 <b>정렬의 결정성</b>이지 정렬 규칙의 옳음이 아니다(규칙은 렌더러 단위 테스트가 소유).
     */
    private static List<String> bakeOrder(Path artifactDir, FakeCardImageRenderer cards) {
        return cards.calls.stream()
                .map(c -> artifactDir.relativize(c.out()).toString().replace(File.separatorChar, '/'))
                .toList();
    }

    private static void deleteRecursively(Path dir) {
        if (!Files.exists(dir)) {
            return;
        }
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
