package com.devwuu.mocha.repository;

import com.devwuu.mocha.domain.Entry;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.NoteMeta;
import com.devwuu.mocha.domain.Rating;
import com.devwuu.mocha.domain.Sourced;
import com.devwuu.mocha.json.MochaObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * T1-2: JsonFileNoteRepository — 임시 디렉토리 실제 파일 I/O로 검증(CLAUDE.md §5.2).
 * <p>날짜 병합(AC-13/AC-14), slug 유일성(V-2), 저장 후 로드 왕복.
 */
class JsonFileNoteRepositoryTest {

    private static final Clock FIXED =
            Clock.fixed(Instant.parse("2026-07-10T00:30:00Z"), ZoneId.of("Asia/Seoul"));

    private JsonFileNoteRepository repo;

    @BeforeEach
    void setUp(@TempDir java.nio.file.Path dataDir) {
        repo = new JsonFileNoteRepository(dataDir, MochaObjectMapper.create(), FIXED);
    }

    private static NoteMeta sampleMeta() {
        return new NoteMeta(
                Sourced.user("커피베라 예가체프 G1"),
                Sourced.user("커피베라"),
                Sourced.search("에티오피아 예가체프"),
                Sourced.search("워시드"),
                new Sourced<>(null, com.devwuu.mocha.domain.Source.SEARCH),
                Sourced.search(List.of("자스민", "베르가못")),
                List.of("https://example.com/coffeevera")
        );
    }

    private static Entry entry(LocalDate date, String taste) {
        return new Entry(date, taste, Rating.GOOD, null, OffsetDateTime.now(FIXED));
    }

    @Test
    @DisplayName("AC-14: 같은 날 재기록 시 엔트리 수 불변(갱신만)")
    void sameDayUpsertKeepsSingleEntry() {
        LocalDate today = LocalDate.of(2026, 7, 10);
        repo.upsertEntry("coffeevera-yirgacheffe-g1", sampleMeta(), entry(today, "새콤하고 좋았다"));
        Note note = repo.upsertEntry("coffeevera-yirgacheffe-g1", sampleMeta(), entry(today, "오늘은 좀 밍밍"));

        assertThat(note.entries()).hasSize(1);
        assertThat(note.entries().getFirst().myTaste()).isEqualTo("오늘은 좀 밍밍");
        // 파일에서도 1개로 로드
        assertThat(repo.findBySlug("coffeevera-yirgacheffe-g1")).get()
                .extracting(n -> n.entries().size()).isEqualTo(1);
    }

    @Test
    @DisplayName("AC-13: 다른 날 재기록 시 엔트리 추가 + 날짜 오름차순 정렬")
    void differentDayAppendsSortedByDate() {
        String slug = "coffeevera-yirgacheffe-g1";
        // 나중 날짜를 먼저 저장해 정렬이 실제로 일어나는지 확인
        repo.upsertEntry(slug, sampleMeta(), entry(LocalDate.of(2026, 7, 10), "10일"));
        Note note = repo.upsertEntry(slug, sampleMeta(), entry(LocalDate.of(2026, 7, 9), "9일"));

        assertThat(note.entries())
                .extracting(Entry::date)
                .containsExactly(LocalDate.of(2026, 7, 9), LocalDate.of(2026, 7, 10));
    }

    @Test
    @DisplayName("V-2: 신규 slug 충돌 시 -2, -3 접미로 유일성 보장")
    void nextAvailableSlugSuffixesOnCollision() {
        assertThat(repo.nextAvailableSlug("coffeevera")).isEqualTo("coffeevera");

        repo.upsertEntry("coffeevera", sampleMeta(), entry(LocalDate.of(2026, 7, 10), "첫 노트"));
        assertThat(repo.nextAvailableSlug("coffeevera")).isEqualTo("coffeevera-2");

        repo.upsertEntry("coffeevera-2", sampleMeta(), entry(LocalDate.of(2026, 7, 10), "둘째 노트"));
        assertThat(repo.nextAvailableSlug("coffeevera")).isEqualTo("coffeevera-3");
    }

