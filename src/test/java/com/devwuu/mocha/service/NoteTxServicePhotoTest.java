package com.devwuu.mocha.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.devwuu.mocha.domain.Aliases;
import com.devwuu.mocha.domain.Brew;
import com.devwuu.mocha.domain.Entry;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.NoteMeta;
import com.devwuu.mocha.domain.Rating;
import com.devwuu.mocha.domain.Source;
import com.devwuu.mocha.domain.Sourced;
import com.devwuu.mocha.domain.Tasting;
import com.devwuu.mocha.repository.jpa.NoteEntityRepository;
import com.devwuu.mocha.support.PostgresIntegrationTest;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * TΔ8b (changes/0029-app-interface) — {@code note_photo} 색인의 트랜잭션 계층 검증
 * (ref: delta.md#D-5, plan.md#ADR-79).
 *
 * <p>실 Postgres에 붙는 이유는 검증 대상이 <b>제약과 순서</b>라서다(백엔드 CLAUDE.md §5.2):
 * {@code UNIQUE(note_id, tasted_on, seq)}가 seq 이어붙이기를 실제로 강제하는지, 삭제가 사진 행까지
 * 걷는지는 인메모리 대체로는 아무것도 보증하지 않는다.
 */
@Transactional
class NoteTxServicePhotoTest extends PostgresIntegrationTest {

    @Autowired
    EntityManager em;

    @Autowired
    NoteEntityRepository notes;

    /** 네이티브 SQL이 향할 스키마 — JPQL과 같은 행을 보게 한다(0028 TΔ5b가 밟은 {@code search_path} 함정). */
    @Value("${spring.jpa.properties.hibernate.default_schema}")
    String schema;

    NoteTxService tx;

    @BeforeEach
    void setUp() {
        tx = new NoteTxService(notes);
    }

    @Test
    @DisplayName("TΔ8b: attachPhotos가 준 순서 그대로 색인되고 (날짜, seq) 순으로 되읽힌다")
    void attachedPhotosKeepOrder() {
        Note saved = seed();

        tx.attachPhotos(saved.id(), day(10), List.of(
                folderPath(saved, 10, "bag.jpg"), folderPath(saved, 10, "label.jpg")));
        flushAndClear();

        assertThat(tx.photoPaths(saved.id())).containsExactly(
                folderPath(saved, 10, "bag.jpg"), folderPath(saved, 10, "label.jpg"));
    }

    @Test
    @DisplayName("TΔ8b: 같은 날짜를 다시 저장하면 사진은 쌓인다 — seq가 이어지고 UNIQUE에 걸리지 않는다")
    void sameDateAccumulatesPhotos() {
        // 엔트리는 통째로 교체되지만(ADR-4·59) 사진은 아카이브에 그대로 남아 있다 — 지우는 것이
        // 재기록의 뜻이 아니므로 색인도 함께 쌓인다. seq를 0부터 다시 매기면 UNIQUE가 막는다.
        Note saved = seed();
        tx.attachPhotos(saved.id(), day(10), List.of(folderPath(saved, 10, "bag.jpg")));
        flushAndClear();

        tx.attachPhotos(saved.id(), day(10), List.of(folderPath(saved, 10, "bag-2.jpg")));
        flushAndClear();

        assertThat(tx.photoPaths(saved.id())).containsExactly(
                folderPath(saved, 10, "bag.jpg"), folderPath(saved, 10, "bag-2.jpg"));
    }

    @Test
    @DisplayName("TΔ8b: 날짜가 다르면 seq는 각자 0부터 — 축이 (노트, 날짜)라는 뜻이다")
    void seqIsScopedToDate() {
        Note saved = seed();

        tx.attachPhotos(saved.id(), day(9), List.of(folderPath(saved, 9, "a.jpg")));
        tx.attachPhotos(saved.id(), day(10), List.of(folderPath(saved, 10, "b.jpg")));
        flushAndClear();

        List<Integer> seqs = em.createNativeQuery(
                        "SELECT seq FROM " + schema + ".note_photo WHERE note_id = " + saved.id()
                                + " ORDER BY tasted_on")
                .getResultList().stream().map(v -> ((Number) v).intValue()).toList();
        assertThat(seqs).containsExactly(0, 0);
    }

    @Test
    @DisplayName("TΔ8b: 빈 목록은 아무 행도 만들지 않는다 — 사진 없는 저장이 정상 경로다")
    void emptyAttachIsNoOp() {
        Note saved = seed();

        tx.attachPhotos(saved.id(), day(10), List.of());
        flushAndClear();

        assertThat(tx.photoPaths(saved.id())).isEmpty();
    }

    @Test
    @DisplayName("AC-Δ8: 노트 삭제가 사진 색인까지 걷는다 — FK가 없어 여기서 빠지면 고아가 조용히 남는다")
    void deleteRemovesPhotoRows() {
        Note doomed = seed();
        Note survivor = seed();
        tx.attachPhotos(doomed.id(), day(10), List.of(folderPath(doomed, 10, "bag.jpg")));
        tx.attachPhotos(survivor.id(), day(10), List.of(folderPath(survivor, 10, "bag.jpg")));
        flushAndClear();

        tx.delete(doomed.id());
        flushAndClear();

        assertThat(tx.photoPaths(doomed.id())).isEmpty();
        // WHERE를 빠뜨려 테이블을 통째로 비우는 구현도 위 단언은 통과한다 — 남아야 할 것으로 가른다.
        assertThat(tx.photoPaths(survivor.id())).hasSize(1);
    }

    @Test
    @DisplayName("TΔ8b: 같은 (노트, 날짜, seq)는 UNIQUE가 막는다 — 색인 중복이 조용히 쌓이지 않는다")
    void duplicateSeqIsRejected() {
        Note saved = seed();
        tx.attachPhotos(saved.id(), day(10), List.of(folderPath(saved, 10, "bag.jpg")));
        flushAndClear();

        // 저장소를 우회해 같은 seq를 직접 심는다 — 제약이 스키마에 실제로 올라와 있는지가 검증 대상이다.
        assertThatThrownBy(() -> {
            em.createNativeQuery("INSERT INTO " + schema + ".note_photo (note_id, tasted_on, seq, path)"
                            + " VALUES (" + saved.id() + ", DATE '2026-07-10', 0, 'photos/x/2026-07-10/y.jpg')")
                    .executeUpdate();
            em.flush();
            // 제약 위반이 실제로 그 이름을 달고 올라온다 — 문법 오류가 아니라 UNIQUE가 막은 것이다.
        }).rootCause().hasMessageContaining("note_photo");
    }

    // ────────────────────────────── 표본·헬퍼 ──────────────────────────────

    private Note seed() {
        return tx.commit(null, meta(), entry(day(10)), Aliases.empty());
    }

    private static NoteMeta meta() {
        return new NoteMeta(
                new Sourced<>("커피베라 예가체프 G1", Source.USER), new Sourced<>("커피베라", Source.USER),
                List.of(), null, null, List.of());
    }

    private static Entry entry(LocalDate date) {
        return new Entry(date, List.of(new Brew(null, new Tasting("감상", "감상", Rating.GOOD))),
                OffsetDateTime.of(2000, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC));
    }

    private static LocalDate day(int dayOfMonth) {
        return LocalDate.of(2026, 7, dayOfMonth);
    }

    /** 실제 커밋이 남기는 모양의 상대 경로 — {@code photos/<접미>/<date>/<파일>}(V-4 어휘). */
    private static String folderPath(Note note, int dayOfMonth, String filename) {
        return "photos/" + note.id() + "-커피베라-커피베라 예가체프 G1/2026-07-%02d/".formatted(dayOfMonth) + filename;
    }

    private void flushAndClear() {
        em.flush();
        em.clear();
    }
}
