package com.devwuu.mocha.repository;

import com.devwuu.mocha.domain.Aliases;
import com.devwuu.mocha.domain.Entry;
import com.devwuu.mocha.domain.Note;
import com.devwuu.mocha.domain.NoteMeta;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

/**
 * 노트(커피 1종) 저장소 — 저장 매체 접근을 파이프라인에서 격리 (ref: plan.md#ADR-8).
 * <p>구현: {@link JpaNoteRepository}. 미래 호스팅형 전환 시 구현체 교체로 대응(NFR-4).
 * <p><b>식별자는 {@code note.id}</b>다 — 파일명이자 식별자였던 slug는 파일 폐기와 함께 소멸했고
 * 유일화(V-2)도 함께 사라졌다(changes/0028 ADR-75).
 */
public interface NoteRepository {

    /** 저장된 모든 노트. id 오름차순(결정적 순서). */
    List<Note> findAll();

    /** id로 노트 조회. 없으면 빈 Optional. */
    Optional<Note> findById(long id);

    /**
     * 날짜 엔트리 병합 저장 (ref: plan.md#ADR-4·37·59, [6], changes/0016).
     * <ul>
     *   <li>{@code noteId}가 {@code null}이면 {@code meta}로 새 노트를 만들고 {@code entry}를 첫 엔트리로 둔다
     *       — id는 INSERT가 발급하므로 저장 전 draft는 식별자를 갖지 않는다(D-1, changes/0028).</li>
     *   <li>있으면 같은 date 엔트리는 갱신(엔트리 통째 교체), 다른 date는 추가 후 날짜 오름차순 정렬한다.</li>
     *   <li>같은 date 갱신에서 회차 append·기존 회차 지칭 병합은 에이전트가 구성한 {@code entry.brews}
     *       배열(V-15 검증 통과분)을 신뢰한다 — 서버는 회차 단위 병합을 하지 않는다(changes/0021 ADR-59).</li>
     *   <li>{@code aliases}는 <b>이 저장으로 심을 별칭</b>이다 — 신규면 초기 별칭, 기존이면 <b>더할 표기만</b>.
     *       무엇을 심을지는 호출부({@link com.devwuu.mocha.service.NoteService})가 정한다: 신규는
     *       {@link com.devwuu.mocha.llm.AliasGenerator} 1콜, 기존은 관측 표기 무콜 축적(V-13, TΔ2·TΔ3).</li>
     * </ul>
     * POLICY: 같은 날짜 엔트리는 갱신만 — 하루 2엔트리 금지, 다회 시도는 brews 회차로
     * (ref: data-model.md#2.2, AC-14, ADR-4·59).
     *
     * <p>0029 TΔ4a: 별칭 <b>판정</b>이 service로 올라가며 이 인자의 의미가 "신규 전용"에서 "심을 것"으로
     * 넓어졌다. 기존 노트에 무엇을 더할지는 저장 전 노트를 읽어야 알 수 있고, 그 읽기는 판단이지 행 조율이
     * 아니다 — 저장소는 받은 표기를 행으로 옮기기만 한다.
     *
     * @param noteId  기존 노트 id. {@code null}이면 신규 생성(D-1).
     * @param aliases 이 저장으로 심을 별칭(내부 전용, V-13). 부재 수렴은 {@link Aliases#empty()}.
     * @return 저장된 최종 노트.
     */
    Note upsertEntry(Long noteId, NoteMeta meta, Entry entry, Aliases aliases);

