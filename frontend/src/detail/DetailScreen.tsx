import { useEffect, useState } from 'react'
import type { NoteDetail, NoteDetailBrew, NoteDetailEntry, NotePhoto, Recipe, Source } from '../api'
import { getNoteDetail } from '../api'
import { GALLERY } from '../routes'

/**
 * 상세 보기 화면 — 노트 전문 + 회차별 레시피·감상 + 그 날의 사진 (changes/0029 TΔ13a).
 *
 * **읽는 화면이다.** 수정·삭제는 TΔ13b, 공유(카드 생성)는 TΔ9이고 여기에는 그 버튼이 없다 — 갈 곳 없는
 * 버튼을 먼저 다는 것은 TΔ12가 카드를 링크로 만들지 않은 것과 같은 이유로 하지 않는다.
 *
 * **이 화면이 `GET /api/notes/{id}` 계약을 정한다**(D-10 ② *"화면이 API의 모양을 정한다"*). 지금
 * `getNoteDetail`은 mock이고 정본은 `note-detail.contract.json`이며, **구현은 TΔ5a**다. 화면이 실제로
 * 보여주는 것만 계약에 실린 결과가 `NoteDetail`의 세 절단이다 — `aliases`·`created_at`/`updated_at`·
 * `my_taste_original`.
 *
 * 시안에 상세 화면은 없다. 그래서 팔레트·서체·액자는 다른 화면과 공유하고, 형태는 **카드 시안**
 * (`design/노트 세리프 - 1 감상.dc.html`·`- 2 레시피 …`)의 어휘를 빌렸다 — 라벨을 오른쪽 정렬한 2열
 * 그리드, 평가 pill, 큰 숫자로 세우는 레시피 스탯. 상세와 카드는 같은 내용의 두 표면이라 서로 닮는 것이
 * 맞고, 카드 시안이 정본인 범위(ADR-54)를 넘지 않는다.
 */
interface DetailScreenProps {
  noteId: number
  onNavigate: (path: string) => void
}

export function DetailScreen({ noteId, onNavigate }: DetailScreenProps) {
  // null = 아직 조회 전. 실패와 구분해야 "불러오는 중"과 "못 불러왔다"를 갈라 안내할 수 있다(갤러리와 같은 판단).
  const [note, setNote] = useState<NoteDetail | null>(null)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    let live = true
    getNoteDetail(noteId)
      .then((loaded) => {
        if (live) {
          setNote(loaded)
          setFailed(false)
        }
      })
      .catch(() => {
        // POLICY: 실패를 빈 노트로 그리지 않는다 — 없는 기록과 못 불러온 기록은 사용자의 다음 행동이 다르다.
        //         지금은 둘을 문구로 가르지 않는다(404와 그 밖의 실패 모두 같은 안내). 서버가 실제로
        //         404를 돌려주기 시작하는 것은 TΔ5a이고, 가를 필요가 관측되면 그때가 판단 지점이다.
        if (live) {
          setFailed(true)
        }
      })
    return () => {
      live = false
    }
  }, [noteId])

  // 갤러리에서 보던 그 사진이 상단에 서야 진입 맥락이 이어진다 — 가장 최근 날짜의 첫 장이고, 목록의
  // thumbnail_url이 고르는 것과 같은 사진이다.
  const hero = note === null ? null : heroPhoto(note.entries)

  return (
    <div className="shell">
      <div className="panel">
        <header className="detail__header">
          <button type="button" className="gallery__back" onClick={() => onNavigate(GALLERY)}>
            ‹ 목록
          </button>
          <div className="gallery__eyebrow">COFFEE NOTE</div>
          <h1 className="detail__title">{note?.coffee_name.value ?? ' '}</h1>
          {note !== null && (
            <div className="detail__roastery">
              {note.roastery?.value ?? '로스터리 미상'}
              <SourceMark source={note.roastery?.source} />
            </div>
          )}
        </header>

        {note === null ? (
          <div className="detail__notice">{failed ? '노트를 불러오지 못했어요.' : '불러오는 중…'}</div>
        ) : (
          <div className="detail__body">
            {hero !== null && <img className="detail__hero" src={hero.url} alt="" />}

            <Meta note={note} />

            {note.entries.length === 0 && (
              // 엔트리 없는 노트는 정상 상태다(계약 예시가 박는다) — 빈 화면 대신 그렇다고 말한다.
              <div className="detail__empty">아직 시음 기록이 없어요.</div>
            )}
            {note.entries.map((entry) => (
              <EntrySection key={entry.date} entry={entry} />
            ))}

            {note.sources.length > 0 && (
              <ul className="detail__sources">
                {note.sources.map((source) => (
                  <li key={source}>
                    <a href={source} target="_blank" rel="noreferrer">
                      {source}
                    </a>
                  </li>
                ))}
              </ul>
            )}
          </div>
        )}
      </div>
    </div>
  )
}

