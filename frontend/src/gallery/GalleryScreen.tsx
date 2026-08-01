import { useEffect, useMemo, useRef, useState } from 'react'
import type { NoteFacets, NoteQuery, NoteSummary } from '../api'
import { getNotes } from '../api'
import { CHAT } from '../routes'
import { FilterBar } from './FilterBar'
import { EMPTY_QUERY, isFiltered, toSearchParams } from './noteQuery'

/**
 * 갤러리 화면 — 사진 썸네일 그리드 + 검색 + 필터 4축 (changes/0029 TΔ12, AC-4, FR-8/US-4 부활).
 *
 * 시안은 `design/리스트 - 갤러리.dc.html`이다. 팔레트·서체·2열 그리드·헤더(COFFEE JOURNAL / 노트 목록 /
 * N편의 기록)·카드 형태(사진 위 그라데이션, 로스터리 작게 + 커피명 크게)를 가져왔고, 시안과 갈린 것은
 * 셋이다: ① 검색창 + 4축 필터(델타 확정 — `FilterBar` 주석이 근거를 소유한다) ② 하단 페이지네이션 대신
 * **무한 스크롤**(사용자 확정 2026-08-01) ③ 시안에 없는 대화 화면 이동.
 *
 * **D-5가 이 화면의 존재 이유다**: changes/0021이 목록 화면을 폐기한 사유가 *"정적 HTML이라 조작이
 * 안 됐다"*였고, 검색·필터·(이후) 수정·삭제가 붙은 이번 것은 그 사유가 해소된 형태다. 원두 봉투 사진을
 * 카드처럼 보여주는 것이 ADR-32(*"사진은 렌더링하지 않는다"*) 폐기의 실현이다.
 *
 * **API는 아직 없다** — `getNotes`가 mock이고(`api/mock.ts`) 이 화면이 도출한 계약을 TΔ5a가 구현한다
 * (D-10 ③ *"화면이 API 계약을 정한다"*). 정본은 `note-list.contract.json`이다.
 */
interface GalleryScreenProps {
  onNavigate: (path: string) => void
}

// 타이핑·칩 조작마다 서버를 때리지 않는다. TΔ11의 후보 시트와 같은 값이다.
const DEBOUNCE_MS = 250

