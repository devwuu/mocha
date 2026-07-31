package com.devwuu.mocha.service;

import com.devwuu.mocha.domain.Aliases;
import com.devwuu.mocha.domain.Entry;
import com.devwuu.mocha.domain.MatchInfo;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.NoteMeta;
import com.devwuu.mocha.domain.Sourced;
import com.devwuu.mocha.llm.AliasGenerator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 노트 유스케이스 중 <b>외부 IO가 필요한 것</b>의 오케스트레이션
 * (ref: 백엔드 CLAUDE.md §2·§3, changes/0029-app-interface/delta.md#D-9, tasks TΔ4c).
 *
 * <p><b>경계</b>: 외부 호출(LLM·검색·파일)을 <b>쓰기 진입 전에</b> 끝내고 결과값만 {@link NoteTxService}에
 * 넘긴다. 원자적이어야 하는 규칙(V-9·V-10·V-13·ADR-4 병합 정책)은 전부 그 아래에 있다 — <b>이 클래스에
 * 비즈니스 규칙을 두지 않는다.</b> 여기 있는 판단은 하나뿐이고 그것도 외부 콜에 관한 것이다:
 * <i>"별칭 생성 콜을 쏠 것인가"</i>({@link #commit}).
 *
 * <p>이 분리가 백엔드 CLAUDE.md §3(<i>"트랜잭션 안에서 외부 호출을 하지 않는다"</i>)을 규칙이 아니라
 * <b>계층으로</b> 지키는 방식이다 — 외부 콜이 사는 층과 트랜잭션이 열리는 층이 아예 다르다.
 *
 * <p>POLICY: 이 클래스에 {@code @Transactional}을 걸지 않는다 — 거는 순간 외부 호출이 트랜잭션 구간에
 * 들어오고, 위 성질이 구조가 아니라 규칙으로만 남는다 (ref: 백엔드 CLAUDE.md §3).
 *
 * <p><b>외부 IO가 없는 유스케이스도 여기를 지난다</b>(사용자 확정 2026-08-01, delta D-9). 상위 계층
 * (tool·렌더러·REST 컨트롤러)이 잡는 타입을 하나로 유지하기 위해서다 — 대가로 위임 한 줄 메서드가 생기고,
 * 사진 확정(TΔ8)·카드(TΔ9)가 붙으면 대부분 실질을 갖는다.
 *
 * <p><b>상위 계층의 테스트 격리 seam이다</b> — 구 {@code NoteRepository} 포트가 지던 역할이고, 포트가
 * TΔ4b에서 걷히며(ADR-76) 이 자리가 유일해졌다. 손 fake가 이 타입을 상속해 만들어지므로 {@code final}이
 * 아니고 공개 메서드가 오버라이드 가능해야 한다(Mockito 미도입 방침 유지).
 */
public class NoteService {

    private static final Logger log = LoggerFactory.getLogger(NoteService.class);

    private final NoteTxService noteTxService;
    private final AliasGenerator aliasGenerator;

    public NoteService(NoteTxService noteTxService, AliasGenerator aliasGenerator) {
        this.noteTxService = noteTxService;
        this.aliasGenerator = aliasGenerator;
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
     * <p>사진 확정(TΔ8)·카드 생성(TΔ9)은 아직 여기 없다 — 둘 다 외부 IO라 이 메서드에 붙는다.
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
        log.info("커밋 완료: noteId={} date={} entries={}", saved.id(), entry.date(), saved.entries().size());
        return saved;
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
     * @throws IllegalArgumentException 커피명 변경 시도(V-9).
     * @throws IllegalStateException    대상 노트 소실 시.
     */
    public Note updateMeta(long noteId, NoteMeta meta) {
        return noteTxService.updateMeta(noteId, meta);
    }

    /**
     * 엔트리 수정 — 대상 회차를 갈아끼우고, {@code entry.date()}가 다르면 날짜를 옮긴다(V-10).
     * <p>외부 IO가 없어 위임 한 줄이다. 이동처 충돌 처리·행 순서는 트랜잭션 안의 규칙이다.
     *
     * @throws IllegalStateException 대상 노트 또는 {@code targetDate} 엔트리 소실 시.
     */
    public Note replaceEntry(long noteId, LocalDate targetDate, Entry entry) {
        return noteTxService.replaceEntry(noteId, targetDate, entry);
    }

    /**
     * 노트 삭제 — 하위 행을 남기지 않는 hard delete (ref: changes/0028 AC-Δ8).
     * <p>없는 id는 무해하게 지나간다(멱등). 실패 응답이 필요해지는 것은 삭제를 노출하는 TΔ5다.
     * <p>사진·카드 파일 정리가 붙는다면 외부 IO라 이 자리다 — 지금은 대상이 아니다(0028 delta#삭제-정책).
     */
    public void delete(long id) {
        noteTxService.delete(id);
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
