package com.devwuu.mocha.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.devwuu.mocha.domain.Aliases;
import com.devwuu.mocha.domain.Cup;
import com.devwuu.mocha.domain.Entry;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.NoteDetail;
import com.devwuu.mocha.domain.NoteMeta;
import com.devwuu.mocha.domain.Rating;
import com.devwuu.mocha.domain.Source;
import com.devwuu.mocha.domain.Sourced;
import com.devwuu.mocha.domain.Review;
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
import java.util.Map;

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

    // ────────────────────────────── 날짜 이동 (TΔ5b-2) ──────────────────────────────

    @Test
    @DisplayName("D-12 ③: 날짜 이동이 사진 색인을 데리고 간다 — tasted_on과 경로가 함께 새 날짜다")
    void dateMoveCarriesPhotoRows() {
        // 참조 축이 (note_id, tasted_on)이라 엔트리만 옮기면 사진이 옛 날짜에 남아 어느 화면에도 보이지
        // 않는다(TΔ8b 이월 (a)). 경로는 계산이 아니라 파일이 실제로 간 자리를 받아 적는다.
        Note saved = seed();
        tx.attachPhotos(saved.id(), day(10), List.of(folderPath(saved, 10, "bag.jpg")));
        flushAndClear();

        tx.replaceEntry(saved.id(), day(10), entry(day(11)),
                Map.of(folderPath(saved, 10, "bag.jpg"), folderPath(saved, 11, "bag.jpg")));
        flushAndClear();

        NoteDetail detail = tx.findDetail(saved.id()).orElseThrow();
        assertThat(detail.photosOn()).containsOnlyKeys(day(11));
        assertThat(detail.photosOn().get(day(11))).containsExactly(folderPath(saved, 11, "bag.jpg"));
    }

    @Test
    @DisplayName("D-12 ③: 병합이면 이동처 사진 뒤로 seq를 이어 발급한다 — UNIQUE라 선택이 아니라 제약이다")
    void mergeAppendsPhotosAfterExisting() {
        Note saved = seed();
        tx.commit(saved.id(), meta(), entry(day(11)), null);
        tx.attachPhotos(saved.id(), day(10), List.of(folderPath(saved, 10, "bag.jpg")));
        tx.attachPhotos(saved.id(), day(11), List.of(folderPath(saved, 11, "label.jpg")));
        flushAndClear();

        // 이동처에 같은 이름이 없어도 그날 먼저 있던 사진이 앞이다 — 회차 병합과 같은 순서(V-15).
        tx.replaceEntry(saved.id(), day(10), entry(day(11)),
                Map.of(folderPath(saved, 10, "bag.jpg"), folderPath(saved, 11, "bag.jpg")));
        flushAndClear();

        assertThat(tx.photoPaths(saved.id())).containsExactly(
                folderPath(saved, 11, "label.jpg"), folderPath(saved, 11, "bag.jpg"));
        assertThat(seqsOn(saved.id(), 11)).containsExactly(0, 1);
    }

    @Test
    @DisplayName("D-12 ③: 이동처에 같은 이름이 있으면 색인이 유일화된 실제 경로를 받는다 — 계산이 아니다")
    void mergeRecordsUniquifiedPath() {
        // 날짜 세그먼트만 갈아 끼우면 두 행이 같은 파일을 가리킨다 — 실제 자리는 파일을 옮긴 쪽만 안다.
        Note saved = seed();
        tx.commit(saved.id(), meta(), entry(day(11)), null);
        tx.attachPhotos(saved.id(), day(10), List.of(folderPath(saved, 10, "bag.jpg")));
        tx.attachPhotos(saved.id(), day(11), List.of(folderPath(saved, 11, "bag.jpg")));
        flushAndClear();

        tx.replaceEntry(saved.id(), day(10), entry(day(11)),
                Map.of(folderPath(saved, 10, "bag.jpg"), folderPath(saved, 11, "bag-2.jpg")));
        flushAndClear();

        assertThat(tx.photoPaths(saved.id())).containsExactly(
                folderPath(saved, 11, "bag.jpg"), folderPath(saved, 11, "bag-2.jpg"));
    }

    @Test
    @DisplayName("TΔ5b-3: 이동을 끝낸 트랜잭션이 사진까지 실어 돌려준다 — 화면이 새 URL을 계산하지 않는다")
    void dateMoveAnswersWithTheMovedPhotos() {
        // 되읽기를 나눠 부르면 그 사이가 "엔트리는 새 날짜인데 사진은 옛 목록"인 조합을 만든다
        // (NoteDetail의 근거가 쓰기 뒤에도 그대로 걸린다). 응답이 곧 화면의 새 기준선이다.
        Note saved = seed();
        tx.attachPhotos(saved.id(), day(10), List.of(folderPath(saved, 10, "bag.jpg")));
        flushAndClear();

        NoteDetail moved = tx.replaceEntry(saved.id(), day(10), entry(day(11)),
                Map.of(folderPath(saved, 10, "bag.jpg"), folderPath(saved, 11, "bag.jpg")));

        assertThat(moved.note().entries()).extracting(Entry::date).containsExactly(day(11));
        assertThat(moved.photosOn()).containsOnlyKeys(day(11));
        assertThat(moved.photosOn().get(day(11))).containsExactly(folderPath(saved, 11, "bag.jpg"));
    }

    @Test
    @DisplayName("TΔ5b-3: 메타 수정 응답에도 사진이 실린다 — 두 쓰기의 반환형을 가를 이유가 없다")
    void metaUpdateAnswersWithPhotosToo() {
        Note saved = seed();
        tx.attachPhotos(saved.id(), day(10), List.of(folderPath(saved, 10, "bag.jpg")));
        flushAndClear();

        NoteDetail updated = tx.updateMeta(saved.id(), meta());

        assertThat(updated.photosOn().get(day(10))).containsExactly(folderPath(saved, 10, "bag.jpg"));
    }

    @Test
    @DisplayName("TΔ5b-2: 날짜가 그대로인 수정은 사진 색인을 건드리지 않는다 — seq도 경로도 그대로")
    void sameDateEditLeavesPhotoRows() {
        Note saved = seed();
        tx.attachPhotos(saved.id(), day(10), List.of(
                folderPath(saved, 10, "bag.jpg"), folderPath(saved, 10, "label.jpg")));
        flushAndClear();

        tx.replaceEntry(saved.id(), day(10), entry(day(10)), Map.of());
        flushAndClear();

        assertThat(tx.photoPaths(saved.id())).containsExactly(
                folderPath(saved, 10, "bag.jpg"), folderPath(saved, 10, "label.jpg"));
        assertThat(seqsOn(saved.id(), 10)).containsExactly(0, 1);
    }

    // ────────────────────────────── 상세 조회 (TΔ5a) ──────────────────────────────

    @Test
    @DisplayName("TΔ5a: 상세는 노트와 사진을 함께 준다 — 사진이 (노트, 날짜)로 붙어 엔트리에 실린다")
    void detailCarriesPhotosKeyedByDate() {
        Note saved = tx.commit(null, meta(), entry(day(10)), Aliases.empty());
        tx.commit(saved.id(), meta(), entry(day(12)), null);
        tx.attachPhotos(saved.id(), day(12),
                List.of(folderPath(saved, 12, "bag.jpg"), folderPath(saved, 12, "brew.jpg")));
        flushAndClear();

        NoteDetail detail = tx.findDetail(saved.id()).orElseThrow();

        assertThat(detail.note().entries()).extracting(Entry::date).containsExactly(day(10), day(12));
        // 사진 없는 날과 있는 날이 같은 노트 안에 섞인다 — 히어로 선택이 "첫 엔트리"가 아닌 이유다.
        assertThat(detail.photosOn()).containsOnlyKeys(day(12));
        assertThat(detail.photosOn().get(day(12)))
                .containsExactly(folderPath(saved, 12, "bag.jpg"), folderPath(saved, 12, "brew.jpg"));
    }

    @Test
    @DisplayName("TΔ5a: 사진 없는 노트도 상세가 선다 — 발화만으로 기록한 노트가 정상 상태다")
    void detailWithoutPhotosIsStillADetail() {
        Note saved = seed();
        flushAndClear();

        NoteDetail detail = tx.findDetail(saved.id()).orElseThrow();

        assertThat(detail.photos()).isEmpty();
        assertThat(detail.photosOn()).isEmpty();
        assertThat(detail.note().entries()).hasSize(1);
    }

    @Test
    @DisplayName("TΔ5a: 없는 id는 빈 Optional — 호출부가 404로 수렴시킨다")
    void detailOfMissingNoteIsEmpty() {
        assertThat(tx.findDetail(999_999L)).isEmpty();
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
        return new Entry(date, List.of(new Cup(null, new Review("감상", "감상", Rating.GOOD))),
                OffsetDateTime.of(2000, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC));
    }

    private static LocalDate day(int dayOfMonth) {
        return LocalDate.of(2026, 7, dayOfMonth);
    }

    /** 실제 커밋이 남기는 모양의 상대 경로 — {@code photos/<접미>/<date>/<파일>}(V-4 어휘). */
    private static String folderPath(Note note, int dayOfMonth, String filename) {
        return "photos/" + note.id() + "-커피베라-커피베라 예가체프 G1/2026-07-%02d/".formatted(dayOfMonth) + filename;
    }

    /** 그 (노트, 날짜)의 사진 seq — 네이티브 질의로 행을 직접 본다(조립 경로는 seq를 도메인에 싣지 않는다). */
    private List<Integer> seqsOn(long noteId, int dayOfMonth) {
        return em.createNativeQuery("SELECT seq FROM " + schema + ".note_photo WHERE note_id = " + noteId
                        + " AND tasted_on = DATE '2026-07-%02d' ORDER BY seq".formatted(dayOfMonth))
                .getResultList().stream().map(v -> ((Number) v).intValue()).toList();
    }

    private void flushAndClear() {
        em.flush();
        em.clear();
    }
}