    /**
     * 노트 단위 메타 갱신 — <b>커피명을 제외한 전부</b>가 대상이다(FR-21). 배열 3종(원두·공식 노트·참조
     * 링크)은 통째 교체하고, <b>별칭과 엔트리는 건드리지 않는다</b>.
     *
     * <p>커피명은 노트 생성 후 불변이라(V-9) 인자에 실려 와도 무시된다 — <b>다른 값을 실었는가의 판정은
     * 호출부(service)의 몫</b>이고, 여기서는 덮어쓸 수단 자체가 없다({@code NoteEntity}에 setter 부재).
     *
     * <p>0029 TΔ4a: 구 {@code applyEdit}이 "노트 메타 + 대상 엔트리 1건"을 한 번에 받던 것을 둘로 갈랐다
     * — 수정 세션(pending mode=edit)이 폐기되며 "엔트리를 반드시 1건 실어야 한다"는 전제가 근거를 잃었고,
     * 로스터리만 고치는 데 엔트리를 딸려 보내면 그 회차 행이 이유 없이 재발급됐다.
     *
     * @param noteId 수정 대상 노트 id
     * @param meta   수정 반영이 끝난 노트 메타
     * @return 저장된 최종 노트.
     * @throws IllegalStateException 대상 노트 소실 시(호출부가 안내로 수렴, plan §7).
     */
    Note updateMeta(long noteId, NoteMeta meta);

    /**
     * 엔트리 교체 — {@code targetDate} 엔트리의 회차를 {@code entry}의 것으로 갈아끼우고, 필요하면 날짜를
     * 옮긴다 (ref: plan.md#ADR-27·ADR-59, V-10, changes/0012).
     * <ul>
     *   <li>{@code entry.date()}가 {@code targetDate}와 다르면 날짜 이동 — 이동처 date에 기존 엔트리가
     *       있으면 그 엔트리를 덮어쓰고 원본 {@code targetDate} 엔트리는 제거한다(엔트리 총수 1 감소, V-10).
     *       날짜 오름차순 정렬은 유지된다.</li>
     *   <li>엔트리 <b>행은 살아남는다</b> — 이동은 삭제 후 재삽입이 아니라 {@code tasted_on} 갱신이라
     *       {@code created_at}이 보존된다(재기록 경로 {@link #upsertEntry}와 갈리는 지점).</li>
     * </ul>
     *
     * @param noteId     수정 대상 노트 id
     * @param targetDate 수정 대상 원본 엔트리 date
     * @param entry      수정 반영이 끝난 엔트리 — {@code date}가 이동처를 겸한다
     * @return 저장된 최종 노트.
     * @throws IllegalStateException 대상 노트 또는 {@code targetDate} 엔트리 소실 시
     *                               (호출부가 안내로 수렴, plan §7).
     */
    Note replaceEntry(long noteId, LocalDate targetDate, Entry entry);

    /**
     * 노트 한 건 삭제 — 하위 행(엔트리·회차·레시피·감상·배열 4종)을 <b>남기지 않는다</b>
     * (ref: changes/0028-rdb-storage/delta.md#삭제-정책, AC-Δ8).
     *
     * <p>POLICY: hard delete — {@code deleted_at} 계열 컬럼을 두지 않는다. 1인용 개인 기록이라 복구 요구가
     * 관측된 적이 없고, soft delete는 <b>모든 조회에 조건을 얹는다</b>(ref: delta.md#삭제-정책, Q-3·Q-12).
     *
     * <p><b>A1에는 호출부가 없다</b> — 삭제 UI(수정 화면의 삭제 버튼)는 A2 범위이고, 여기서 필요한 것은
     * 명시적 순서 삭제 코드가 <b>존재하고 테스트로 검증되는 것</b>이다. FK가 없으므로(ADR-75) 그 테스트가
     * 고아 행을 막는 유일한 안전망이고, A1 기간에 급하면 그 삭제 순서를 psql에 그대로 옮겨 쓸 수 있다.
     *
     * <p>없는 {@code id}는 무해하게 지나간다(멱등) — 지울 것이 없다는 것과 지웠다는 것을 A1이 가릴 이유가
     * 없다. 호출부의 실패 응답이 필요해지는 것은 삭제를 노출하는 A2다.
     *
     * <p>사진({@code data/photos/})·카드({@code artifact/cards/})는 대상이 아니다 — 파일시스템에 남고,
     * 노트↔사진 연결({@code note_photo})은 A2가 들인다(Q-13).
     */
    void delete(long id);
}
