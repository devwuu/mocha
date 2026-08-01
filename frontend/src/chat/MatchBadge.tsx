import { useEffect, useRef, useState } from 'react'
import type { Draft, NoteCandidate } from '../api'
import { getNoteCandidates } from '../api'
import { selectExisting, selectNew } from './draftEdits'

/**
 * 매칭 배지 + 변경 시트 — 에이전트의 신규/기존 판정을 사용자가 뒤집는 자리 (changes/0029 TΔ11, AC-1).
 *
 * **배지 전체가 탭 영역**이고 오른쪽 chevron이 그것을 알린다(OQ-2 ㉢). 매칭은 에이전트가 판정하되
 * 최종 결정권은 폼에 있다 — 배지도 폼의 일부다(data-model §2.3).
 *
 * <p>시트가 양방향으로 열려 있는 것이 이 컴포넌트의 요구사항이다:
 * <ul>
 *   <li>후보 선택 — 실제로는 기존 노트인데 `새 노트`로 판정된 경우. 표기가 흔들리거나 발화에 로스터리가
 *       없으면 에이전트가 후보를 못 찾는다.</li>
 *   <li>[새 노트로 등록] — 실제로는 새 커피인데 기존 노트로 판정된 경우. <b>싱글 오리진은 커피명이
 *       산지·농장·품종에서 오므로 이름이 같은 다른 커피가 흔하다</b>(사용자 확정 2026-08-01).</li>
 * </ul>
 *
 * 한쪽만 열어 두면 배지가 한 방향으로만 정정 가능해지고, "판정을 되돌릴 수 없다"는 것이 이 델타가
 * 없애려는 실패의 성질이다(delta.md §1.3).
 */
interface MatchBadgeProps {
  draft: Draft
  busy: boolean
  onChange: (draft: Draft) => void
}

export function MatchBadge({ draft, busy, onChange }: MatchBadgeProps) {
  const [open, setOpen] = useState(false)

  return (
    <>
      <button
        type="button"
        className="badge"
        onClick={() => setOpen(true)}
        disabled={busy}
        aria-haspopup="dialog"
      >
        <span>{describeMatch(draft)}</span>
        <span className="badge__chevron" aria-hidden="true">
          ›
        </span>
      </button>

      {/* 열 때마다 새로 마운트한다 — 검색어가 그때의 커피명에서 다시 시작한다. */}
      {open && (
        <CandidateSheet
          draft={draft}
          onPick={(next) => {
            onChange(next)
            setOpen(false)
          }}
          onClose={() => setOpen(false)}
        />
      )}
    </>
  )
}

/** 배지 문구 — 기존 노트면 무엇에 붙는지가 보여야 한다. 커피명이 아직 없으면 id로 떨어진다. */
function describeMatch(draft: Draft): string {
  if (draft.match.type === 'new') {
    return '새 노트'
  }
  const name = draft.note.coffee_name?.value
  return `기존 노트 · ${name && name !== '' ? name : `#${draft.match.note_id}`}`
}

// 타이핑마다 서버를 때리지 않는다. 검색은 목록을 좁히는 보조 수단이라 즉시성이 요구되지 않는다.
const DEBOUNCE_MS = 250

/**
 * 후보 목록 시트.
 *
 * `<dialog>`를 쓰는 것은 ESC·백드롭·포커스 트랩을 브라우저에서 얻기 위해서다 — 직접 구현하면
 * 접근성 처리가 통째로 이쪽 몫이 된다(right-sizing).
 *
 * 검색어 초기값이 폼의 커피명인 이유: 시트를 여는 동기가 "이 커피의 노트가 이미 있나"이므로 그 조회가
 * 이미 되어 있어야 한다. 비우면 전체 목록이 최근순으로 온다(TΔ7 계약).
 */
function CandidateSheet({
  draft,
  onPick,
  onClose,
}: {
  draft: Draft
  onPick: (draft: Draft) => void
  onClose: () => void
}) {
  const dialog = useRef<HTMLDialogElement>(null)
  const [query, setQuery] = useState(draft.note.coffee_name?.value ?? '')
  const [candidates, setCandidates] = useState<NoteCandidate[] | null>(null)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    dialog.current?.showModal()
  }, [])

  useEffect(() => {
    // 늦게 도착한 이전 검색어의 응답이 현재 목록을 덮지 않게 한다.
    let live = true
    const timer = setTimeout(() => {
      getNoteCandidates(query)
        .then((response) => {
          if (live) {
            setCandidates(response.candidates)
            setFailed(false)
          }
        })
        .catch(() => {
          // POLICY: 조회 실패를 조용히 빈 목록으로 보여주지 않는다 — "후보가 없다"와 "못 불러왔다"는
          //         사용자의 다음 행동이 다르다(전자는 새 노트, 후자는 재시도).
          if (live) {
            setCandidates([])
            setFailed(true)
          }
        })
    }, DEBOUNCE_MS)
    return () => {
      live = false
      clearTimeout(timer)
    }
  }, [query])

  return (
    <dialog
      ref={dialog}
      className="sheet"
      aria-label="매칭할 노트 고르기"
      onClose={onClose}
      onClick={(event) => {
        // 백드롭 클릭 — dialog 자신이 타깃이면 내용 밖이다.
        if (event.target === dialog.current) {
          dialog.current?.close()
        }
      }}
    >
      <div className="sheet__body">
        <div className="sheet__head">
          <h2 className="sheet__title">어느 노트에 붙일까요?</h2>
          <button type="button" className="sheet__close" onClick={() => dialog.current?.close()} aria-label="닫기">
            ✕
          </button>
        </div>

        <input
          className="sheet__search"
          value={query}
          placeholder="커피명 · 로스터리로 찾기"
          autoFocus
          onChange={(event) => setQuery(event.target.value)}
        />

        <ul className="sheet__list">
          {candidates === null && <li className="sheet__empty">찾는 중…</li>}
          {candidates !== null && candidates.length === 0 && (
            <li className="sheet__empty">{failed ? '목록을 불러오지 못했어요.' : '해당하는 노트가 없어요.'}</li>
          )}
          {candidates?.map((candidate) => (
            <li key={candidate.note_id}>
              <button
                type="button"
                className={`sheet__item${isCurrent(draft, candidate) ? ' sheet__item--current' : ''}`}
                onClick={() => onPick(selectExisting(draft, candidate))}
              >
                <span className="sheet__name">{candidate.coffee_name}</span>
                {/* 로스터리가 동명 후보를 가르는 유일한 축이다 — 커피명 다음에 바로 온다. */}
                <span className="sheet__meta">
                  {candidate.roastery ?? '로스터리 미상'}
                  {candidate.latest_date && ` · ${candidate.latest_date}`}
                </span>
              </button>
            </li>
          ))}
        </ul>

        {/* 목록 밖 고정 자리 — 후보를 고르는 것과 반대 방향의 결정이라 목록에 섞지 않는다. */}
        <button
          type="button"
          className="sheet__new"
          disabled={draft.match.type === 'new'}
          onClick={() => onPick(selectNew(draft))}
        >
          + 새 노트로 등록
        </button>
      </div>
    </dialog>
  )
}

function isCurrent(draft: Draft, candidate: NoteCandidate): boolean {
  return draft.match.type === 'existing' && draft.match.note_id === candidate.note_id
}
