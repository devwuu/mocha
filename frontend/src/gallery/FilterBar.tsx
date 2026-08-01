import { useEffect, useRef, useState } from 'react'
import type { NoteFacets, NoteQuery, Rating } from '../api'
import { RATINGS } from '../api'
import { toggle } from './noteQuery'

/**
 * 갤러리 필터 바 — 4축 (changes/0029 TΔ12, AC-4).
 *
 * 시안 `design/리스트 - 갤러리.dc.html`은 드롭다운 3개(방식·평점·정렬)에 검색창이 없다. 여기서는
 * **델타 확정을 따른다**(사용자 확정 2026-08-01): 검색창 + 필터 4축(로스터리·가공방식·원산지·평가).
 * `open-questions.md` 검색 절의 확정이 시안보다 나중이고, ADR-54가 시안을 정본으로 삼는 범위는
 * **카드 디자인**이다. 시안에서 가져온 것은 팔레트·서체·그리드·헤더 형태이고, 필터 바의 형태는
 * TΔ11의 매칭 시트와 같은 성격으로 여기서 정했다(시안이 생기면 그쪽이 정본이 된다).
 *
 * **정렬 선택기는 두지 않았다** — 시안에는 `최신순 ▾`가 있으나 델타 확정 사항에 정렬 축이 없고, 축을
 * 늘리는 것은 spec에 없는 동작을 더하는 일이다(루트 §3). 갤러리는 최근 시음일 내림차순 하나로 돈다.
 *
 * 세 축은 칩 → 하단 시트(다중 선택)이고 **원산지만 인라인 텍스트 입력**이다. 원산지에 열거할 값 집합이
 * 없기 때문이다 — ADR-53이 `origin` 필드를 `beans[].description` 자유 텍스트로 흡수해, 이 축만
 * 부분일치로 근사한다(`contract.ts`의 `NoteQuery.origin` 주석이 근거를 소유한다).
 */
interface FilterBarProps {
  query: NoteQuery
  facets: NoteFacets
  onChange: (query: NoteQuery) => void
}

export function FilterBar({ query, facets, onChange }: FilterBarProps) {
  const [open, setOpen] = useState<Axis | null>(null)

  return (
    <>
      <div className="filters">
        <FilterChip
          label="로스터리"
          selected={query.roastery}
          // 저장된 값이 없으면 고를 것도 없다 — 빈 시트를 여는 대신 칩을 잠근다.
          disabled={facets.roastery.length === 0}
          onOpen={() => setOpen('roastery')}
        />
        <FilterChip
          label="가공방식"
          selected={query.process}
          disabled={facets.process.length === 0}
          onOpen={() => setOpen('process')}
        />
        {/* 평가는 facet이 아니라 고정 4범주다(V-1) — 아직 그 평가를 준 노트가 없어도 고를 수 있다. */}
        <FilterChip label="평가" selected={query.rating} disabled={false} onOpen={() => setOpen('rating')} />
        <input
          className="filters__origin"
          value={query.origin}
          placeholder="원산지"
          aria-label="원산지로 거르기"
          onChange={(event) => onChange({ ...query, origin: event.target.value })}
        />
      </div>

      {open !== null && (
        <OptionSheet
          axis={open}
          options={optionsFor(open, facets)}
          selected={query[open]}
          onToggle={(value) => onChange(withToggled(query, open, value))}
          onClear={() => onChange(withCleared(query, open))}
          onClose={() => setOpen(null)}
        />
      )}
    </>
  )
}

/** 다중 선택 축 3종. 원산지는 자유 텍스트라 여기 없다. */
type Axis = 'roastery' | 'process' | 'rating'

const AXIS_LABEL: Record<Axis, string> = {
  roastery: '로스터리',
  process: '가공방식',
  rating: '평가',
}

function optionsFor(axis: Axis, facets: NoteFacets): readonly string[] {
  if (axis === 'rating') {
    return RATINGS
  }
  return facets[axis]
}

/*
 * 축별로 분기를 펴 둔 것은 의도다 — 계산 키(`{...query, [axis]: …}`)로 쓰면 `rating: Rating[]`이
 * `string[]`으로 넓어져 **4범주 밖의 값이 쿼리에 실리는 것을 컴파일러가 놓친다**(V-1). 축이 셋뿐이라
 * 감수할 만한 장황함이고, 축이 늘면 그때가 판단 지점이다.
 */