/** 히어로 = 가장 최근 날짜의 첫 사진. 엔트리는 날짜 오름차순이므로 뒤에서부터 찾는다. */
function heroPhoto(entries: NoteDetailEntry[]): NotePhoto | null {
  for (let index = entries.length - 1; index >= 0; index--) {
    const photos = entries[index].photos
    if (photos.length > 0) {
      return photos[0]
    }
  }
  return null
}

/**
 * 노트 메타 — 원두·로스팅·공식 노트. 카드 시안의 2열 그리드(라벨 오른쪽 정렬) 그대로다.
 *
 * 값이 없는 줄은 그리지 않는다 — 저장된 노트는 대부분의 메타가 비어 있을 수 있고(계약의 최소형 예시),
 * 빈 라벨만 늘어놓으면 없는 것이 결손처럼 보인다.
 */
function Meta({ note }: { note: NoteDetail }) {
  const rows = [
    note.beans.length > 0 && (
      <div className="detail__beans">
        {note.beans.map((bean, index) => (
          <span key={index}>
            {bean.description.value}
            {bean.process !== null && <em className="detail__process">{bean.process.value}</em>}
          </span>
        ))}
      </div>
    ),
    note.roast_level !== null && (
      <>
        {note.roast_level.value}
        <SourceMark source={note.roast_level.source} />
      </>
    ),
    note.official_notes !== null && note.official_notes.value.length > 0 && (
      <>
        {note.official_notes.value.join(' · ')}
        <SourceMark source={note.official_notes.source} />
      </>
    ),
  ]
  const labels = ['원두', '로스팅', '노트']

  if (rows.every((row) => row === false)) {
    return null
  }
  return (
    <div className="detail__meta">
      {rows.map((row, index) =>
        row === false ? null : (
          <div className="detail__row" key={labels[index]}>
            <div className="detail__label">{labels[index]}</div>
            <div className="detail__value">{row}</div>
          </div>
        ),
      )}
    </div>
  )
}

/** 날짜 하나 = 그 날의 사진 + 회차들. `date`가 엔트리의 유일 키다(V-3). */
function EntrySection({ entry }: { entry: NoteDetailEntry }) {
  return (
    <section className="entry">
      <h2 className="entry__date">{formatDate(entry.date)}</h2>

      {entry.photos.length > 0 && (
        <div className="entry__photos">
          {entry.photos.map((photo) => (
            <img key={photo.url} src={photo.url} alt="" loading="lazy" />
          ))}
        </div>
      )}

      {entry.brews.map((brew, index) => (
        <BrewBlock key={index} brew={brew} no={index + 1} />
      ))}
    </section>
  )
}

/** 회차 1개 — 레시피·감상 중 있는 것만 그린다(둘 다 null인 회차는 저장되지 않는다, V-15). */
function BrewBlock({ brew, no }: { brew: NoteDetailBrew; no: number }) {
  return (
    <div className="brew">
      <div className="brew__no">
        {no}회차
        {/* 방식은 회차의 성격이라 번호 옆에 붙는다 — 시안 레시피 카드의 제목 옆 pill과 같은 자리다. */}
        {brew.recipe?.method != null && <span className="brew__method">{brew.recipe.method}</span>}
      </div>

      {brew.recipe !== null && <RecipeBlock recipe={brew.recipe} />}

      {brew.tasting !== null && (
        <div className="brew__tasting">
          <div className="brew__tasting-head">
            <span className="brew__label">내가 느끼길</span>
            {brew.tasting.rating !== null && <span className="brew__rating">{brew.tasting.rating}</span>}
          </div>
          {brew.tasting.my_taste !== null && <p className="brew__taste">{brew.tasting.my_taste}</p>}
        </div>
      )}
    </div>
  )
}