export function GalleryScreen({ onNavigate }: GalleryScreenProps) {
  const [query, setQuery] = useState<NoteQuery>(EMPTY_QUERY)
  // null = 첫 조회 전. 빈 배열(결과 없음)과 구분해야 "찾는 중"과 "없다"를 갈라 안내할 수 있다.
  const [notes, setNotes] = useState<NoteSummary[] | null>(null)
  const [cursor, setCursor] = useState<string | null>(null)
  const [total, setTotal] = useState(0)
  const [facets, setFacets] = useState<NoteFacets>(EMPTY_FACETS)
  const [loading, setLoading] = useState(false)
  const [failed, setFailed] = useState(false)
  const sentinel = useRef<HTMLDivElement>(null)

  /*
   * 늦게 도착한 이전 조회의 응답이 현재 목록을 덮지 않게 하는 세대 번호.
   *
   * 첫 페이지와 이어붙이기가 **서로 다른 effect**라 각자의 정리 함수만으로는 부족하다 — 필터를 바꾼
   * 직후 도착한 이어붙이기 응답은 지워진 목록 뒤에 남의 페이지를 붙인다. 한쪽에서 올린 번호를 양쪽이
   * 함께 보는 것이 그 창을 닫는다.
   */
  const generation = useRef(0)
  // 이어붙이기가 진행 중인가 — 감시자 통지와 "이미 보이는가" 직접 확인이 겹치는 창을 닫는다.
  const inFlight = useRef(false)

  // 객체 동일성이 아니라 **실제로 서버에 물어볼 내용**이 바뀌었을 때만 다시 조회한다.
  const key = useMemo(() => toSearchParams(query, null).toString(), [query])

  useEffect(() => {
    const mine = ++generation.current
    setLoading(true)
    const timer = setTimeout(() => {
      getNotes(query, null)
        .then((response) => {
          if (generation.current !== mine) {
            return
          }
          setNotes(response.notes)
          setCursor(response.next_cursor)
          setTotal(response.total)
          setFacets(response.facets)
          setFailed(false)
          setLoading(false)
        })
        .catch(() => {
          // POLICY: 조회 실패를 조용히 빈 목록으로 보여주지 않는다 — "기록이 없다"와 "못 불러왔다"는
          //         사용자의 다음 행동이 다르다(TΔ11 후보 시트와 같은 판단).
          if (generation.current !== mine) {
            return
          }
          setNotes([])
          setCursor(null)
          setTotal(0)
          setFailed(true)
          setLoading(false)
        })
    }, DEBOUNCE_MS)
    return () => clearTimeout(timer)
    // 의존이 query가 아니라 key인 것이 요점이다 — query는 key에서 나온 값이라 함께 움직이고,
    // key로 두면 "같은 질의면 다시 부르지 않는다"가 객체 동일성과 무관하게 성립한다.
  }, [key])

  /*
   * 무한 스크롤 — 그리드 끝의 감시자가 화면에 들어오면 다음 페이지를 잇는다.
   *
   * 스크롤 위치를 재지 않고 `IntersectionObserver`를 쓰는 것은 스크롤 이벤트 throttling·컨테이너
   * 높이 계산이 통째로 이쪽 몫이 되기 때문이다(right-sizing). 더 가져올 것이 없으면(`cursor === null`)
   * 감시 자체를 걸지 않는다.
   *
   * **감시자만으로는 부족하다**: 관측하는 것이 *"보이게 되는 순간"*이라, 노트가 적어 그리드가 화면보다
   * 짧으면 감시자가 처음부터 보이는 채로 서 있고 그 순간이 영영 오지 않는다 — 스크롤할 것이 없으니
   * 사용자가 만들 수도 없다. 실측(2026-08-01)에서 첫 페이지 4건에서 멈춰 이어지지 않은 것이 그 형태다.
   * 그래서 감시를 건 직후 **이미 보이는지 한 번 직접 본다.** 화면이 찰 때까지 이어지고, 그 뒤로는
   * 감시자가 받는다.
   */
  useEffect(() => {
    const target = sentinel.current
    if (target === null || cursor === null || loading) {
      return
    }
    let live = true

    function more() {
      // 감시자 통지와 직접 확인이 같은 틱에 겹칠 수 있다 — 같은 커서로 두 번 부르지 않는다.
      if (!live || inFlight.current) {
        return
      }
      inFlight.current = true
      observer.disconnect()
      const mine = generation.current
      setLoading(true)
      getNotes(query, cursor)
        .then((response) => {
          if (generation.current !== mine) {
            return
          }
          setNotes((prev) => [...(prev ?? []), ...response.notes])
          setCursor(response.next_cursor)
          setTotal(response.total)
          setLoading(false)
        })
        .catch(() => {
          if (generation.current !== mine) {
            return
          }
          // 이어붙이기 실패는 이미 보고 있는 목록을 버리지 않는다 — 커서를 비워 더 조르지 않게만 한다.
          setCursor(null)
          setFailed(true)
          setLoading(false)
        })
        .finally(() => {
          inFlight.current = false
        })
    }

    const observer = new IntersectionObserver((entries) => {
      if (entries[0].isIntersecting) {
        more()
      }
    })
    observer.observe(target)

    // 스크롤 컨테이너는 그리드 자신이다(`overflow-y: auto`) — 감시자가 그 바닥 안에 있으면 이미 보인다.
    const scroller = target.parentElement
    if (scroller !== null && target.getBoundingClientRect().top <= scroller.getBoundingClientRect().bottom) {
      more()
    }

    return () => {
      live = false
      observer.disconnect()
    }
    // key가 의존에 있는 것은 필터가 바뀌면 감시를 새로 걸기 위해서다 — 옛 커서로 이어붙이지 않는다.
  }, [cursor, loading, key])

  return (
    <div className="shell">
      <div className="panel">
        <header className="gallery__header">
          {/* 시안에 없는 편차 ③ — 화면이 둘이 됐으므로 서로 오갈 자리가 필요하다. */}
          <button type="button" className="gallery__back" onClick={() => onNavigate(CHAT)}>
            ‹ 대화
          </button>
          <div className="gallery__eyebrow">COFFEE JOURNAL</div>
          <h1 className="gallery__title">노트 목록</h1>
          {/* 필터가 걸리면 함께 줄어든다 — 몇 개로 좁혀졌는지가 필터의 유일한 피드백이다. */}
          <div className="gallery__count">{notes === null ? ' ' : `${total}편의 기록`}</div>
        </header>

        <input
          className="gallery__search"
          value={query.q}
          placeholder="커피명 · 로스터리로 찾기"
          aria-label="노트 검색"
          onChange={(event) => setQuery({ ...query, q: event.target.value })}
        />

        <FilterBar query={query} facets={facets} onChange={setQuery} />

        <div className="gallery__grid">
          {notes?.map((note) => (
            <NoteCard key={note.note_id} note={note} />
          ))}

          {/* 감시자는 그리드 안에 두되 칸을 차지하지 않는다 — 마지막 카드 바로 뒤가 "끝"이다. */}
          <div ref={sentinel} className="gallery__sentinel" />
        </div>

        {notes === null && <div className="gallery__notice">불러오는 중…</div>}
        {notes !== null && notes.length === 0 && (
          <div className="gallery__notice">
            {failed
              ? '목록을 불러오지 못했어요.'
              : isFiltered(query)
                ? '조건에 맞는 기록이 없어요.'
                : '아직 기록이 없어요. 대화로 첫 잔을 남겨 보세요.'}
          </div>
        )}
        {/* 이어붙이기 실패는 이미 보고 있는 목록 아래에 남긴다 — 목록을 지우지 않는다. */}
        {notes !== null && notes.length > 0 && failed && (
          <div className="gallery__notice">다음 목록을 불러오지 못했어요.</div>
        )}
        {notes !== null && notes.length > 0 && loading && <div className="gallery__notice">불러오는 중…</div>}
      </div>
    </div>
  )
}

