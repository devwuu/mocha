package com.devwuu.mocha.repository;

import com.devwuu.mocha.domain.Aliases;
import com.devwuu.mocha.domain.Entry;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.NoteMeta;
import com.devwuu.mocha.domain.Sourced;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * {@code data/notes/<slug>.json} 파일 기반 NoteRepository (ref: plan.md#ADR-8).
 * <p>JSON이 유일한 원본(ADR-1). 쓰기는 임시 파일 → move(replace) 원자적 반영으로
 * 쓰기 도중 죽어도 원본이 깨지지 않게 한다(CLAUDE.md §3).
 */
public class JsonFileNoteRepository implements NoteRepository {

    private static final Logger log = LoggerFactory.getLogger(JsonFileNoteRepository.class);

    private static final Pattern SLUG = Pattern.compile("[a-z0-9-]+");

    private final Path notesDir;
    private final ObjectMapper mapper;
    private final Clock clock;

    // 시계(Asia/Seoul — V-3)는 config 공통 빈 주입(ADR-63), 테스트에서 시간 고정용.
    public JsonFileNoteRepository(Path dataDir, ObjectMapper mapper, Clock clock) {
        this.notesDir = dataDir.resolve("notes");
        this.mapper = mapper;
        this.clock = Objects.requireNonNull(clock, "clock");
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
    public Note upsertEntry(String slug, NoteMeta meta, Entry entry, Aliases aliases) {
        // 신규 노트면 별칭을 심고(AliasGenerator 산출, TΔ2), 기존 노트면 별칭 원본 존치(축적은 TΔ3, V-13).
        Note note = findBySlug(slug)
                .map(existing -> withMergedEntry(existing, meta, entry))
                .orElseGet(() -> createNote(slug, meta, entry, aliases));
        write(note);
        return note;
    }

    // 기존 노트에 엔트리 병합: 같은 date는 갱신, 다른 date는 추가 후 날짜 오름차순 정렬(ADR-4).
    // POLICY: 노트 단위 메타(원두 구성·로스팅 등 커피의 사실)는 커피 1종 단위로 안정적이므로
    //         재기록 시 갱신하지 않고 보존한다 — 재기록은 그날의 엔트리를 쌓는 일이다(ADR-4, beans 승계).
    // POLICY: 감상·레시피는 회차(brews) 안에만 — 회차 병합은 append 기본, 기존 회차 수정은 명시 지칭 시에만.
    //         그 병합 배열은 에이전트가 구성하고(V-15 검증 통과분) 서버는 신뢰해 같은 date 엔트리를 통째
    //         교체한다 — 회차 단위 서버 병합 없음 (ref: plan.md#ADR-59, data-model.md#2.2, V-15).
    private Note withMergedEntry(Note existing, NoteMeta meta, Entry entry) {
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
        // TΔ3: EXISTING 매칭 커밋 — 이번 기록의 커피명·로스터리 관측 표기(meta 유래, 추출·OCR)를 별칭에
        //      무콜 축적한다. 노트 표시값과 같은 표기는 넣지 않고, 다른 표기만 정규화 중복 제거로 더한다.
        //      노트 단위 메타(원두 구성 등)는 종전대로 갱신하지 않고 보존한다(ADR-4, V-13, ADR-37).
        Aliases accumulated = existing.aliases().accumulate(
                Sourced.valueOrNull(meta.coffeeName()), Sourced.valueOrNull(existing.coffeeName()),
                Sourced.valueOrNull(meta.roastery()), Sourced.valueOrNull(existing.roastery()));
        return new Note(
                existing.slug(),
                existing.coffeeName(),
                existing.roastery(),
                existing.beans(),
                existing.roastLevel(),
                existing.officialNotes(),
                accumulated,
                existing.sources(),
                List.copyOf(merged),
                existing.createdAt(),
                OffsetDateTime.now(clock)
        );
    }

    @Override
    public Note applyEdit(String slug, LocalDate targetDate, Note draft) {
        Note existing = findBySlug(slug)
                .orElseThrow(() -> new IllegalStateException("수정 대상 노트 소실: " + slug));
        // POLICY: coffee_name은 노트 생성 후 불변 — 수정 세션에서도 변경 불가, 다른 값이면 커밋 거부
        //         (ref: specs/coffee-note-agent/data-model.md#V-9).
        if (!existing.coffeeName().value().equals(draft.coffeeName().value())) {
            throw new IllegalArgumentException(
                    "coffee_name은 노트 생성 후 불변(V-9): " + slug);
        }
        if (draft.entries().size() != 1) {
            throw new IllegalArgumentException(
                    "edit draft는 대상 엔트리 1건만 담는다(data-model §2.3): " + draft.entries().size() + "건");
        }
        if (existing.entries().stream().noneMatch(e -> e.date().equals(targetDate))) {
            throw new IllegalStateException("수정 대상 엔트리 소실: " + slug + " " + targetDate);
        }
        Entry edited = draft.entries().getFirst();
        // POLICY: 날짜 이동으로 이동처 date에 기존 엔트리가 있으면 덮어쓰고 원본 date는 제거 —
        //         엔트리 총수 1 감소. 경고 표기는 미리보기(V-10 전반부)의 몫
        //         (ref: specs/coffee-note-agent/data-model.md#V-10).
        List<Entry> entries = new ArrayList<>(existing.entries().stream()
                .filter(e -> !e.date().equals(targetDate) && !e.date().equals(edited.date()))
                .toList());
        entries.add(edited);
        entries.sort(Comparator.comparing(Entry::date));
        Note updated = new Note(
                existing.slug(),
                existing.coffeeName(), // V-9 이중 방어: draft 값이 아니라 원본을 쓴다
                draft.roastery(),
                draft.beans(),
                draft.roastLevel(),
                draft.officialNotes(),
                existing.aliases(), // 수정 세션은 별칭을 건드리지 않는다 — 원본 존치(V-13)
                draft.sources(),
                List.copyOf(entries),
                existing.createdAt(),
                OffsetDateTime.now(clock)
        );
        write(updated);
        return updated;
    }

    private Note createNote(String slug, NoteMeta meta, Entry entry, Aliases aliases) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        return new Note(
                slug,
                meta.coffeeName(),
                meta.roastery(),
                meta.beans(),
                meta.roastLevel(),
                meta.officialNotes(),
                aliases == null ? Aliases.empty() : aliases,
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

    // 단건(findBySlug)·전체(findAll) 조회 공용 읽기 지점 — 로드 위생은 여기 한 곳에 건다.
    private Note read(Path file) {
        try {
            Note note = mapper.readValue(Files.readAllBytes(file), Note.class);
            // POLICY: 저장소 읽기 경계가 유일한 무결성 관문 — 수기 편집 JSON의 무효 요소(beans V-14,
            //         회차 V-15·recipe V-8)는 저장 경로와 동일한 정규화로 로드 시 드롭한다. 앱이 쓴
            //         데이터엔 no-op. 파일은 다시 쓰지 않는다 — 읽기 메모리 정규화만. 단, 드롭분은 그
            //         노트의 다음 저장에서 파일에도 반영되므로 경고 로그로 관측을 남긴다
            //         (ref: specs/coffee-note-agent/plan.md#ADR-66, data-model.md#V-8·V-14).
            Note sanitized = note.normalized();
            if (sanitized != note) {
                log.warn("노트 로드 위생(ADR-66): 무효 요소 드롭 — {} (수기 편집 위반 추정, 파일은 다시 쓰지 않음. 드롭분은 다음 저장 시 파일에 반영됨)",
                        file.getFileName());
            }
            return sanitized;
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