/**
 * 레시피 — 수치는 큰 글자로 세우고(시안의 3열 스탯), 문장류는 아래 2열 그리드로 내린다.
 *
 * 전 필드가 nullable이라(V-8) 있는 것만 그린다. 방식별 분기가 없는 flat 스키마이므로 화면도 분기하지
 * 않는다 — 에스프레소면 추출량·시간이 차고 핸드드립이면 물·푸어링이 차는 식으로 **데이터가 형태를 정한다.**
 */
function RecipeBlock({ recipe }: { recipe: Recipe }) {
  const stats = [
    recipe.dose_g !== null && { label: '원두', value: `${recipe.dose_g}g` },
    recipe.water_ml !== null && { label: '물', value: `${recipe.water_ml}ml` },
    recipe.yield_ml !== null && { label: '추출', value: `${recipe.yield_ml}ml` },
    recipe.time_sec !== null && { label: '시간', value: formatSeconds(recipe.time_sec) },
    recipe.temp_c !== null && { label: '물온도', value: `${recipe.temp_c}℃` },
    recipe.grind !== null && { label: '분쇄도', value: recipe.grind },
  ].filter((stat) => stat !== false)

  const rows = [
    recipe.machine !== null && { label: '기구', value: recipe.machine },
    recipe.pouring !== null && { label: '푸어링', value: recipe.pouring },
    recipe.feedback !== null && { label: '관찰', value: recipe.feedback },
  ].filter((row) => row !== false)

  return (
    <>
      {stats.length > 0 && (
        <div className="brew__stats">
          {stats.map((stat) => (
            <div className="stat" key={stat.label}>
              <div className="stat__label">{stat.label}</div>
              <div className="stat__value">{stat.value}</div>
            </div>
          ))}
        </div>
      )}
      {rows.length > 0 && (
        <div className="detail__meta">
          {rows.map((row) => (
            <div className="detail__row" key={row.label}>
              <div className="detail__label">{row.label}</div>
              <div className="detail__value">{row.value}</div>
            </div>
          ))}
        </div>
      )}
    </>
  )
}

/**
 * 출처는 `user`가 아닐 때만 보여준다 — 캡처 폼(`DraftForm`)과 같은 규칙·같은 어휘다.
 *
 * 저장된 뒤에도 값을 남긴 것은 *"이 로스팅 표기는 내가 적은 게 아니라 봉투 사진에서 읽은 것"*이 신뢰를
 * 가르는 정보이기 때문이다(사용자 확정 2026-08-01). 사용자 자신이 넣은 값에 배지를 다는 것은 소음이라
 * 하지 않는다.
 */
function SourceMark({ source }: { source?: Source }) {
  if (source === undefined || source === 'user') {
    return null
  }
  return <em className="detail__source">{SOURCE_LABELS[source]}</em>
}

const SOURCE_LABELS: Record<string, string> = { photo: '사진', search: '검색' }

/** `2026-07-02` → `2026. 7. 2` — 카드 시안의 날짜 표기다. 형식이 아니면 받은 값을 그대로 쓴다. */
function formatDate(date: string): string {
  const matched = /^(\d{4})-(\d{2})-(\d{2})$/.exec(date)
  return matched === null ? date : `${matched[1]}. ${Number(matched[2])}. ${Number(matched[3])}`
}

/** 160 → `2분 40초`. 분이 없으면 초만 쓴다 — 에스프레소의 27초를 `0분 27초`로 읽게 하지 않는다. */
function formatSeconds(seconds: number): string {
  const minutes = Math.floor(seconds / 60)
  const rest = seconds % 60
  if (minutes === 0) {
    return `${rest}초`
  }
  return rest === 0 ? `${minutes}분` : `${minutes}분 ${rest}초`
}