function withToggled(query: NoteQuery, axis: Axis, value: string): NoteQuery {
  switch (axis) {
    case 'roastery':
      return { ...query, roastery: toggle(query.roastery, value) }
    case 'process':
      return { ...query, process: toggle(query.process, value) }
    case 'rating':
      return { ...query, rating: toggle(query.rating, value as Rating) }
  }
}

function withCleared(query: NoteQuery, axis: Axis): NoteQuery {
  switch (axis) {
    case 'roastery':
      return { ...query, roastery: [] }
    case 'process':
      return { ...query, process: [] }
    case 'rating':
      return { ...query, rating: [] }
  }
}

/**
 * 칩 문구는 시안의 `방식 전체 ▾` 형태를 따른다 — 고른 것이 하나면 그 값을, 여럿이면 개수를 보인다.
 *
 * 값을 그대로 보이는 쪽이 개수보다 낫지만 로스터리 셋을 나열하면 칩이 줄을 통째로 먹는다. 하나일 때만
 * 값을 보이는 것이 그 절충이고, 실사용에서 대개 하나다.
 */
function FilterChip({
  label,
  selected,
  disabled,
  onOpen,
}: {
  label: string
  selected: readonly string[]
  disabled: boolean
  onOpen: () => void
}) {
  return (
    <button
      type="button"
      className={`chip${selected.length > 0 ? ' chip--on' : ''}`}
      disabled={disabled}
      aria-haspopup="dialog"
      onClick={onOpen}
    >
      <span className="chip__label">{label}</span>
      <span className="chip__value">
        {selected.length === 0 ? '전체' : selected.length === 1 ? selected[0] : selected.length}
      </span>
      <span className="chip__caret" aria-hidden="true">
        ▾
      </span>
    </button>
  )
}

/**
 * 선택지 시트 — TΔ11의 매칭 시트와 같은 `<dialog>` 형태다.
 *
 * `<dialog>`를 쓰는 이유도 같다: ESC·백드롭·포커스 트랩을 브라우저에서 얻는다. 형태를 맞춘 것은
 * 취향이 아니라 **폰에서 같은 제스처로 닫히게** 하려는 것이다.
 *
 * 고른 즉시 목록이 다시 조회된다(시트 안에 [적용] 버튼이 없다) — 필터는 되돌리기 쉬운 조작이고,
 * 확정 단계를 넣으면 "무엇을 고르면 몇 개가 남는가"를 보며 좁히지 못한다.
 */
function OptionSheet({
  axis,
  options,
  selected,
  onToggle,
  onClear,
  onClose,
}: {
  axis: Axis
  options: readonly string[]
  selected: readonly string[]
  onToggle: (value: string) => void
  onClear: () => void
  onClose: () => void
}) {
  const dialog = useRef<HTMLDialogElement>(null)

  useEffect(() => {
    dialog.current?.showModal()
  }, [])

  return (
    <dialog
      ref={dialog}
      className="sheet"
      aria-label={`${AXIS_LABEL[axis]}로 거르기`}
      onClose={onClose}
      onClick={(event) => {
        if (event.target === dialog.current) {
          dialog.current?.close()
        }
      }}
    >
      <div className="sheet__body">
        <div className="sheet__head">
          <h2 className="sheet__title">{AXIS_LABEL[axis]}</h2>
          <button type="button" className="sheet__close" onClick={() => dialog.current?.close()} aria-label="닫기">
            ✕
          </button>
        </div>

        <ul className="sheet__list">
          <li>
            {/* [전체]가 목록 맨 위에 있는 것은 시안의 `전체` 표기와 같은 자리다 — 축을 푸는 행동이 축 안의
                한 값을 고르는 행동과 섞이지 않게 체크 표시 대신 비움으로 그린다. */}
            <button
              type="button"
              className={`sheet__item sheet__item--option${selected.length === 0 ? ' sheet__item--current' : ''}`}
              onClick={onClear}
            >
              <span className="sheet__name">전체</span>
            </button>
          </li>
          {options.map((option) => (
            <li key={option}>
              <button
                type="button"
                className={`sheet__item sheet__item--option${selected.includes(option) ? ' sheet__item--current' : ''}`}
                aria-pressed={selected.includes(option)}
                onClick={() => onToggle(option)}
              >
                <span className="sheet__name">{option}</span>
                <span className="sheet__check" aria-hidden="true">
                  {selected.includes(option) ? '✓' : ''}
                </span>
              </button>
            </li>
          ))}
        </ul>
      </div>
    </dialog>
  )
}
