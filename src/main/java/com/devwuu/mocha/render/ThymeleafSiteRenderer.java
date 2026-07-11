package com.devwuu.mocha.render;

import com.devwuu.mocha.domain.Entry;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.Sourced;
import com.devwuu.mocha.repository.NoteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.thymeleaf.ITemplateEngine;
import org.thymeleaf.context.Context;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * 파이프라인 [7] — Thymeleaf를 오프라인 실행해 JSON 원본을 정적 HTML로 전체 리렌더한다
 * (ref: plan.md §1 [7], ADR-1, ADR-7; tasks T5-1). T3-5 임시 {@code LoggingSiteRenderer}를 대체한다.
 * <ul>
 *   <li>{@code site/index.html} — 노트 목록(FR-8)</li>
 *   <li>{@code site/notes/<slug>.html} — 노트 상세, "로스터리가 말하길 / 내가 느끼길" 2단(FR-7)</li>
 *   <li>{@code site/mascot-face.png} — 템플릿이 참조하는 정적 자산</li>
 * </ul>
 * <p>디자인은 {@link Theme}(type-a 세리프 / type-b 귀여운)로 고르며 {@code templates/<theme>/} 폴더를 탄다.
 * <p>POLICY: 렌더러는 JSON 외 어떤 상태도 읽지 않는다 — {@link NoteRepository#findAll()}만이 입력이고
 * HTML은 언제든 전체 재생성 가능한 파생물이다(ref: plan.md#ADR-1, AC-6).
 * <p>POLICY: HTML의 모든 링크·이미지는 상대 경로만 쓴다 — {@code file://} 직접 열람 보장(ref: plan.md, AC-11).
 * <p>노트 상세는 날짜 엔트리 전체를 시간순 타임라인으로 나열하고 출처 링크를 노출한다(T5-2, AC-13/FR-12).
 * 인덱스 카드는 최근 엔트리 1건만 요약한다. print CSS는 후속(T5-3)이 채운다.
 */
public class ThymeleafSiteRenderer implements SiteRenderer {

    private static final Logger log = LoggerFactory.getLogger(ThymeleafSiteRenderer.class);

    private static final String MASCOT_NAME = "mascot-face.png";
    private static final String MASCOT_RESOURCE = "/assets/" + MASCOT_NAME;
    private static final String PHOTOS_PREFIX = "photos/";
    private static final String THUMBS_PREFIX = "thumbs/";

    private final NoteRepository noteRepository;
    private final ITemplateEngine templateEngine;
    private final Path siteDir;
    private final Theme theme;

    private final KoreanDates dates = new KoreanDates();
    private final RatingStyle ratingStyle = new RatingStyle();

    public ThymeleafSiteRenderer(
            NoteRepository noteRepository, ITemplateEngine templateEngine, Path siteDir, Theme theme) {
        this.noteRepository = noteRepository;
        this.templateEngine = templateEngine;
        this.siteDir = siteDir;
        this.theme = theme;
    }

    @Override
    public void renderAll() {
        List<Note> notes = noteRepository.findAll();

        // 최근 기록일 내림차순으로 노트를 세운다 — 인덱스와 상세 링크가 같은 순서를 공유한다(결정론적, AC-6).
        List<Note> ordered = notes.stream()
                .sorted(Comparator.comparing(ThymeleafSiteRenderer::latestDate).reversed()
                        .thenComparing(Note::slug))
                .toList();

        writeIndex(ordered);
        for (Note note : ordered) {
            writeNote(note);
        }
        copyMascot();
        log.info("전체 리렌더 완료: theme={} notes={} dir={}", theme.id(), notes.size(), siteDir);
    }

    // --- 인덱스 ---

    private void writeIndex(List<Note> ordered) {
        int recordCount = ordered.stream().mapToInt(n -> n.entries().size()).sum();
        List<SiteView.Row> rows = ordered.stream().map(this::toRow).toList();
        SiteView.Index index = new SiteView.Index(ordered.size(), recordCount, rows);

        Context ctx = baseContext();
        ctx.setVariable("index", index);
        writeHtml(siteDir.resolve("index.html"), render("index", ctx));
    }

    private SiteView.Row toRow(Note note) {
        Entry latest = latestEntry(note);
        // 대표 썸네일(FR-8): 카드의 요약 값(날짜·rating)은 최근 엔트리를 쓰되, 대표 사진은 최근 엔트리부터
        // 거슬러 첫 사진을 고른다 — 최근 기록에 사진이 없어도 노트에 사진이 있으면 카드에 대표 이미지가 뜬다.
        String thumb = representativeThumb(note, THUMBS_PREFIX); // 인덱스는 site/ 기준 → thumbs/…
        return new SiteView.Row(
                "notes/" + note.slug() + ".html",
                note.coffeeName(),
                value(note.roastery()),
                value(note.origin()),
                latest == null ? null : latest.date(),
                note.entries().size(),
                latest == null ? null : latest.rating(),
                thumb);
    }

    // --- 노트 상세 ---

    private void writeNote(Note note) {
        // entries는 날짜 오름차순 유지(ADR-4) → 그대로 시간순 타임라인이 된다(AC-13).
        List<Entry> entries = note.entries() == null ? List.of() : note.entries();
        List<SiteView.EntryView> timeline = entries.stream().map(this::toEntryView).toList();
        SiteView.NotePage page = new SiteView.NotePage(
                note.slug(),
                note.coffeeName(),
                note.roastery(),
                note.origin(),
                note.process(),
                note.roastLevel(),
                note.officialNotes() == null ? List.of() : note.officialNotes().value(),
                note.sources() == null ? List.of() : note.sources(),
                timeline);

        Context ctx = baseContext();
        ctx.setVariable("note", page);
        ctx.setVariable("indexHref", "../index.html");
        ctx.setVariable("mascotHref", "../" + MASCOT_NAME);
        writeHtml(siteDir.resolve("notes").resolve(note.slug() + ".html"), render("note", ctx));
    }

    // 노트 상세는 notes/ 하위이므로 썸네일은 ../thumbs/… 로 참조한다(상대 경로, AC-11).
    private SiteView.EntryView toEntryView(Entry entry) {
        return new SiteView.EntryView(
                entry.date(),
                entry.myTaste(),
                entry.rating(),
                thumbs(entry, "../" + THUMBS_PREFIX));
    }

    // --- 공통 ---

    private Context baseContext() {
        Context ctx = new Context(Locale.KOREAN);
        ctx.setVariable("fmt", dates);
        ctx.setVariable("rs", ratingStyle);
        ctx.setVariable("mascotHref", MASCOT_NAME); // 인덱스는 site/ 기준(상세는 위에서 ../로 덮어씀)
        return ctx;
    }

    private String render(String templateName, Context ctx) {
        return templateEngine.process(theme.id() + "/" + templateName, ctx);
    }

    // 노트의 대표 썸네일 — 최근 엔트리부터 거슬러 첫 사진을 고른다(FR-8). 어느 엔트리에도 사진이 없으면 null.
    private static String representativeThumb(Note note, String prefix) {
        List<Entry> entries = note.entries();
        if (entries == null) {
            return null;
        }
        for (int i = entries.size() - 1; i >= 0; i--) {
            String thumb = firstThumb(entries.get(i), prefix);
            if (thumb != null) {
                return thumb;
            }
        }
        return null;
    }

    // photos[0]을 썸네일 상대 경로로. photos/<slug>/<date>/name → <prefix><slug>/<date>/name. 없으면 null.
    private static String firstThumb(Entry entry, String prefix) {
        if (entry == null || entry.photos() == null || entry.photos().isEmpty()) {
            return null;
        }
        return toThumb(entry.photos().get(0), prefix);
    }

    private static List<String> thumbs(Entry entry, String prefix) {
        if (entry.photos() == null) {
            return List.of();
        }
        return entry.photos().stream().map(p -> toThumb(p, prefix)).toList();
    }

    private static String toThumb(String photoRel, String prefix) {
        // 저장 규칙상 photos/ 접두 상대 경로(V-4). 접두만 썸네일 접두로 치환한다.
        String tail = photoRel.startsWith(PHOTOS_PREFIX) ? photoRel.substring(PHOTOS_PREFIX.length()) : photoRel;
        return prefix + tail;
    }

    private static String value(Sourced<String> sourced) {
        return sourced == null ? null : sourced.value();
    }

    private static Entry latestEntry(Note note) {
        List<Entry> entries = note.entries();
        if (entries == null || entries.isEmpty()) {
            return null;
        }
        // entries는 날짜 오름차순 유지(ADR-4) — 마지막이 최근.
        return entries.get(entries.size() - 1);
    }

    private static LocalDate latestDate(Note note) {
        Entry latest = latestEntry(note);
        return latest == null ? LocalDate.MIN : latest.date();
    }

    private void writeHtml(Path target, String html) {
        try {
            Files.createDirectories(target.getParent());
            Files.writeString(target, html, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("HTML 저장 실패: " + target, e);
        }
    }

    // 템플릿이 참조하는 마스코트 자산을 site/ 루트로 복사한다(상대 경로 참조 대상, AC-11).
    private void copyMascot() {
        try (InputStream in = getClass().getResourceAsStream(MASCOT_RESOURCE)) {
            if (in == null) {
                log.warn("마스코트 자산을 클래스패스에서 못 찾음: {} — 이미지 링크가 깨질 수 있음", MASCOT_RESOURCE);
                return;
            }
            Files.createDirectories(siteDir);
            Files.write(siteDir.resolve(MASCOT_NAME), in.readAllBytes());
        } catch (IOException e) {
            throw new UncheckedIOException("마스코트 자산 복사 실패", e);
        }
    }
}
