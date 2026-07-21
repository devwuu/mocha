package com.devwuu.mocha.render;

import com.devwuu.mocha.domain.Bean;
import com.devwuu.mocha.domain.Brew;
import com.devwuu.mocha.domain.Entry;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.Recipe;
import com.devwuu.mocha.domain.Sourced;
import com.devwuu.mocha.domain.Tasting;
import com.devwuu.mocha.repository.NoteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * 파이프라인 [6] — Thymeleaf를 오프라인 실행해 JSON 원본을 회차 카드 JPG로 굽는다
 * (ref: plan.md §1 [6], ADR-1, ADR-7, ADR-10, ADR-54·59; changes/0021 TΔ5a).
 * <ul>
 *   <li>{@code artifact/cards/<slug>/<date>-taste-<n>.jpg} — 회차 n의 감상 카드(tasting 있는 회차만, AC-78).
 *       {@code templates/<theme>/taste.html}을 회차 파트 1건으로 렌더한 뒤
 *       {@link CardImageRenderer}(헤드리스 Chromium)로 래스터화한다(ADR-10/ADR-11).</li>
 *   <li>{@code artifact/cards/<slug>/<date>-recipe-<n>.jpg} — 회차 n의 레시피 카드(recipe 있는 회차만, AC-78).</li>
 *   <li>{@code artifact/mascot-face.png}·{@code artifact/fonts/*.ttf} — 카드가 참조하는 로컬 자산(ADR-11).</li>
 * </ul>
 * <p>POLICY: 렌더 산출물은 cards/&lt;slug&gt;/&lt;date&gt;-taste-&lt;n&gt;.jpg·&lt;date&gt;-recipe-&lt;n&gt;.jpg뿐 —
 * artifact/ 아래 HTML 산출 금지 (ADR-55·59, AC-67). index.html은 폐기됐다(changes/0021 TΔ6).
 * <p>카드 HTML은 <b>파일로 남기지 않는다</b> — 카드를 굽는 순간의 중간 입력일 뿐이다(ADR-10).
 * <p>디자인은 {@link Theme}(type-a 세리프 / type-b 귀여운)로 고르며 {@code templates/<theme>/} 폴더를 탄다.
 * 카드 디자인 원본은 {@code design/} 시안 — 변경은 시안 갱신 → 템플릿 재이식, 이식 편차는 델타 명시분만(ADR-54 POLICY).
 * <p>POLICY: 렌더러는 JSON 외 어떤 상태도 읽지 않는다 — {@link NoteRepository#findAll()}만이 입력이고
 * 산출물은 언제든 전체 재생성 가능한 파생물이다(ref: plan.md#ADR-1, AC-6/AC-Δ7).
 * <p>POLICY: HTML의 모든 링크·이미지는 상대 경로만 쓴다 — {@code file://} 직접 열람 보장(ref: plan.md, AC-Δ5).
 * <p>POLICY: 렌더 엔진(Playwright/Chromium) 타입은 {@link CardImageRenderer} 경계 뒤에만 존재한다 — 렌더러는
 * 엔진을 직접 참조하지 않는다(ref: plan.md#ADR-11 POLICY, NFR-4).
 */
public class ThymeleafNoteRenderer implements NoteRenderer {

    private static final Logger log = LoggerFactory.getLogger(ThymeleafNoteRenderer.class);

    private static final String MASCOT_NAME = "mascot-face.png";
    private static final String MASCOT_RESOURCE = "/assets/" + MASCOT_NAME;
    private static final String FONT_RESOURCE_PREFIX = "/assets/fonts/";
    private static final String FONTS_DIR = "fonts";

    private final NoteRepository noteRepository;
    private final ITemplateEngine templateEngine;
    private final Path artifactDir;
    private final Theme theme;
    private final CardImageRenderer cardImageRenderer;

    private final KoreanDates dates = new KoreanDates();
    private final RatingStyle ratingStyle = new RatingStyle();
    private final RecipeAmounts recipeAmounts = new RecipeAmounts();

    public ThymeleafNoteRenderer(
            NoteRepository noteRepository, ITemplateEngine templateEngine, Path artifactDir, Theme theme,
            CardImageRenderer cardImageRenderer) {
        this.noteRepository = noteRepository;
        this.templateEngine = templateEngine;
        this.artifactDir = artifactDir;
        this.theme = theme;
        this.cardImageRenderer = cardImageRenderer;
    }

    @Override
    public void renderAll() {
        List<EntryRef> ordered = orderedEntries(noteRepository.findAll());

        // 카드가 참조하는 로컬 자산을 base(artifact 루트)에 먼저 깔아야 래스터화 시 폰트·이미지가 해석된다(ADR-11).
        copyMascot();
        copyFonts();
        Set<Path> baked = new HashSet<>();
        for (EntryRef ref : ordered) {
            baked.addAll(bakeEntryCards(ref));
        }
        pruneOrphanCards(baked);
        log.info("전체 리렌더 완료: theme={} entries={} cards={} dir={}",
                theme.id(), ordered.size(), baked.size(), artifactDir);
    }

    @Override
    public List<Path> renderEntryCard(String slug, LocalDate date) {
        Note note = noteRepository.findAll().stream().filter(n -> n.slug().equals(slug)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("카드 렌더 대상 노트 없음: slug=" + slug));
        Entry entry = note.entries() == null ? null : note.entries().stream()
                .filter(e -> e.date().equals(date)).findFirst().orElse(null);
        if (entry == null) {
            throw new IllegalArgumentException("카드 렌더 대상 엔트리 없음: slug=" + slug + " date=" + date);
        }

        // 증분 렌더라도 base 자산은 최신 상태여야 한다 — 카드가 참조하는 폰트·이미지의 해석 기준(AC-Δ7).
        copyMascot();
        copyFonts();
        List<Path> cards = bakeEntryCards(new EntryRef(note, entry));
        // 회차 감소·파트 소멸 재저장의 옛 번호 카드가 남지 않게, 방금 산출 집합 외의 그 엔트리 카드를 지운다(TΔ5a).
        pruneEntryCards(slug, date, Set.copyOf(cards));
        log.info("엔트리 카드 렌더: slug={} date={} → {}장", slug, date, cards.size());
        return cards;
    }

    @Override
    public void removeEntryCard(String slug, LocalDate date) {
        // 수정 세션 날짜 이동의 옛 date 카드 정리(AC-39) — 그 엔트리의 회차 카드 전부.
        // 파일 부재는 정상(이미 없거나 렌더된 적 없음) — 멱등.
        pruneEntryCards(slug, date, Set.of());
    }

    // 그 엔트리(slug,date)의 카드 파일 중 keep에 없는 것을 지운다 — removeEntryCard(전부)와
    // 재저장 잔존 정리(방금 산출 외)의 공용 지점.
    private void pruneEntryCards(String slug, LocalDate date, Set<Path> keep) {
        Path slugDir = artifactDir.resolve(CardFiles.CARDS_DIR).resolve(slug);
        if (!Files.isDirectory(slugDir)) {
            return;
        }
        try (DirectoryStream<Path> cards = Files.newDirectoryStream(slugDir, CardFiles.entryCardGlob(date))) {
            for (Path card : cards) {
                if (!keep.contains(card)) {
                    Files.delete(card);
                    log.info("엔트리 카드 삭제: slug={} date={} file={}", slug, date, card.getFileName());
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("엔트리 카드 삭제 실패: slug=" + slug + " date=" + date, e);
        }
    }

    // POLICY: renderAll은 JSON 기준 산출 집합에 없는 카드 파일을 지운다 — removeEntryCard 실패로 남은
    //         옛 카드의 최종 정리 지점이자 파생물 재현 동일성의 근거
    //         (ref: specs/coffee-note-agent/plan.md §7, changes/0012 delta AC-Δ7).
    private void pruneOrphanCards(Set<Path> expected) {
        Path cardsDir = artifactDir.resolve(CardFiles.CARDS_DIR);
        if (!Files.isDirectory(cardsDir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(cardsDir)) {
            // 깊은 경로부터(역순) — 고아 파일을 지운 뒤 비게 된 slug 디렉토리까지 정리한다.
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                if (Files.isRegularFile(path)) {
                    if (!expected.contains(path)) {
                        Files.delete(path);
                        log.info("고아 카드 정리: {}", path);
                    }
                } else if (Files.isDirectory(path) && !path.equals(cardsDir) && isEmptyDir(path)) {
                    Files.delete(path);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("고아 카드 정리 실패: " + cardsDir, e);
        }
    }

    private static boolean isEmptyDir(Path dir) throws IOException {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.findAny().isEmpty();
        }
    }

    // --- 회차 카드 (회차 파트 1건 → JPG, ADR-54·59) ---

    // 엔트리의 회차 카드 전부를 굽는다 — tasting 있는 회차는 감상 카드, recipe 있는 회차는 레시피 카드(AC-78).
    // 산출 순서 = CardFiles.expectedCards와 동일(회차 오름차순, 감상 → 레시피).
    private List<Path> bakeEntryCards(EntryRef ref) {
        List<Path> out = new ArrayList<>();
        List<Brew> brews = ref.entry().brews();
        for (int i = 0; i < brews.size(); i++) {
            int n = i + 1; // 배열 순서 = 회차 번호(ADR-59)
            Brew brew = brews.get(i);
            if (brew.tasting() != null) {
                out.add(bakeTasteCard(ref.note(), ref.entry(), brew.tasting(), n));
            }
            if (brew.recipe() != null) {
                out.add(bakeRecipeCard(ref.note(), ref.entry(), brew.recipe(), n));
            }
        }
        return out;
    }

    // taste.html을 회차 감상 파트 1건으로 렌더해 cards/<slug>/<date>-taste-<n>.jpg로 굽는다.
    private Path bakeTasteCard(Note note, Entry entry, Tasting tasting, int brewNumber) {
        NoteView.TasteCard card = new NoteView.TasteCard(
                value(note.coffeeName()), // 제목은 값만 — 출처 무표기(제목=정체성, NoteView.TasteCard)
                value(note.roastery()),
                beanLines(note.beans()),
                value(note.roastLevel()),
                note.officialNotes() == null || note.officialNotes().value() == null
                        ? List.of() : note.officialNotes().value(),
                entry.date(),
                tasting.myTaste(),
                tasting.rating());
        Path out = CardFiles.tasteCard(artifactDir, note.slug(), entry.date(), brewNumber);
        cardImageRenderer.render(render("taste", cardContext(card)), artifactDir, out);
        return out;
    }

    // recipe.html을 회차 레시피 파트 1건으로 렌더해 cards/<slug>/<date>-recipe-<n>.jpg로 굽는다.
    private Path bakeRecipeCard(Note note, Entry entry, Recipe recipe, int brewNumber) {
        NoteView.RecipeCard card = new NoteView.RecipeCard(
                value(note.coffeeName()), value(note.roastery()), entry.date(), recipe);
        Path out = CardFiles.recipeCard(artifactDir, note.slug(), entry.date(), brewNumber);
        cardImageRenderer.render(render("recipe", cardContext(card)), artifactDir, out);
        return out;
    }

    private Context cardContext(Object card) {
        Context ctx = baseContext();
        ctx.setVariable("card", card);
        return ctx;
    }

    // beans → 카드 표시 행(설명 + 가공방식, 출처 무표기 — FR-7 매핑, NoteView.BeanLine).
    private static List<NoteView.BeanLine> beanLines(List<Bean> beans) {
        if (beans == null) {
            return List.of();
        }
        return beans.stream()
                .map(b -> new NoteView.BeanLine(b.description().value(), value(b.process())))
                .toList();
    }

    // --- 공통 ---

    // 모든 (노트,엔트리)를 엔트리 date 내림차순 + slug 오름차순으로 평탄화한다(결정론적 재현성, AC-Δ7).
    private static List<EntryRef> orderedEntries(List<Note> notes) {
        List<EntryRef> refs = new ArrayList<>();
        for (Note note : notes) {
            if (note.entries() == null) {
                continue;
            }
            for (Entry entry : note.entries()) {
                refs.add(new EntryRef(note, entry));
            }
        }
        refs.sort(Comparator.comparing((EntryRef r) -> r.entry().date()).reversed()
                .thenComparing(r -> r.note().slug()));
        return refs;
    }

    private Context baseContext() {
        Context ctx = new Context(Locale.KOREAN);
        ctx.setVariable("fmt", dates);
        ctx.setVariable("rs", ratingStyle);
        ctx.setVariable("amt", recipeAmounts);
        ctx.setVariable("mascotHref", MASCOT_NAME); // artifact 루트 기준 상대 경로
        return ctx;
    }

    private String render(String templateName, Context ctx) {
        return templateEngine.process(theme.id() + "/" + templateName, ctx);
    }

    private static String value(Sourced<String> sourced) {
        return sourced == null ? null : sourced.value();
    }

    // 템플릿이 참조하는 마스코트 자산을 artifact/ 루트로 복사한다(상대 경로 참조 대상, AC-Δ5).
    private void copyMascot() {
        try (InputStream in = getClass().getResourceAsStream(MASCOT_RESOURCE)) {
            if (in == null) {
                log.warn("마스코트 자산을 클래스패스에서 못 찾음: {} — 이미지 링크가 깨질 수 있음", MASCOT_RESOURCE);
                return;
            }
            Files.createDirectories(artifactDir);
            Files.write(artifactDir.resolve(MASCOT_NAME), in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException("마스코트 자산 복사 실패", e);
        }
    }

    // 카드/인덱스 CSS의 @font-face가 CDN 없이 해석되게 테마 폰트를 artifact/fonts/로 복사한다(오프라인·결정성, ADR-11).
    private void copyFonts() {
        Path fontsDir = artifactDir.resolve(FONTS_DIR);
        for (String name : theme.fontFiles()) {
            String resource = FONT_RESOURCE_PREFIX + name;
            try (InputStream in = getClass().getResourceAsStream(resource)) {
                if (in == null) {
                    log.warn("폰트 자산을 클래스패스에서 못 찾음: {} — 카드 폰트가 기본 폰트로 대체될 수 있음(assets/fonts/README.md)",
                            resource);
                    continue;
                }
                Files.createDirectories(fontsDir);
                Files.write(fontsDir.resolve(name), in.readAllBytes());
            } catch (IOException e) {
                throw new UncheckedIOException("폰트 자산 복사 실패: " + resource, e);
            }
        }
    }

    /** 평탄화된 (노트, 엔트리) 한 쌍 — 카드 산출의 단위. */
    private record EntryRef(Note note, Entry entry) {
    }
}