    @Test
    @DisplayName("V-2: slug 형식 위반은 거부")
    void invalidSlugRejected() {
        assertThatThrownBy(() -> repo.nextAvailableSlug("커피베라 G1"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("저장 후 새 조회로 동일 노트 복원(왕복) + findAll은 slug 순")
    void persistsAndReloads() {
        LocalDate today = LocalDate.of(2026, 7, 10);
        Note saved = repo.upsertEntry("bravo", sampleMeta(), entry(today, "b"));
        repo.upsertEntry("alpha", sampleMeta(), entry(today, "a"));

        assertThat(repo.findBySlug("bravo")).contains(saved);
        assertThat(repo.findAll()).extracting(Note::slug).containsExactly("alpha", "bravo");
    }

    @Test
    @DisplayName("빈 저장소는 findAll 빈 리스트 / findBySlug 빈 Optional")
    void emptyRepository() {
        assertThat(repo.findAll()).isEmpty();
        assertThat(repo.findBySlug("nope")).isEmpty();
    }

    // ── applyEdit — 수정 세션 커밋 (changes/0012 TΔ2, ADR-27) ──────────────────

    /** 저장된 노트에서 대상 엔트리 1건만 실은 edit draft를 만든다(data-model §2.3). */
    private static Note editDraft(Note base, Entry edited) {
        return new Note(
                base.slug(), base.coffeeName(), base.roastery(), base.origin(), base.process(),
                base.roastLevel(), base.officialNotes(), base.sources(),
                List.of(edited), base.createdAt(), base.updatedAt()
        );
    }

    @Test
    @DisplayName("AC-Δ2(일부): applyEdit 필드 갱신 — 같은 date의 엔트리·노트 필드가 draft로 갱신")
    void applyEditUpdatesFields() {
        String slug = "coffeevera-yirgacheffe-g1";
        LocalDate target = LocalDate.of(2026, 7, 10);
        Note saved = repo.upsertEntry(slug, sampleMeta(), entry(target, "새콤하고 좋았다"));

        Note draft = new Note(
                saved.slug(), saved.coffeeName(),
                Sourced.user("커피베라 성수점"), // 노트 단위 필드도 수정 범위(커피명 제외 전부)
                saved.origin(), saved.process(), saved.roastLevel(), saved.officialNotes(),
                saved.sources(), List.of(entry(target, "다시 보니 복숭아향")),
                saved.createdAt(), saved.updatedAt()
        );
        Note updated = repo.applyEdit(slug, target, draft);

        assertThat(updated.entries()).hasSize(1);
        assertThat(updated.entries().getFirst().myTaste()).isEqualTo("다시 보니 복숭아향");
        assertThat(updated.roastery().value()).isEqualTo("커피베라 성수점");
        // 파일에서도 동일 복원(원자적 쓰기 왕복)
        assertThat(repo.findBySlug(slug)).contains(updated);
    }

    @Test
    @DisplayName("AC-Δ3(일부): applyEdit 무충돌 날짜 이동 — 옛 date 제거·새 date 추가·정렬 유지, 총수 불변")
    void applyEditMovesDateWithoutConflict() {
        String slug = "coffeevera-yirgacheffe-g1";
        repo.upsertEntry(slug, sampleMeta(), entry(LocalDate.of(2026, 7, 9), "9일"));
        Note saved = repo.upsertEntry(slug, sampleMeta(), entry(LocalDate.of(2026, 7, 10), "10일"));

        // 7/10 엔트리를 7/11로 이동
        Note draft = editDraft(saved, entry(LocalDate.of(2026, 7, 11), "사실 11일에 마심"));
        Note updated = repo.applyEdit(slug, LocalDate.of(2026, 7, 10), draft);

        assertThat(updated.entries())
                .extracting(Entry::date)
                .containsExactly(LocalDate.of(2026, 7, 9), LocalDate.of(2026, 7, 11));
        assertThat(updated.entries().getLast().myTaste()).isEqualTo("사실 11일에 마심");
        assertThat(repo.findBySlug(slug)).contains(updated);
    }

    @Test
    @DisplayName("V-10: applyEdit 날짜 이동 충돌 시 대상 덮어쓰기 + 원본 date 제거 — 엔트리 총수 1 감소")
    void applyEditOverwritesOnDateConflict() {
        String slug = "coffeevera-yirgacheffe-g1";
        repo.upsertEntry(slug, sampleMeta(), entry(LocalDate.of(2026, 7, 9), "9일 원본"));
        Note saved = repo.upsertEntry(slug, sampleMeta(), entry(LocalDate.of(2026, 7, 10), "10일 원본"));
        assertThat(saved.entries()).hasSize(2);

        // 7/9 엔트리를 7/10으로 이동 — 기존 7/10 엔트리와 충돌 → 덮어쓰기
        Note draft = editDraft(saved, entry(LocalDate.of(2026, 7, 10), "이동해 온 9일 기록"));
        Note updated = repo.applyEdit(slug, LocalDate.of(2026, 7, 9), draft);

        assertThat(updated.entries()).hasSize(1); // 총수 2 → 1
        assertThat(updated.entries().getFirst().date()).isEqualTo(LocalDate.of(2026, 7, 10));
        assertThat(updated.entries().getFirst().myTaste()).isEqualTo("이동해 온 9일 기록");
        assertThat(repo.findBySlug(slug)).get()
                .extracting(n -> n.entries().size()).isEqualTo(1);
    }

    @Test
    @DisplayName("V-9: applyEdit coffee_name 변경 시도 거부 — 커밋 없음, 원본 무변화")
    void applyEditRejectsCoffeeNameChange() {
        String slug = "coffeevera-yirgacheffe-g1";
        LocalDate target = LocalDate.of(2026, 7, 10);
        Note saved = repo.upsertEntry(slug, sampleMeta(), entry(target, "원본 감상"));

        Note draft = new Note(
                saved.slug(),
                Sourced.user("커피베라 예가체프 G2"), // 오타 정정 포함 예외 없이 거부(V-9)
                saved.roastery(), saved.origin(), saved.process(), saved.roastLevel(),
                saved.officialNotes(), saved.sources(),
                List.of(entry(target, "감상 수정")),
                saved.createdAt(), saved.updatedAt()
        );

        assertThatThrownBy(() -> repo.applyEdit(slug, target, draft))
                .isInstanceOf(IllegalArgumentException.class);
        // 거부 시 원본 노트 무변화
        assertThat(repo.findBySlug(slug)).contains(saved);
    }
}
