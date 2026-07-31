package com.devwuu.mocha.service;

import com.devwuu.mocha.domain.Aliases;
import com.devwuu.mocha.domain.Entry;
import com.devwuu.mocha.domain.MatchInfo;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.NoteMeta;
import com.devwuu.mocha.domain.Sourced;
import com.devwuu.mocha.llm.AliasGenerator;
import com.devwuu.mocha.repository.NoteRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 노트 유스케이스 — 커밋·수정·삭제·조회의 <b>판단</b>을 지는 계층
 * (ref: 백엔드 CLAUDE.md §2, changes/0029-app-interface/delta.md#D-8, tasks TΔ4a).
 *
 * <p><b>왜 생겼는가.</b> 0029 TΔ4에서 {@code SlackCommitHandler}가 사라지며 커밋 오케스트레이션(신규
 * 노트면 별칭 생성 → 저장 → 사진 → 카드)이 무주공산이 됐다. 그대로 REST에 들어가면 그것이 컨트롤러
 * 안에 다시 짜인다 — 0028 TΔ5c가 *"정책의 service 분리는 A2로"*로 미뤄 둔 결정이 <b>옮길 호출부가
 * 0개인 지금</b> 이행된다.
 *
 * <p><b>경계</b>: 여기 사는 것은 <b>판단</b>이고({@link #commit} 신규/기존 분기·V-13 별칭 축적·
 * {@link #updateMeta} V-9 검사), 저장소에 남는 것은 <b>행 조율</b>(엔트리 통째 교체·날짜 이동의 UNIQUE
 * 중간 상태·3단 조립·로드 경계 위생)이다. 도메인 {@code Note}에 자식 행 id가 없어 계층을 나눠도 그
 * 조율은 저장소 안에 그대로 있다(0028 TΔ5c 실측).
 *
 * <p>POLICY: <b>트랜잭션 경계는 저장소가 소유한다</b> — 이 클래스에 {@code @Transactional}을 걸지 않는다
 * (ref: 백엔드 CLAUDE.md §3, changes/0029 tasks TΔ4a, 사용자 확정 2026-08-01). 외부 호출(별칭 LLM 콜)이
 * 쓰기 구간에 들어올 수 없는 성질을 규칙이 아니라 <b>구조로</b> 유지하기 위해서다.
 * <p><b>대가를 명시적으로 진다</b>: 그래서 V-9 검사와 V-13 축적 계산이 read-then-act가 되어 트랜잭션
 * 밖에 선다. 1인 단일 프로세스라 경합이 실질적으로 없고(동시 커밋 경로가 하나), 검사를 통과한 뒤 값이
 * 바뀔 창은 사용자 자신뿐이다. 경합이 실재하게 되는 것은 멀티테넌시(A3)이고, 그때 다시 판단할 자리다.
 *
 * <p>테스트 격리 seam이기도 하다 — 상위 계층(tool·렌더러)의 손 fake가 이 타입을 상속해 만들어지므로
 * {@code final}이 아니고 공개 메서드가 오버라이드 가능해야 한다(0029 TΔ4b, Mockito 미도입 방침 유지).
 */
public class NoteService {

    private static final Logger log = LoggerFactory.getLogger(NoteService.class);

    private final NoteRepository noteRepository;
    private final AliasGenerator aliasGenerator;

    public NoteService(NoteRepository noteRepository, AliasGenerator aliasGenerator) {
        this.noteRepository = noteRepository;
        this.aliasGenerator = aliasGenerator;
    }

    // ────────────────────────────── 조회 ──────────────────────────────

    /** 저장된 모든 노트. id 오름차순(결정적 순서). */
    public List<Note> findAll() {
        return noteRepository.findAll();
    }

    /** id로 노트 조회. 없으면 빈 Optional. */
    public Optional<Note> findById(long id) {
        return noteRepository.findById(id);
    }

    // ────────────────────────────── 커밋 ──────────────────────────────

    /**
     * 폼 확정 저장 — 구 {@code SlackCommitHandler.confirmSave}의 <b>Slack 없는 판본</b>이다
     * (ref: plan.md#ADR-3·ADR-37, spec AC-4, changes/0029 tasks TΔ4a).
     *
     * <p>순서는 <b>별칭 생성(외부 콜) → 저장</b>이다. 뒤집으면 LLM 지연이 쓰기 구간에 들어온다
     * (백엔드 CLAUDE.md §3).
     * <ul>
     *   <li>신규 노트({@code match.type == NEW})면 별칭을 LLM 1콜로 생성한다 — <b>노트당 평생 1회</b>다.</li>
     *   <li>기존 노트면 콜 없이 이번 기록의 관측 표기를 기존 별칭에 축적한다(V-13) — 저장소에는 <b>더할
     *       표기만</b> 넘어간다(기존 행을 지우고 다시 넣으면 id가 밀려 첫 등장 순서가 무너진다, TΔ3a).</li>
     * </ul>
     *
     * <p>사진 확정(TΔ8)·카드 생성(TΔ9)은 아직 여기 없다 — 각각의 task가 이 메서드에 붙인다.
     *
     * <p>POLICY: 사용자 [저장] 확인 없이 이 메서드를 부르지 않는다 (ref: plan.md#ADR-3, AC-4).
     * <p>POLICY: 별칭 생성 콜 실패는 저장을 되돌리지 않는다 — 빈 별칭으로 수렴하고 저장은 유지한다.
     * 이후 관측 표기 축적이 보완한다 ({@link AliasGenerator} 내부 처리, plan.md §7, V-13, ADR-37).
     *
     * @param draft 확정된 폼 내용 — 이번 시음 엔트리 1건을 포함한다. {@code id}가 있으면 그 노트에 병합.
     * @param match 신규/기존 판정 — 별칭 <b>생성</b> 여부가 여기서 갈린다.
     * @return 저장된 최종 노트.
     * @throws IllegalArgumentException {@code draft}에 엔트리가 없으면(저장할 시음이 없다).
     */
    public Note commit(Note draft, MatchInfo match) {
        Entry entry = latestEntry(draft);
        NoteMeta meta = metaOf(draft);
        Aliases aliases = aliasesFor(draft, match);

        Note saved = noteRepository.upsertEntry(draft.id(), meta, entry, aliases);
        log.info("커밋 완료: noteId={} date={} entries={}", saved.id(), entry.date(), saved.entries().size());
        return saved;
    }

    /**
     * 이 커밋으로 심을 별칭 — 신규는 생성 1콜, 기존은 관측 표기 무콜 축적분(V-13, ADR-37 축적 ①·②).
     *
     * <p>기존 노트 갈래가 저장 전에 노트를 읽는 것이 위 POLICY가 말하는 read-then-act다 — 무엇이 이미
     * 별칭에 있는지는 행을 봐야 알고, 그 판정은 조율이 아니라 판단이라 여기 있어야 한다.
     */
    private Aliases aliasesFor(Note draft, MatchInfo match) {
        if (match != null && match.type() == MatchInfo.MatchType.NEW) {
            return aliasGenerator.generate(
                    Sourced.valueOrNull(draft.coffeeName()), Sourced.valueOrNull(draft.roastery()));
        }
        if (draft.id() == null) {
            // match가 없거나 EXISTING인데 id가 없다 — 대조할 원본이 없으므로 축적할 것도 없다.
            return Aliases.empty();
        }
        Note existing = noteRepository.findById(draft.id())
                .orElseThrow(() -> new IllegalStateException("병합 대상 노트 소실: " + draft.id()));
        Aliases before = existing.aliases();
        Aliases accumulated = before.accumulate(
                Sourced.valueOrNull(draft.coffeeName()), Sourced.valueOrNull(existing.coffeeName()),
                Sourced.valueOrNull(draft.roastery()), Sourced.valueOrNull(existing.roastery()));
        return newcomers(before, accumulated);
    }

    /** 축적 결과에서 기존에 없던 표기만 — 대조 기준은 정규화 키다(표시 형태가 아니라, V-13). */
    private static Aliases newcomers(Aliases before, Aliases after) {
        return new Aliases(
                newcomers(before.coffeeName(), after.coffeeName()),
                newcomers(before.roastery(), after.roastery()));
    }

    private static List<String> newcomers(List<String> before, List<String> after) {
        Set<String> known = before.stream().map(Aliases::normalize).collect(Collectors.toSet());
        return after.stream().filter(alias -> !known.contains(Aliases.normalize(alias))).toList();
    }

    // ────────────────────────────── 수정 ──────────────────────────────

    /**
     * 노트 메타 수정 — 커피명을 제외한 전부가 대상이다(FR-21, spec AC-5).
     *
     * <p>POLICY: coffee_name은 노트 생성 후 불변 — 다른 값을 실은 요청은 거부한다
     * (ref: specs/coffee-note-agent/data-model.md#V-9). 이 검사가 <b>이 델타에서 V-9의 유일한 자동
     * 가드</b>다: 구 가드는 {@code propose_edit} patch 스키마의 구조 차단이었고 TΔ1에서 tool과 함께
     * 사라졌다(TΔ1 이월 (a)가 여기서 닫힌다).
     *
     * <p>검사가 트랜잭션 밖이라는 것(read-then-act)은 클래스 POLICY가 밝힌 대가다.
     *
     * @throws IllegalArgumentException 커피명 변경 시도(V-9).
     * @throws IllegalStateException    대상 노트 소실 시.
     */
    public Note updateMeta(long noteId, NoteMeta meta) {
        Note existing = noteRepository.findById(noteId)
                .orElseThrow(() -> new IllegalStateException("수정 대상 노트 소실: " + noteId));
        if (!Objects.equals(Sourced.valueOrNull(existing.coffeeName()), Sourced.valueOrNull(meta.coffeeName()))) {
            throw new IllegalArgumentException("coffee_name은 노트 생성 후 불변(V-9): " + noteId);
        }
        return noteRepository.updateMeta(noteId, meta);
    }

    /**
     * 엔트리 수정 — 대상 회차를 갈아끼우고, {@code entry.date()}가 다르면 날짜를 옮긴다(V-10).
     *
     * <p>더할 판단이 없어 위임 한 줄이다 — 이동처 충돌 처리·행 순서는 전부 조율이라 저장소의 몫이다.
     * 그래도 이 자리를 두는 것은 상위 계층이 저장소를 직접 잡지 않게 하기 위해서다(TΔ4b가 포트를 걷는다).
     *
     * @throws IllegalStateException 대상 노트 또는 {@code targetDate} 엔트리 소실 시.
     */
    public Note replaceEntry(long noteId, LocalDate targetDate, Entry entry) {
        return noteRepository.replaceEntry(noteId, targetDate, entry);
    }

    // ────────────────────────────── 삭제 ──────────────────────────────

    /**
     * 노트 삭제 — 하위 행을 남기지 않는 hard delete (ref: changes/0028 AC-Δ8).
     * <p>없는 id는 무해하게 지나간다(멱등). 실패 응답이 필요해지는 것은 삭제를 노출하는 TΔ5다.
     */
    public void delete(long id) {
        noteRepository.delete(id);
    }

    // ────────────────────────────── 도메인 조각 ──────────────────────────────

    /** draft(Note)에서 노트 단위 메타만 뽑는다 — 엔트리·식별자·타임스탬프는 저장소가 다룬다. */
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
