import { useEffect, useRef, useState } from 'react'
import type { Draft, NoteCandidate, NoteDetail, NoteDetailEntry } from '../api'
import { getNoteCandidates, getNoteDetail } from '../api'
import { selectEditTarget, selectExisting, selectNew } from './draftEdits'

/**
 * 매칭 배지 + 변경 시트 — 에이전트의 판정을 사용자가 뒤집는 자리 (changes/0029 TΔ11 · TΔ28a, AC-1·13).
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
 *
 * <p><b>TΔ28a — 수정 모드에서는 시트가 두 걸음이다</b>(D-14, 사용자 확정 2026-08-02). 캡처 시트는 노트를
 * 고르면 끝이지만(어느 날에 붙일지는 서버가 정한다) 수정은 <b>노트가 아니라 그 안의 «어느 날 기록»</b>을
 * 가리켜야 한다. 후보 한 줄이 들고 있는 것은 노트까지라 거기서 멈추면 날짜를 시스템이 추측하게 되고,
 * 추측한 날짜로 저장하면 사용자가 고치려던 적 없는 기록이 덮인다. 그래서 노트를 고른 뒤 <b>그 노트의 기록
 * 목록</b>을 이어서 보여주고 사용자가 고른다.
 *
 * <p><b>모드가 유지되는 것도 여기서 정해진다</b>: 수정 시트를 거쳐도 배지는 `기존 기록 수정`인 채고
 * 대상만 바뀐다. 수정하려던 사람을 회차 추가로 흘려보내면 화면이 의도를 배신한다(기각한 1차안).
 */
interface MatchBadgeProps {
  draft: Draft
  busy: boolean
  onChange: (draft: Draft) => void
}

