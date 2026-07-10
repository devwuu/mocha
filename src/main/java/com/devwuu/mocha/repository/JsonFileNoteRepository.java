package com.devwuu.mocha.repository;

import com.devwuu.mocha.domain.Entry;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.NoteMeta;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * {@code data/notes/<slug>.json} 파일 기반 NoteRepository (ref: plan.md#ADR-8).
 * <p>JSON이 유일한 원본(ADR-1). 쓰기는 임시 파일 → move(replace) 원자적 반영으로
 * 쓰기 도중 죽어도 원본이 깨지지 않게 한다(CLAUDE.md §3).
 */
public class JsonFileNoteRepository implements NoteRepository {

    private static final Pattern SLUG = Pattern.compile("[a-z0-9-]+");
    // 날짜/타임스탬프는 Asia/Seoul 기준(V-3).
    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");

    private final Path notesDir;
    private final ObjectMapper mapper;
    private final Clock clock;

    public JsonFileNoteRepository(Path dataDir, ObjectMapper mapper) {
        this(dataDir, mapper, Clock.system(SEOUL));
    }

    JsonFileNoteRepository(Path dataDir, ObjectMapper mapper, Clock clock) {
        this.notesDir = dataDir.resolve("notes");
        this.mapper = mapper;
        this.clock = clock;
    }

    @Override
    public List<Note> findAll() {
        if (!Files.isDirectory(notesDir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(notesDir)) {
            return files
                    .filter(p -> p.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString()))
                    .map(this::read)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("노트 디렉토리 스캔 실패: " + notesDir, e);
        }
    }

    @Override
    public Optional<Note> findBySlug(String slug) {
        Path file = fileOf(slug);
        return Files.isRegularFile(file) ? Optional.of(read(file)) : Optional.empty();
    }

    @Override
    public String nextAvailableSlug(String base) {
        if (base == null || !SLUG.matcher(base).matches()) {
            throw new IllegalArgumentException("slug 형식이 아님([a-z0-9-]+): " + base);
        }
        if (!exists(base)) {
            return base;
        }
        // V-2: 충돌 시 -2, -3 … 로 증가.
        for (int n = 2; ; n++) {
            String candidate = base + "-" + n;
            if (!exists(candidate)) {
                return candidate;
            }
        }
    }

    @Override
    public Note upsertEntry(String slug, NoteMeta meta, Entry entry) {
        Note note = findBySlug(slug)
                .map(existing -> withMergedEntry(existing, entry))
                .orElseGet(() -> createNote(slug, meta, entry));
        write(note);
        return note;
    }

    // 기존 노트에 엔트리 병합: 같은 date는 갱신, 다른 date는 추가 후 날짜 오름차순 정렬(ADR-4).
    // POLICY: 노트 단위 메타(원산지·가공 등 커피의 사실)는 커피 1종 단위로 안정적이므로
    //         재기록 시 갱신하지 않고 보존한다 — 재기록은 그날의 엔트리를 쌓는 일이다(ADR-4).
    private Note withMergedEntry(Note existing, Entry entry) {
        List<Entry> merged = new ArrayList<>();
        boolean replaced = false;
        for (Entry e : existing.entries()) {
            if (e.date().equals(entry.date())) {
                merged.add(entry);
                replaced = true;
            } else {
                merged.add(e);
            }
        }
        if (!replaced) {
            merged.add(entry);
        }
        merged.sort(Comparator.comparing(Entry::date));
        return new Note(
                existing.slug(),
                existing.coffeeName(),
                existing.roastery(),
                existing.origin(),
                existing.process(),
                existing.roastLevel(),
                existing.officialNotes(),
                existing.sources(),
                List.copyOf(merged),
                existing.createdAt(),
                OffsetDateTime.now(clock)
        );
    }

    private Note createNote(String slug, NoteMeta meta, Entry entry) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        return new Note(
                slug,
                meta.coffeeName(),
                meta.roastery(),
                meta.origin(),
                meta.process(),
                meta.roastLevel(),
                meta.officialNotes(),
                meta.sources(),
                List.of(entry),
                now,
                now
        );
    }

    private boolean exists(String slug) {
        return Files.isRegularFile(fileOf(slug));
    }

    private Path fileOf(String slug) {
        return notesDir.resolve(slug + ".json");
    }

    private Note read(Path file) {
        try {
            return mapper.readValue(Files.readAllBytes(file), Note.class);
        } catch (IOException e) {
            throw new UncheckedIOException("노트 읽기 실패: " + file, e);
        }
    }

    private void write(Note note) {
        try {
            Files.createDirectories(notesDir);
            Path target = fileOf(note.slug());
            // 같은 디렉토리에 임시 파일로 쓴 뒤 원자적 move — .tmp는 findAll의 *.json 필터에도 안 걸린다.
            Path tmp = Files.createTempFile(notesDir, note.slug() + "-", ".tmp");
            Files.write(tmp, mapper.writeValueAsBytes(note));
            try {
                Files.move(tmp, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException notAtomic) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new UncheckedIOException("노트 저장 실패: " + note.slug(), e);
        }
    }
}