const EMPTY_FACETS: NoteFacets = { roastery: [], process: [] }

/**
 * 그리드 한 칸 — 시안의 카드 형태 그대로다: 사진 위에 아래로 짙어지는 그라데이션, 그 위에 로스터리(작게)와
 * 커피명(크게).
 *
 * 사진이 없으면 시안의 **사선 패턴**이 그대로 배경이 된다(CSS가 소유) — 빈 칸을 남기지 않는 것이 시안의
 * 답이고, 발화만으로 기록한 노트가 그리드에서 사라져 보이지 않게 한다.
 *
 * 최근 시음일을 로스터리 줄에 붙인 것은 시안과 갈린 자리다. 목록이 최근순으로만 정렬되는데(정렬 선택기
 * 없음) 그 축이 화면에 하나도 안 보이면 순서가 임의로 읽힌다.
 *
 * ⚠️ **아직 링크가 아니다** — 상세 화면이 TΔ13a이고, 갈 곳이 없는 링크를 먼저 달면 눌렀을 때 아무 일도
 * 일어나지 않는 경로가 생긴다. TΔ13a가 `/notes/{id}`를 세우며 이 요소가 `<a>`가 된다.
 */
function NoteCard({ note }: { note: NoteSummary }) {
  return (
    <article className="note-card">
      {note.thumbnail_url !== null && (
        <img className="note-card__photo" src={note.thumbnail_url} alt="" loading="lazy" />
      )}
      <div className="note-card__scrim" />
      <div className="note-card__caption">
        <div className="note-card__meta">
          {note.roastery ?? '로스터리 미상'}
          {note.latest_date !== null && ` · ${note.latest_date}`}
        </div>
        <div className="note-card__name">{note.coffee_name}</div>
      </div>
    </article>
  )
}