export function MatchBadge({ draft, busy, onChange }: MatchBadgeProps) {
  const [open, setOpen] = useState(false)
  const editing = draft.match.type === 'edit'

  return (
    <>
      <button
        type="button"
        className={`badge${editing ? ' badge--edit' : ''}`}
        onClick={() => setOpen(true)}
        disabled={busy}
        aria-haspopup="dialog"
      >
        <span>{describeMatch(draft)}</span>
        <span className="badge__chevron" aria-hidden="true">
          ›
        </span>
      </button>

      {/* 열 때마다 새로 마운트한다 — 검색어가 그때의 커피명에서 다시 시작하고 걸음도 1로 되돌아간다. */}
      {open && (
        <CandidateSheet
          draft={draft}
          editing={editing}
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

/**
 * 배지 문구 — 무엇에 붙는지와 **무엇이 일어나는지**가 함께 보여야 한다. 커피명이 아직 없으면 id로 떨어진다.
 *
 * 세 갈래가 각각 다른 저장을 뜻한다: 새 노트 생성 · 회차 append · 기존 회차 교체(TΔ28b).
 */
function describeMatch(draft: Draft): string {
  if (draft.match.type === 'new') {
    return '새 노트'
  }
  const name = draft.note.coffee_name?.value
  const label = name !== undefined && name !== '' ? name : `#${draft.match.note_id}`
  return draft.match.type === 'edit' ? `기존 기록 수정 · ${label}` : `기존 노트 · ${label}`
}

// 타이핑마다 서버를 때리지 않는다. 검색은 목록을 좁히는 보조 수단이라 즉시성이 요구되지 않는다.
const DEBOUNCE_MS = 250

/**
 * 후보 시트 — 캡처는 한 걸음, 수정은 두 걸음.
 *
 * `<dialog>`를 쓰는 것은 ESC·백드롭·포커스 트랩을 브라우저에서 얻기 위해서다 — 직접 구현하면
 * 접근성 처리가 통째로 이쪽 몫이 된다(right-sizing).
 *
 * 검색어 초기값이 폼의 커피명인 이유: 시트를 여는 동기가 "이 커피의 노트가 이미 있나"이므로 그 조회가
 * 이미 되어 있어야 한다. 비우면 전체 목록이 최근순으로 온다(TΔ7 계약).
 *
 * **걸음 2의 목록은 새 API를 열지 않는다** — 이미 있는 `GET /api/notes/{id}`가 날짜와 그날의 회차를
 * 함께 준다(TΔ13a 계약). 날짜만 따로 주는 엔드포인트를 만들면 고른 뒤 폼을 채울 재료를 다시 받아 와야
 * 해서 요청이 둘이 된다. **이 task가 서버를 한 줄도 건드리지 않는 것도 그래서다.**
 */
function CandidateSheet({
  draft,
  editing,
  onPick,
  onClose,
}: {
  draft: Draft
  editing: boolean
  onPick: (draft: Draft) => void
  onClose: () => void
}) {
  const dialog = useRef<HTMLDialogElement>(null)
  const [query, setQuery] = useState(draft.note.coffee_name?.value ?? '')
  const [candidates, setCandidates] = useState<NoteCandidate[] | null>(null)
  const [failed, setFailed] = useState(false)
  // 걸음 2 — 고른 노트. null이면 아직 후보 목록이다(캡처 모드에서는 계속 null이다).
  const [target, setTarget] = useState<NoteCandidate | null>(null)

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
      aria-label={editing ? '고칠 기록 고르기' : '매칭할 노트 고르기'}
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
          {target === null ? (
            <h2 className="sheet__title">{editing ? '어느 기록을 고칠까요?' : '어느 노트에 붙일까요?'}</h2>
          ) : (
            // 되돌아갈 길이 없으면 커피를 잘못 골랐을 때 시트를 닫았다 다시 열어야 한다.
            <button type="button" className="sheet__back" onClick={() => setTarget(null)}>
              ‹ 커피 다시 고르기
            </button>
          )}
          <button type="button" className="sheet__close" onClick={() => dialog.current?.close()} aria-label="닫기">
            ✕
          </button>
        </div>

        {target === null ? (
          <>
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
              {candidates?.map((candidate) => {
                // 기록이 없는 노트는 고칠 것이 없다 — `latest_date`가 비는 것이 그 신호다(TΔ11 계약).
                // 캡처 모드에서는 정상 후보다(회차를 붙일 수 있다).
                const empty = editing && candidate.latest_date === null
                return (
                  <li key={candidate.note_id}>
                    <button
                      type="button"
                      className={`sheet__item${isCurrent(draft, candidate) ? ' sheet__item--current' : ''}`}
                      disabled={empty}
                      onClick={() =>
                        editing ? setTarget(candidate) : onPick(selectExisting(draft, candidate))
                      }
                    >
                      <span className="sheet__name">{candidate.coffee_name}</span>
                      {/* 로스터리가 동명 후보를 가르는 유일한 축이다 — 커피명 다음에 바로 온다. */}
                      <span className="sheet__meta">
                        {candidate.roastery ?? '로스터리 미상'}
                        {candidate.latest_date !== null && ` · ${candidate.latest_date}`}
                        {empty && ' · 기록 없음'}
                      </span>
                    </button>
                  </li>
                )
              })}
            </ul>

            {/* 목록 밖 고정 자리 — 후보를 고르는 것과 반대 방향의 결정이라 목록에 섞지 않는다.
                수정 모드에는 없다: 고칠 기존 기록을 지목해 놓고 새 노트를 만드는 것은 뜻이 성립하지
                않는 조합이다. "이건 수정이 아니었다"는 판단은 [취소] 후 다시 말하는 경로다(D-14). */}
            {!editing && (
              <button
                type="button"
                className="sheet__new"
                disabled={draft.match.type === 'new'}
                onClick={() => onPick(selectNew(draft))}
              >
                + 새 노트로 등록
              </button>
            )}
          </>
        ) : (
          <EntryPicker target={target} onPick={onPick} />
        )}
      </div>
    </dialog>
  )
}

/**
 * 걸음 2 — 그 커피의 어느 날 기록인가 (TΔ28a).
 *
 * **고르기 «전»에 대가를 알린다**(사용자 확정 2026-08-02): 고른 기록의 저장된 값으로 폼이 통째로 갈리므로
 * 모카가 반영해 뒀던 요구(*"평가 낮춰줘"*)가 사라진다. 고른 뒤에 알리면 사용자는 *"낮춘 게 왜 원래대로
 * 돌아왔지"*를 먼저 겪고, 여기서 알리면 무를 기회가 함께 있다.
 */
function EntryPicker({ target, onPick }: { target: NoteCandidate; onPick: (draft: Draft) => void }) {
  const [detail, setDetail] = useState<NoteDetail | null>(null)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    let live = true
    getNoteDetail(target.note_id)
      .then((loaded) => {
        if (live) {
          setDetail(loaded)
          setFailed(false)
        }
      })
      .catch(() => {
        if (live) {
          setFailed(true)
        }
      })
    return () => {
      live = false
    }
  }, [target.note_id])

  return (
    <>
      <h2 className="sheet__title sheet__title--sub">
        {target.coffee_name}
        <span className="sheet__meta"> · {target.roastery ?? '로스터리 미상'}</span>
      </h2>
      <p className="sheet__hint">고른 기록의 저장된 값으로 폼이 채워져. 지금 폼에 담긴 수정 내용은 사라져.</p>

      <ul className="sheet__list">
        {detail === null && !failed && <li className="sheet__empty">불러오는 중…</li>}
        {failed && <li className="sheet__empty">기록을 불러오지 못했어요.</li>}
        {detail !== null && detail.entries.length === 0 && <li className="sheet__empty">아직 기록이 없어요.</li>}
        {/* 상세는 날짜 오름차순이다(TΔ13a 계약) — 고를 때는 최근 것이 위여야 한다.
            "어제 마신 그거"가 손에 가까운 자리에 온다. */}
        {detail !== null &&
          [...detail.entries].reverse().map((entry) => (
            <li key={entry.date}>
              <button
                type="button"
                className="sheet__item"
                onClick={() => onPick(selectEditTarget(detail, entry.date))}
              >
                <span className="sheet__date">{entry.date}</span>
                <span className="sheet__meta">{summarize(entry)}</span>
              </button>
            </li>
          ))}
      </ul>
    </>
  )
}

/**
 * 기록 한 줄의 요약 — 회차 수 · 평가 · 감상 첫머리.
 *
 * 날짜만으로는 그날이 어느 날인지 알아보기 어렵다. 감상 한 조각이 붙으면 *"아, 자몽 같다고 했던 날"*로
 * 잡힌다 — 갤러리·후보 목록이 로스터리로 동명 후보를 가르는 것과 같은 자리다.
 */
function summarize(entry: NoteDetailEntry): string {
  const parts = [`${entry.cups.length}회차`]
  const rating = entry.cups.map((cup) => cup.review?.rating).find((value) => value != null)
  const taste = entry.cups
    .map((cup) => cup.review?.my_taste)
    .find((value) => value != null && value !== '')
  if (rating != null) {
    parts.push(rating)
  }
  if (taste != null) {
    parts.push(`“${taste}”`)
  } else if (rating == null) {
    // 레시피만 남긴 날 — 빈 자리를 두면 "왜 이 줄만 짧지"가 된다(V-15: 둘 중 하나는 있다).
    parts.push('레시피만')
  }
  return parts.join(' · ')
}

/** 지금 붙어 있는(또는 고치는 중인) 노트 — 시트가 어디에서 출발하는지가 보여야 한다. */
function isCurrent(draft: Draft, candidate: NoteCandidate): boolean {
  return draft.match.type !== 'new' && draft.match.note_id === candidate.note_id
}
