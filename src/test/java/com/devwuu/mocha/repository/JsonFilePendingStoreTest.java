package com.devwuu.mocha.repository;

import com.devwuu.mocha.domain.Entry;
import com.devwuu.mocha.domain.MatchInfo;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.PendingNote;
import com.devwuu.mocha.domain.Rating;
import com.devwuu.mocha.domain.Source;
import com.devwuu.mocha.domain.Sourced;
import com.devwuu.mocha.json.MochaObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * T1-3: JsonFilePendingStore — 임시 디렉토리 실제 파일 I/O로 검증(CLAUDE.md §5.2).
 * <p>재시작 생존(AC-7/NFR-2), TTL 만료 처리(V-7).
 */
class JsonFilePendingStoreTest {

    private static final ZoneId SEOUL = ZoneId.of("Asia/Seoul");
    private static final Instant NOW = Instant.parse("2026-07-10T00:30:00Z");
    private static final Clock FIXED = Clock.fixed(NOW, SEOUL);
    private static final Duration TTL = Duration.ofHours(24);
    private static final String USER = "U123";

    private static PendingNote sampleDraft(OffsetDateTime createdAt) {
        Note draft = new Note(
                "coffeevera-yirgacheffe-g1",
                Sourced.user("커피베라 예가체프 G1"),
                Sourced.user("커피베라"),
                Sourced.search("에티오피아 예가체프"),
                Sourced.search("워시드"),
                new Sourced<>(null, Source.SEARCH),
                Sourced.search(List.of("자스민", "베르가못")),
                List.of("https://example.com/coffeevera"),
                List.of(new Entry(LocalDate.of(2026, 7, 10), "새콤하고 좋았다", Rating.GOOD, null, List.of(), createdAt)),
                createdAt,
                createdAt
        );
        return new PendingNote(draft, MatchInfo.newNote(), "1720570200.000100", createdAt);
    }

    @Test
    @DisplayName("AC-7/NFR-2: 저장 후 새 인스턴스로 로드해 동일 draft 복원")
    void persistsAndReloadsAcrossInstances() {
        OffsetDateTime createdAt = OffsetDateTime.now(FIXED);
        PendingNote pending = sampleDraft(createdAt);

        new JsonFilePendingStore(dataDir, MochaObjectMapper.create(), TTL, FIXED).put(USER, pending);

        // 프로세스 재시작을 흉내내 별개 인스턴스로 조회.
        PendingStore reopened = new JsonFilePendingStore(dataDir, MochaObjectMapper.create(), TTL, FIXED);
        assertThat(reopened.get(USER)).contains(pending);
    }

    @Test
    @DisplayName("V-7: TTL 초과 pending은 get에서 만료 처리(빈 Optional)")
    void expiredPendingIsNotReturned() {
        // 25시간 전 생성 → TTL(24h) 초과.
        OffsetDateTime stale = OffsetDateTime.now(FIXED).minusHours(25);
        JsonFilePendingStore store = new JsonFilePendingStore(dataDir, MochaObjectMapper.create(), TTL, FIXED);
        store.put(USER, sampleDraft(stale));

        assertThat(store.get(USER)).isEmpty();
    }

    @Test
    @DisplayName("V-7 경계: TTL 이내 pending은 정상 조회")
    void freshPendingIsReturned() {
        OffsetDateTime recent = OffsetDateTime.now(FIXED).minusHours(23);
        JsonFilePendingStore store = new JsonFilePendingStore(dataDir, MochaObjectMapper.create(), TTL, FIXED);
        PendingNote pending = sampleDraft(recent);
        store.put(USER, pending);

        assertThat(store.get(USER)).contains(pending);
    }

    // --- 0012 TΔ1: edit 모드 pending (FR-21) ---

    @Test
    @DisplayName("0012-TΔ1/AC-Δ5: edit 모드 pending은 재시작 후에도 mode·target이 보존된다")
    void editModePendingSurvivesRestart() {
        OffsetDateTime createdAt = OffsetDateTime.now(FIXED);
        PendingNote record = sampleDraft(createdAt);
        PendingNote editPending = new PendingNote(
                PendingNote.Mode.EDIT,
                record.draft(),
                new PendingNote.EditTarget("coffeevera-yirgacheffe-g1", LocalDate.of(2026, 7, 9)),
                null,
                record.previewTs(),
                createdAt
        );

        new JsonFilePendingStore(dataDir, MochaObjectMapper.create(), TTL, FIXED).put(USER, editPending);

        // 프로세스 재시작을 흉내내 별개 인스턴스로 조회 — 수정 draft 생존(AC-40).
        PendingStore reopened = new JsonFilePendingStore(dataDir, MochaObjectMapper.create(), TTL, FIXED);
        assertThat(reopened.get(USER)).contains(editPending);
        assertThat(reopened.get(USER).orElseThrow().mode()).isEqualTo(PendingNote.Mode.EDIT);
        assertThat(reopened.get(USER).orElseThrow().target())
                .isEqualTo(new PendingNote.EditTarget("coffeevera-yirgacheffe-g1", LocalDate.of(2026, 7, 9)));
    }

    // (0012 이전 pending.json 하위 호환 테스트는 제거 — 기존 데이터는 배포 전 수동 삭제로 결정, delta 비범위.)

    @Test
    @DisplayName("clear 후 조회는 빈 Optional / 부재 시에도 빈 Optional")
    void clearAndAbsent() {
        JsonFilePendingStore store = new JsonFilePendingStore(dataDir, MochaObjectMapper.create(), TTL, FIXED);
        assertThat(store.get(USER)).isEmpty();

        store.put(USER, sampleDraft(OffsetDateTime.now(FIXED)));
        assertThat(store.get(USER)).isPresent();

        store.clear(USER);
        assertThat(store.get(USER)).isEmpty();
    }

    @TempDir
    Path dataDir;
}
