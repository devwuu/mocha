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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 파이프라인 [7] — Thymeleaf를 오프라인 실행해 JSON 원본을 index 목록 HTML + 시음 엔트리 카드 JPG로 굽는다
 * (ref: plan.md §1 [7], ADR-1, ADR-7, ADR-10; changes/0002-instagram-share-card TΔ3/TΔ4).
 * <ul>
 *   <li>{@code artifact/index.html} — <b>엔트리 최신순</b> 행 목록(FR-8). 각 행이 {@code cards/<slug>/<date>.jpg}로 링크.</li>
 *   <li>{@code artifact/cards/<slug>/<date>.jpg} — 시음 엔트리 1건 카드(4:5). note.html을 단일 엔트리로 렌더한 뒤
 *       {@link CardImageRenderer}(헤드리스 Chromium)로 래스터화한다(ADR-10/ADR-11).</li>
 *   <li>{@code artifact/mascot-face.png}·{@code artifact/fonts/*.ttf} — 카드/인덱스가 참조하는 로컬 자산(ADR-11).</li>
 * </ul>
 * <p>노트 상세 HTML({@code notes/<slug>.html})은 <b>파일로 남기지 않는다</b> — 카드를 굽는 순간의 중간 입력일 뿐이다(ADR-10).
 * <p>디자인은 {@link Theme}(type-a 세리프 / type-b 귀여운)로 고르며 {@code templates/<theme>/} 폴더를 탄다.
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
    private static final String CARDS_DIR = "cards";

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

        // 카드/인덱스가 참조하는 로컬 자산을 base(artifact 루트)에 먼저 깔아야 래스터화 시 폰트·이미지가 해석된다(ADR-11).
        copyMascot();
        copyFonts();
        writeIndex(ordered);
        Set<Path> baked = new HashSet<>();
        for (EntryRef ref : ordered) {
            baked.add(bakeCard(ref));
        }
        pruneOrphanCards(baked);
        log.info("전체 리렌더 완료: theme={} entries={} dir={}", theme.id(), ordered.size(), artifactDir);
    }

    @Override
    public Path renderEntryCard(String slug, LocalDate date) {
        List<Note> notes = noteRepository.findAll();
        Note note = notes.stream().filter(n -> n.slug().equals(slug)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("카드 렌더 대상 노트 없음: slug=" + slug));
        Entry entry = note.entries() == null ? null : note.entries().stream()
                .filter(e -> e.date().equals(date)).findFirst().orElse(null);
        if (entry == null) {
            throw new IllegalArgumentException("카드 렌더 대상 엔트리 없음: slug=" + slug + " date=" + date);
        }

        // 증분 렌더라도 base 자산과 index는 최신 상태여야 한다 — 방금 엔트리 1장만 새로 굽되 목록은 전체 갱신(AC-Δ7).
        copyMascot();
        copyFonts();
        Path cardPath = bakeCard(new EntryRef(note, entry));
        writeIndex(orderedEntries(notes));
        log.info("엔트리 카드 렌더: slug={} date={} → {}", slug, date, cardPath);
        return cardPath;
    }

    @Override
    public void removeEntryCard(String slug, LocalDate date) {
        // 수정 세션 날짜 이동의 옛 date 카드 정리(AC-39). 파일 부재는 정상(이미 없거나 렌더된 적 없음) — 멱등.
        Path card = artifactDir.resolve(CARDS_DIR).resolve(slug).resolve(date + ".jpg");
        try {
            if (Files.deleteIfExists(card)) {
                log.info("엔트리 카드 삭제: slug={} date={}", slug, date);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("엔트리 카드 삭제 실패: " + card, e);
        }
    }

    // POLICY: renderAll은 JSON 기준 산출 집합에 없는 카드 파일을 지운다 — removeEntryCard 실패로 남은
    //         옛 카드의 최종 정리 지점이자 파생물 재현 동일성의 근거
    //         (ref: specs/coffee-note-agent/plan.md §7, changes/0012 delta AC-Δ7).
    private void pruneOrphanCards(Set<Path> expected) {
        Path cardsDir = artifactDir.resolve(CARDS_DIR);
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

    // --- 인덱스 (엔트리 최신순 목록) ---

    private void writeIndex(List<EntryRef> ordered) {
        // 헤더 집계는 노트(원두) 수·전체 기록(엔트리) 수 — 행은 엔트리당 1행이지만 집계는 유지(delta FR-8).
        long noteCount = ordered.stream().map(r -> r.note().slug()).distinct().count();
        List<NoteView.Row> rows = ordered.stream().map(this::toRow).toList();
        NoteView.Index index = new NoteView.Index((int) noteCount, ordered.size(), rows);

        Context ctx = baseContext();
        ctx.setVariable("index", index);
        writeHtml(artifactDir.resolve("index.html"), render("index", ctx));
    }

    private NoteView.Row toRow(EntryRef ref) {
        Entry entry = ref.entry();
        Tasting tasting = latestTasting(entry);
        return new NoteView.Row(
                cardHref(ref.note().slug(), entry.date()),
                value(ref.note().coffeeName()),
                value(ref.note().roastery()),
                value(beansDescriptionSummary(ref.note().beans())),
                entry.date(),
                tasting == null ? null : tasting.rating());
    }

    // --- 엔트리 카드 (단일 엔트리 → JPG) ---

    // note.html을 대상 엔트리 1건으로 렌더해 cards/<slug>/<date>.jpg로 굽는다. HTML은 중간 입력, 파일로 남기지 않는다(ADR-10).
    private Path bakeCard(EntryRef ref) {
        Note note = ref.note();
        Entry entry = ref.entry();
        NoteView.EntryCard card = new NoteView.EntryCard(
                note.slug(),
                note.coffeeName(), // Sourced 그대로 — 제목은 value만 쓰되 (사진) 무표기(제목=정체성, TΔ6)
                note.roastery(),
                beansDescriptionSummary(note.beans()),
                beansProcessSummary(note.beans()),
                note.roastLevel(),
                note.officialNotes() == null ? List.of() : note.officialNotes().value(),
                note.sources() == null ? List.of() : note.sources(),
                toEntryView(entry));

        Context ctx = baseContext();
        ctx.setVariable("note", card); // 템플릿 변수명은 note 유지 — 카드가 참조하는 메타(로스터리 등)의 소유자
        String html = render("note", ctx);

        Path out = artifactDir.resolve(CARDS_DIR).resolve(note.slug()).resolve(entry.date() + ".jpg");
        cardImageRenderer.render(html, artifactDir, out);
        return out;
    }

    // POLICY: 렌더러는 사진을 읽지 않는다 — 사진은 아카이브 전용이라 카드/인덱스에 실리지 않는다
    //         (ref: specs/coffee-note-agent/changes/0014-photo-archive-only ADR-32, AC-Δ2).
    // TΔ1b 과도기: 구 템플릿(note.html)은 엔트리 단일 감상·레시피 계약이라 마지막 회차의 tasting/recipe를
    // 대표로 물린다 — TΔ4·TΔ5a에서 회차 파트별 카드 2종(taste/recipe)으로 대체된다(changes/0021 ADR-54·59).
    private NoteView.EntryView toEntryView(Entry entry) {
        Tasting tasting = latestTasting(entry);
        return new NoteView.EntryView(
                entry.date(),
                tasting == null ? null : tasting.myTaste(),
                tasting == null ? null : tasting.rating(),
                latestRecipe(entry)); // null이면 템플릿이 "이렇게 내렸어요" 영역을 숨긴다(AC-Δ2)
    }

    private static Tasting latestTasting(Entry entry) {
        List<Brew> brews = entry.brews();
        for (int i = brews.size() - 1; i >= 0; i--) {
            if (brews.get(i).tasting() != null) {
                return brews.get(i).tasting();
            }
        }
        return null;
    }

    private static Recipe latestRecipe(Entry entry) {
        List<Brew> brews = entry.brews();
        for (int i = brews.size() - 1; i >= 0; i--) {
            if (brews.get(i).recipe() != null) {
                return brews.get(i).recipe();
            }
        }
        return null;
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
        ctx.setVariable("mascotHref", MASCOT_NAME); // 카드·인덱스 모두 artifact 루트 기준 상대 경로
        return ctx;
    }

    private String render(String templateName, Context ctx) {
        return templateEngine.process(theme.id() + "/" + templateName, ctx);
    }

    private static String cardHref(String slug, LocalDate date) {
        return CARDS_DIR + "/" + slug + "/" + date + ".jpg";
    }

    // TΔ1a 과도기: 구 템플릿(note.html·index.html)은 origin/process 표시 계약이라 beans를 요약해 물린다.
    // 출처 배지는 첫 요소 서브필드의 출처로 대표한다 — TΔ4(카드 2종 이식)·TΔ6(index 폐기)에서
    // beans 네이티브 바인딩으로 대체된다(changes/0021 ADR-54).
    private static Sourced<String> beansDescriptionSummary(List<Bean> beans) {
        if (beans == null || beans.isEmpty()) {
            return null;
        }
        String joined = beans.stream().map(b -> b.description().value()).collect(Collectors.joining(", "));
        return new Sourced<>(joined, beans.getFirst().description().source());
    }

    private static Sourced<String> beansProcessSummary(List<Bean> beans) {
        List<Sourced<String>> processes = beans == null ? List.<Sourced<String>>of() : beans.stream()
                .map(Bean::process)
                .filter(p -> p != null && p.value() != null && !p.value().isBlank())
                .toList();
        if (processes.isEmpty()) {
            return null;
        }
        String joined = processes.stream().map(Sourced::value).collect(Collectors.joining(", "));
        return new Sourced<>(joined, processes.getFirst().source());
    }

    private static String value(Sourced<String> sourced) {
        return sourced == null ? null : sourced.value();
    }

    private void writeHtml(Path target, String html) {
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, html, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("HTML 저장 실패: " + target, e);
        }
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

    /** 평탄화된 (노트, 엔트리) 한 쌍 — 카드/인덱스 행의 단위. */
    private record EntryRef(Note note, Entry entry) {
    }
}
