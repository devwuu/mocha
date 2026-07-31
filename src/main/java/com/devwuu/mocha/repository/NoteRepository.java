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
     *   <li>신규 노트면 {@code aliases}를 그 노트의 초기 별칭으로 심는다
     *       — 신규 노트 첫 [저장] 시 {@link com.devwuu.mocha.llm.AliasGenerator}가 생성한 음차·이표기(TΔ2).</li>
     *   <li>기존 노트면 {@code aliases} 인자는 무시하고, {@code meta}의 커피명·로스터리 관측 표기를
     *       기존 별칭에 정규화 중복 제거로 무콜 축적한다(표시값과 같은 표기는 미추가, TΔ3, V-13).</li>
     * </ul>
     * POLICY: 같은 날짜 엔트리는 갱신만 — 하루 2엔트리 금지, 다회 시도는 brews 회차로
     * (ref: data-model.md#2.2, AC-14, ADR-4·59).
     *
     * @param noteId  기존 노트 id. {@code null}이면 신규 생성(D-1).
     * @param aliases 신규 노트에 심을 별칭(내부 전용, V-13). 부재 수렴은 {@link Aliases#empty()}.
     * @return 저장된 최종 노트.
     */
    Note upsertEntry(Long noteId, NoteMeta meta, Entry entry, Aliases aliases);

    /**
     * 수정 세션([저장]) 커밋 — 대상 엔트리를 draft 내용으로 갱신하고, 필요 시 날짜를 이동한다
     * (ref: plan.md#ADR-27, changes/0012).
     * <ul>
     *   <li>{@code draft}는 대상 엔트리 1건만 실은 Note 사본(data-model §2.3).
     *       노트 단위 필드(로스터리 등)도 draft 값으로 갱신한다 — 커피명 제외 전부가 수정 범위.</li>
     *   <li>coffee_name은 노트 생성 후 불변 — draft 값이 원본과 다르면 커밋을 거부한다 (V-9).</li>
     *   <li>draft 엔트리의 date가 {@code targetDate}와 다르면 날짜 이동 — 이동처 date에 기존 엔트리가
     *       있으면 그 엔트리를 덮어쓰고 원본 {@code targetDate} 엔트리는 제거한다(엔트리 총수 1 감소, V-10).
     *       날짜 오름차순 정렬은 유지된다.</li>
     * </ul>
     *
     * @param noteId     수정 대상 노트 id ({@code PendingNote.target.noteId})
     * @param targetDate 수정 대상 원본 엔트리 date ({@code PendingNote.target.date})
     * @param draft      수정 반영이 끝난 draft — 대상 엔트리 1건 포함
     * @return 저장된 최종 노트.
     * @throws IllegalArgumentException coffee_name 변경 시도(V-9), 또는 draft 엔트리가 1건이 아니면.
     * @throws IllegalStateException    대상 노트 또는 {@code targetDate} 엔트리 소실 시
     *                                  (호출부가 만료 안내로 수렴, plan §7).
     */
    Note applyEdit(long noteId, LocalDate targetDate, Note draft);

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
