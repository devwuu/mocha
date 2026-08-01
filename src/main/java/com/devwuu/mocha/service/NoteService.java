package com.devwuu.mocha.service;

import com.devwuu.mocha.SingleUser;
import com.devwuu.mocha.domain.Aliases;
import com.devwuu.mocha.domain.Entry;
import com.devwuu.mocha.domain.MatchInfo;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.NoteCandidate;
import com.devwuu.mocha.domain.NoteCursor;
import com.devwuu.mocha.domain.NoteDetail;
import com.devwuu.mocha.domain.NoteFilter;
import com.devwuu.mocha.domain.NoteMeta;
import com.devwuu.mocha.domain.NotePage;
import com.devwuu.mocha.domain.Sourced;
import com.devwuu.mocha.llm.AliasGenerator;
import com.devwuu.mocha.render.CardFiles;
import com.devwuu.mocha.repository.NoteFolderName;
import com.devwuu.mocha.repository.PhotoStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 노트 유스케이스 중 <b>외부 IO가 필요한 것</b>의 오케스트레이션
 * (ref: 백엔드 CLAUDE.md §2·§3, changes/0029-app-interface/delta.md#D-9, tasks TΔ4c).
 *
 * <p><b>경계</b>: 외부 호출(LLM·검색·파일)을 <b>쓰기 진입 전에</b> 끝내고 결과값만 {@link NoteTxService}에
 * 넘긴다. 원자적이어야 하는 규칙(V-9·V-10·V-13·ADR-4 병합 정책)은 전부 그 아래에 있다 — <b>이 클래스에
 * 비즈니스 규칙을 두지 않는다.</b> 여기 있는 판단은 둘뿐이고 <b>둘 다 외부 콜에 관한 것</b>이다:
 * <i>"별칭 생성 콜을 쏠 것인가"</i>({@link #commit}) · <i>"사진 폴더를 옮길 것인가"</i>
 * ({@link #replaceEntry} — 날짜가 바뀌었는가 · 옮길 사진이 있는가).
 *
 * <p>이 분리가 백엔드 CLAUDE.md §3(<i>"트랜잭션 안에서 외부 호출을 하지 않는다"</i>)을 규칙이 아니라
 * <b>계층으로</b> 지키는 방식이다 — 외부 콜이 사는 층과 트랜잭션이 열리는 층이 아예 다르다.
 *
 * <p>POLICY: 이 클래스에 {@code @Transactional}을 걸지 않는다 — 거는 순간 외부 호출이 트랜잭션 구간에
 * 들어오고, 위 성질이 구조가 아니라 규칙으로만 남는다 (ref: 백엔드 CLAUDE.md §3).
 *
 * <p><b>외부 IO가 없는 유스케이스도 여기를 지난다</b>(사용자 확정 2026-08-01, delta D-9). 상위 계층
 * (tool·렌더러·REST 컨트롤러)이 잡는 타입을 하나로 유지하기 위해서다 — 대가로 위임 한 줄 메서드가 생기고,
 * 사진 확정(TΔ8b)이 붙으며 {@link #commit}·{@link #delete}는 실질을 가졌다(카드는 TΔ9).
 *
 * <p><b>상위 계층의 테스트 격리 seam이다</b> — 구 {@code NoteRepository} 포트가 지던 역할이고, 포트가
 * TΔ4b에서 걷히며(ADR-76) 이 자리가 유일해졌다. 손 fake가 이 타입을 상속해 만들어지므로 {@code final}이
 * 아니고 공개 메서드가 오버라이드 가능해야 한다(Mockito 미도입 방침 유지).
 */
public class NoteService {

    private static final Logger log = LoggerFactory.getLogger(NoteService.class);

    private final NoteTxService noteTxService;
    private final AliasGenerator aliasGenerator;
    private final PhotoStore photoStore;
    private final Path artifactDir;

    /**
     * 갤러리 한 페이지의 노트 수 (ref: changes/0029 tasks.md TΔ5a).
     *
     * <p>계약에 페이지 크기 파라미터가 없다 — 무한 스크롤이라 클라이언트가 정할 이유가 없고, 정하게 하면
     * 그 값이 계약이 되어 나중에 서버가 못 바꾼다. 24는 <b>폰 2열 그리드에서 12행</b>으로, 첫 화면을
     * 채우고도 한참 남아 스크롤이 커서 왕복을 기다리지 않는 크기다. 실사용에서 다시 판단할 값이다.
     */
    private static final int PAGE_SIZE = 24;

    public NoteService(NoteTxService noteTxService, AliasGenerator aliasGenerator,
                       PhotoStore photoStore, Path artifactDir) {
        this.noteTxService = noteTxService;
        this.aliasGenerator = aliasGenerator;
        this.photoStore = photoStore;
        this.artifactDir = artifactDir;
    }

    // ────────────────────────────── 조회 ──────────────────────────────

    /** 저장된 모든 노트. id 오름차순(결정적 순서). */
    public List<Note> findAll() {
        return noteTxService.findAll();
    }

    /** id로 노트 조회. 없으면 빈 Optional. */
    public Optional<Note> findById(long id) {
        return noteTxService.findById(id);
    }

    /**
     * 매칭 후보 검색 — 변경 시트가 쓰는 조회 (ref: changes/0029 tasks.md TΔ7).
     *
     * <p>외부 IO가 없어 위임 한 줄이다(delta D-9 — 상위 계층이 잡는 타입을 하나로 유지하는 대가).
     *
     * <p><b>이 검색은 모델을 지나지 않는다</b>(사용자 확정 2026-08-01). 사용자가 시트를 연 시점은
     * <i>에이전트의 매칭 판정이 틀렸다</i>는 신호이므로, 거기서 다시 모델을 부르면 방금 틀린 판정기를
     * 되부르는 셈이다. 이 델타가 하네스를 확률적 영역에서 결정론적 영역으로 옮기는 것이기도 하다
     * (delta.md §6). 에이전트 쪽 매칭 경로는 {@code list_notes} tool이 따로 소유하고 — 그쪽은 검색어 없이
     * 전건을 모델에 넘긴다 — <b>둘은 같은 질의가 아니다.</b>
     */
    public List<NoteCandidate> findCandidates(String query) {
        return noteTxService.findCandidates(query);
    }

    /**
     * 갤러리 목록 한 페이지 — 필터·정렬·페이징·facet·총수 (ref: changes/0029 tasks.md TΔ5a·TΔ12, AC-4).
     *
     * <p>외부 IO가 없어 위임 한 줄이다(delta D-9). <b>페이지 크기는 이 층이 정한다</b> — 클라이언트가
     * 지정하지 않는 값이고(계약에 {@code limit} 파라미터가 없다) 아래 층은 몇 건을 읽을지만 안다.
     */
    public NotePage findNotes(NoteFilter filter, NoteCursor cursor) {
        return noteTxService.findNotes(filter, cursor, PAGE_SIZE);
    }

    /**
     * 상세 화면이 읽는 노트 전문 + 사진 — 없으면 빈 Optional (ref: changes/0029 tasks.md TΔ5a·TΔ13a).
     * <p>외부 IO가 없어 위임 한 줄이다. 사진 <b>파일</b>은 여기서 읽지 않는다 — 응답에 실리는 것은 URL이고
     * 바이트를 돌려주는 것은 정적 서빙({@code /api/photos/**}, {@code WebConfig})의 몫이다.
     */
    public Optional<NoteDetail> findDetail(long id) {
        return noteTxService.findDetail(id);
    }

    // ────────────────────────────── 커밋 ──────────────────────────────

    /**
     * 폼 확정 저장 — 구 {@code SlackCommitHandler.confirmSave}의 <b>Slack 없는 판본</b>이다
     * (ref: plan.md#ADR-3·ADR-37, spec AC-4, changes/0029 tasks TΔ4a·TΔ4c).
     *
     * <p><b>이 메서드가 지는 것은 순서다</b>: 별칭 생성(외부 콜) → 저장. 뒤집으면 LLM 지연이 쓰기 구간에
     * 들어온다(백엔드 CLAUDE.md §3). 저장 안에서 무슨 일이 일어나는지는 {@link NoteTxService#commit}이
     * 소유한다.
     * <ul>
     *   <li>신규 노트({@code match.type == NEW})면 별칭을 LLM 1콜로 생성한다 — <b>노트당 평생 1회</b>다.</li>
     *   <li>그 외에는 콜하지 않고 {@code null}을 넘긴다 — 기존 별칭에 무엇을 더할지(V-13 축적)는 저장된
     *       행을 읽어야 알 수 있어 트랜잭션 안에서 계산된다(TΔ4c).</li>
     * </ul>
     *
     * <p><b>사진 확정은 저장 <i>뒤</i>다</b>(TΔ8b) — 순서가 뒤집힌 유일한 외부 IO이고, 그럴 수밖에 없다:
     * 아카이브 폴더 접미가 {@code <id>-…}라(ADR-75) 신규 노트는 INSERT가 id를 발급하기 전까지 최종 경로가
     * 없다. 그래서 사진은 스테이징에 머물다 여기서 옮겨진다({@link #archivePhotos}). 카드 생성(TΔ9)은
     * 아직 여기 없다.
     *
     * <p>POLICY: 사용자 [저장] 확정 없이 이 메서드를 부르지 않는다 (ref: plan.md#ADR-3, AC-4).
     * <p>POLICY: 별칭 생성 콜 실패는 저장을 되돌리지 않는다 — 빈 별칭으로 수렴하고 저장은 유지한다.
     * 이후 관측 표기 축적이 보완한다 ({@link AliasGenerator} 내부 처리, plan.md §7, V-13, ADR-37).
     *
     * @param draft 확정된 폼 내용 — 이번 시음 엔트리 1건을 포함한다. {@code id}가 있으면 그 노트에 병합.
     * @param match 신규/기존 판정 — 별칭 <b>생성</b> 콜 여부가 여기서 갈린다.
     * @return 저장된 최종 노트.
     * @throws IllegalArgumentException {@code draft}에 엔트리가 없으면(저장할 시음이 없다).
     */
    public Note commit(Note draft, MatchInfo match) {
        // 입력 검증은 트랜잭션을 열기 전에 — 저장할 것이 없는 요청으로 쓰기 구간에 들어가지 않는다.
        Entry entry = latestEntry(draft);
        NoteMeta meta = metaOf(draft);

        Aliases generated = isNew(match) ? generateAliases(draft) : null;

        Note saved = noteTxService.commit(draft.id(), meta, entry, generated);
        archivePhotos(saved, entry.date());
        log.info("커밋 완료: noteId={} date={} entries={}", saved.id(), entry.date(), saved.entries().size());
        return saved;
    }

    /**
     * 스테이징 사진을 이 노트의 아카이브로 옮기고 색인({@code note_photo})을 남긴다
     * (ref: changes/0029 tasks.md TΔ8b, plan.md#ADR-79). 스테이징이 비어 있으면 아무 일도 하지 않는다.
     *
     * <p><b>폴더 접미는 계산보다 기록이 우선이다</b>: 이미 사진이 있는 노트면 그때 실제로 쓴 경로에서
     * 되읽고({@link NoteFolderName#from}), 없을 때만 지금 이름으로 만든다. 접미는 생성 시점 스냅샷이라
     * (ADR-75) 로스터리가 수정된 노트에서 재계산하면 옛 폴더와 갈리는데 — 0028이 <i>"근본 해법은 A2에서
     * {@code note_photo}와 함께"</i>로 미뤄 둔 한계가 여기서 닫힌다.
     */
    // POLICY: 사진 확정 실패는 저장을 되돌리지 않는다 — DB 쓰기와 파일 쓰기는 같은 원자 단위가 아니고,
    //         남은 스테이징은 시작 시 고아 청소가 걷는다
    //         (ref: 백엔드 CLAUDE.md §3, specs/coffee-note-agent/plan.md#ADR-29·#ADR-79).
    private void archivePhotos(Note saved, LocalDate date) {
        try {
            String folder = noteTxService.photoPaths(saved.id()).stream()
                    .map(NoteFolderName::from).flatMap(Optional::stream).findFirst()
                    .orElseGet(() -> NoteFolderName.of(saved));
            List<String> paths = photoStore.commit(SingleUser.ID, folder, date.toString());
            // 색인이 먼저 서고 실패해도 파일은 아카이브에 남는다 — 사진 바이트가 정본이라 유실이 아니다.
            noteTxService.attachPhotos(saved.id(), date, paths);
            if (!paths.isEmpty()) {
                log.info("사진 확정: noteId={} date={} photos={}", saved.id(), date, paths.size());
            }
        } catch (RuntimeException e) {
            log.warn("사진 확정 실패(저장은 유지): noteId={} date={}", saved.id(), date, e);
        }
    }

    /** 별칭 <b>생성</b>은 신규 노트에서만 — 노트당 평생 1회다(ADR-37). */
    private static boolean isNew(MatchInfo match) {
        return match != null && match.type() == MatchInfo.MatchType.NEW;
    }

    /** 유일한 외부 콜. 쓰기 진입 전에 끝난다 — 실패는 생성기가 빈 별칭으로 수렴시킨다(plan §7). */
    private Aliases generateAliases(Note draft) {
        return aliasGenerator.generate(
                Sourced.valueOrNull(draft.coffeeName()), Sourced.valueOrNull(draft.roastery()));
    }

    // ────────────────────────────── 수정·삭제 ──────────────────────────────

    /**
     * 노트 메타 수정 — 커피명을 제외한 전부가 대상이다(FR-21, spec AC-5).
     * <p>외부 IO가 없어 위임 한 줄이다. V-9 검사는 {@link NoteTxService#updateMeta}가 트랜잭션 안에서 진다.
     *
     * @return 갱신된 노트 전문 + 사진 — 응답이 곧 화면의 새 기준선이다(TΔ5b-3).
     * @throws IllegalArgumentException 커피명 변경 시도(V-9).
     * @throws IllegalStateException    대상 노트 소실 시.
     */
    public NoteDetail updateMeta(long noteId, NoteMeta meta) {
        return noteTxService.updateMeta(noteId, meta);
    }

    /**
     * 엔트리 수정 — 대상 회차를 갈아끼우고, {@code entry.date()}가 다르면 <b>사진까지 데리고</b> 날짜를
     * 옮긴다 (ref: V-10 개정본, changes/0029 delta.md#D-12 ③, spec FR-10·FR-21, tasks TΔ5b-2).
     *
     * <p><b>이 메서드가 지는 것은 순서다</b>: 아카이브 폴더 이동(외부 IO) → 저장. {@link #commit}이
     * 별칭 콜에 대해 지는 것과 같은 책임이고, 그래서 위임 한 줄이던 자리가 여기서 실질을 갖는다.
     * 이동처 충돌 처리(회차 병합)·색인 행의 순서는 트랜잭션 안의 규칙이다({@link NoteTxService}).
     *
     * <p><b>파일이 먼저인 것은 최종 경로가 이동 후에야 정해지기 때문이다</b> — 이동처에 같은 이름 파일이
     * 있으면 {@code -N}으로 유일화되므로 색인이 날짜 세그먼트만 갈아 끼워 계산하면 다른 파일을 가리킨다.
     * {@link #archivePhotos}가 파일 뒤에 색인을 세우는 것과 같은 구조적 이유이고(폴더 접미가 id를 기다린다),
     * 방향도 같다: <b>파일이 정본, 행은 색인</b>(plan.md#ADR-79).
     *
     * <p>실패 처리는 그 둘과 <b>갈린다</b>: 커밋의 사진 확정은 삼키지만(저장이 본체이고 사진은 뒤따르는
     * 색인) 여기서는 삼키지 않는다 — 삼키면 사진이 옛 날짜에 남아 어느 화면에도 보이지 않는 상태, 즉
     * 이 task가 고치러 온 바로 그 상태를 조용히 다시 만든다. 던지는 시점에 행은 하나도 바뀌지 않았고
     * 사진 바이트는 아카이브 안에 있다.
     *
     * @return 갱신된 노트 전문 + 사진 — 이동 후의 사진 URL까지 서버가 답한다(TΔ5b-3).
     * @throws IllegalStateException 대상 노트 또는 {@code targetDate} 엔트리 소실 시.
     */
    public NoteDetail replaceEntry(long noteId, LocalDate targetDate, Entry entry) {
        Map<String, String> movedPhotos = movePhotoFiles(noteId, targetDate, entry.date());
        return noteTxService.replaceEntry(noteId, targetDate, entry, movedPhotos);
    }

    /**
     * 날짜가 바뀌면 그 노트의 아카이브 폴더를 새 날짜로 옮기고 <b>어디로 갔는지</b>를 돌려준다.
     *
     * <p>폴더 접미는 {@link #archivePhotos}와 같은 규칙으로 <b>기록에서 되읽는다</b>(ADR-75 — 생성 시점
     * 스냅샷이라 재계산하면 로스터리가 수정된 노트에서 갈린다). 색인된 사진이 없으면 되읽을 것이 없고
     * 옮길 것도 없다 — 파일시스템을 훑어 폴더를 찾아 나서지 않는다(그 탐색을 하지 않는 것이 TΔ8b의 답이다).
     */
    private Map<String, String> movePhotoFiles(long noteId, LocalDate targetDate, LocalDate movedTo) {
        if (movedTo.equals(targetDate)) {
            return Map.of();
        }
        Optional<String> folder = noteTxService.photoPaths(noteId).stream()
                .map(NoteFolderName::from).flatMap(Optional::stream).findFirst();
        if (folder.isEmpty()) {
            return Map.of();
        }
        Map<String, String> moved =
                photoStore.moveEntryPhotos(folder.get(), targetDate.toString(), movedTo.toString());
        if (!moved.isEmpty()) {
            log.info("사진 동반 이동: noteId={} {} → {} photos={}", noteId, targetDate, movedTo, moved.size());
        }
        return moved;
    }

    /**
     * 노트 삭제 — 하위 행도 <b>파일도</b> 남기지 않는 hard delete
     * (ref: changes/0028 AC-Δ8, spec AC-6; 파일 몫은 changes/0029 tasks.md TΔ8b).
     *
     * <p><b>0028이 <i>"A2가 정한다"</i>로 미룬 결정이 여기서 닫혔다</b>(사용자 확정 2026-08-01): 사진
     * 아카이브({@code data/photos/})와 카드 파생물({@code artifact/cards/})을 함께 지운다. 종전에 지우지
     * 못한 이유는 <b>무엇을 지울지 알 수 없어서</b>였다 — 노트↔사진 연결이 폴더 규약뿐이었고 그 폴더명은
     * 재계산으로 맞출 수 없다(ADR-75). {@code note_photo}가 실제 경로를 들고 있으므로 이제 답이 있다.
     *
     * <p><b>지웠는지를 돌려준다</b>(TΔ5b-3 — 구 판본은 없는 id를 멱등으로 지나갔다). 삭제가 노출되며
     * 계약이 404를 선언했고({@code note-update.contract.json}), 그 판정은 삭제 자체가 답한다.
     * 없던 노트면 지울 파일도 없으므로 아래 정리 구간에 들어가지 않는다.
     *
     * @return 지웠으면 {@code true}, 없는 id였으면 {@code false}.
     */
    // POLICY: 행 삭제가 파일 삭제보다 **먼저**다 — 뒤집으면 행 삭제 실패 시 "사진만 사라진 노트"가 남는다.
    //         이 순서에서 남는 잔여물은 참조 없는 파일뿐이고 그것은 다시 지울 수 있다
    //         (ref: specs/coffee-note-agent/plan.md#ADR-79, 백엔드 CLAUDE.md §3).
    public boolean delete(long id) {
        // 지운 뒤에는 경로도 폴더 접미도 알 수 없다 — 파일 정리에 필요한 것을 먼저 읽는다.
        List<String> photoPaths = noteTxService.photoPaths(id);
        String cardFolder = noteTxService.findById(id).map(NoteFolderName::of).orElse(null);

        if (!noteTxService.delete(id)) {
            return false;
        }

        // 여기서부터는 best-effort다: 실패해도 노트는 이미 사라졌고 되돌릴 대상이 없다.
        try {
            photoStore.deletePhotos(photoPaths);
            removeCards(cardFolder);
        } catch (RuntimeException e) {
            log.warn("삭제 후 파일 정리 실패(행은 이미 삭제됨): noteId={}", id, e);
        }
        return true;
    }

    /**
     * 그 노트의 카드 폴더를 통째로 지운다 — {@code artifact/cards/<접미>/}.
     *
     * <p>카드가 <b>렌더러를 지나지 않는</b> 이유는 의존 방향이다: {@code ThymeleafNoteRenderer}가 이
     * 클래스를 잡고 있어({@code NoteRenderer}의 조회원) 반대 방향 주입은 순환이다. 지우는 일에는 렌더
     * 프로바이더가 필요 없고 경로 규약만 있으면 되므로({@link CardFiles}가 단일 소유) 여기서 파일만 걷는다.
     * 놓친 카드가 있어도 {@code renderAll}의 고아 정리가 최종 회수 지점이다(plan §7).
     */
    private void removeCards(String noteFolder) {
        if (noteFolder == null || artifactDir == null) {
            return;
        }
        Path cardsDir = artifactDir.resolve(CardFiles.CARDS_DIR).resolve(noteFolder);
        if (!Files.isDirectory(cardsDir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(cardsDir)) {
            // 깊은 경로부터 — 파일을 지운 뒤라야 폴더가 빈다.
            for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
            log.info("카드 폴더 삭제: {}", cardsDir);
        } catch (IOException e) {
            throw new UncheckedIOException("카드 폴더 삭제 실패: " + cardsDir, e);
        }
    }

    // ────────────────────────────── 도메인 조각 ──────────────────────────────

    /** draft(Note)에서 노트 단위 메타만 뽑는다 — 엔트리·식별자·타임스탬프는 아래 층이 다룬다. */
    private static NoteMeta metaOf(Note draft) {
        return new NoteMeta(
                draft.coffeeName(),
                draft.roastery(),
                draft.beans(),
                draft.roastLevel(),
                draft.officialNotes(),
                draft.sources());
    }

    /** 이번 시음 엔트리 — draft.entries는 1건 전제(폼과 동일 가정). 마지막 엔트리를 취한다. */
    private static Entry latestEntry(Note draft) {
        List<Entry> entries = draft.entries();
        if (entries == null || entries.isEmpty()) {
            throw new IllegalArgumentException("저장할 시음 엔트리가 없다: noteId=" + draft.id());
        }
        return entries.getLast();
    }
}
