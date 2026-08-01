import { useEffect, useState } from 'react'
import type { NoteDetail, NoteDetailBrew, NoteDetailEntry, NotePhoto, Recipe, Source } from '../api'
import { getNoteDetail } from '../api'
import { SOURCE_LABELS } from '../formValues'
import { editPath, GALLERY } from '../routes'

/**
 * 상세 보기 화면 — 노트 전문 + 회차별 레시피·감상 + 그 날의 사진 (changes/0029 TΔ13a).
 *
 * **읽는 화면이다.** 고치는 일은 여기서 하지 않고 `/notes/{id}/edit`이 진다(TΔ13b) — 헤더의 [수정]이
 * 그리로 보내는 입구뿐이다. 공유(카드 생성)는 TΔ9이고 아직 버튼이 없다: 갈 곳 없는 버튼을 먼저 다는 것은
 * TΔ12가 카드를 링크로 만들지 않은 것과 같은 이유로 하지 않는다.
 *
 * **이 화면이 `GET /api/notes/{id}` 계약을 정했다**(D-10 ② *"화면이 API의 모양을 정한다"*). 정본은
 * `note-detail.contract.json`이고 **TΔ5a가 그것을 구현했다** — mock에서 실 DB로 갈아 끼우는 동안 이
 * 파일은 한 줄도 바뀌지 않았다. 화면이 실제로 보여주는 것만 계약에 실린 결과가 `NoteDetail`의 세
 * 절단이다 — `aliases`·`created_at`/`updated_at`·`my_taste_original`.
 *
 * 시안은 `design/노트 상세.dc.html`이다(ADR-54: 시안이 디자인 source of truth). 형태를 그대로 이식했다 —
 * 한 줄 헤더(‹ 목록 / COFFEE NOTE) + 가운데 정렬 커피명·로스터리 · 250px 히어로 · 52px 라벨의 메타
 * 그리드 · 날짜 rule · **회차 카드**(머리 + 3열 스탯 격자 + 문장 블록 + 감상). 시안과 갈린 것은 셋이고
 * 각각 그 자리 주석이 근거를 소유한다: ① 출처 배지 ② 추출량(`yield_ml`) 슬롯 ③ 참조 링크.
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
        //         지금은 둘을 문구로 가르지 않는다(404와 그 밖의 실패 모두 같은 안내). 서버는 TΔ5a부터
        //         실제로 404를 돌려주고, 가를 필요가 관측되면 그때가 판단 지점이다.
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
          <div className="detail__bar">
            <button type="button" className="detail__back" onClick={() => onNavigate(GALLERY)}>
              ‹ 목록
            </button>
            <div className="detail__eyebrow">COFFEE NOTE</div>
            {/*
              시안의 오른쪽 32px 스페이서 자리를 [수정]이 채운다(TΔ13b) — 노트가 서기 전에는 갈 곳이 없으니
              그때는 빈 스페이서로 남아 가운데 정렬을 지킨다. 시안에 없는 편차이고, 델타가 수정을 UI 전용으로
              옮긴 이상(D-1) 저장된 노트에서 그리로 가는 입구가 하나는 있어야 한다.
            */}
            {note === null ? (
              <div className="detail__bar-pad" />
            ) : (
              <button type="button" className="detail__edit" onClick={() => onNavigate(editPath(note.note_id))}>
                수정
              </button>
            )}
          </div>
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
            {/* 사진이 없어도 자리는 남는다 — 시안의 사선 패턴이 그대로 채우고, 갤러리 카드가 빈 칸을
                다루는 방식과 같은 답이다. 시안의 "봉투 사진" 글자는 목업 라벨이라 이식하지 않았다. */}
            <div className="detail__hero">{hero !== null && <img src={hero.url} alt="" />}</div>

            <Meta note={note} />

            <div className="detail__entries">
              {note.entries.length === 0 && (
                // 엔트리 없는 노트는 정상 상태다(계약 예시가 박는다) — 빈 화면 대신 그렇다고 말한다.
                <div className="detail__empty">아직 시음 기록이 없어요.</div>
              )}
              {note.entries.map((entry) => (
                <EntrySection key={entry.date} entry={entry} />
              ))}

              {/* 시안과 갈린 것 ③ — 참조 링크는 시안에 없다. FR-12가 저장하는 값이고 캡처 폼도 보여주므로
                  같은 어휘로 맨 아래에 둔다. 없으면 그리지 않으니 시안 형태를 해치지 않는다. */}
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
 * 노트 메타 — 원두·로스팅·공식 노트. 시안의 2열 그리드(52px 라벨, 오른쪽 정렬) 그대로다.
 *
 * 값이 없는 줄은 그리지 않는다 — 저장된 노트는 대부분의 메타가 비어 있을 수 있고(계약의 최소형 예시),
 * 빈 라벨만 늘어놓으면 없는 것이 결손처럼 보인다. 회차 스탯이 없는 값을 `—`로 남기는 것과 반대인데,
 * 저쪽은 **격자가 무너지지 않아야** 하고 이쪽은 줄 수가 자유롭다.
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

/**
 * 날짜 하나 = 날짜 rule + 그 날의 사진 + 회차 카드들. `date`가 엔트리의 유일 키다(V-3).
 *
 * 사진이 회차 위에 오는 것이 시안의 순서다 — 그 날의 장면이 먼저고 기록이 뒤다.
 */
function EntrySection({ entry }: { entry: NoteDetailEntry }) {
  return (
    <section className="entry">
      <div className="entry__rule">
        <span className="entry__date">{formatDate(entry.date)}</span>
        <i />
      </div>

      {entry.photos.length > 0 && (
        <div className="entry__photos">
          {entry.photos.map((photo) => (
            <img key={photo.url} src={photo.url} alt="" loading="lazy" />
          ))}
        </div>
      )}

      {entry.brews.map((brew, index) => (
        <BrewCard key={index} brew={brew} no={index + 1} />
      ))}
    </section>
  )
}

/**
 * 회차 1개 = 카드 하나 — 머리(회차·방식) · 스탯 격자 · 문장 블록 · 감상.
 *
 * 레시피·감상 중 있는 것만 그린다(둘 다 null인 회차는 저장되지 않는다, V-15). 감상만 있는 회차는 머리와
 * 감상만 남고, 그것도 시안 카드의 정상 형태다.
 */
function BrewCard({ brew, no }: { brew: NoteDetailBrew; no: number }) {
  const recipe = brew.recipe
  const notes = recipe === null ? [] : sentenceRows(recipe)

  return (
    <div className="brew">
      <div className="brew__head">
        <span className="brew__no">{no}회차</span>
        {recipe?.method != null && <span className="brew__method">{recipe.method}</span>}
      </div>

      {recipe !== null && <Stats recipe={recipe} />}

      {notes.length > 0 && (
        <div className="brew__notes">
          {notes.map((row) => (
            <div key={row.label}>
              <div className="brew__label">{row.label}</div>
              <div className="brew__sentence">{row.value}</div>
            </div>
          ))}
        </div>
      )}

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
 * 레시피 수치 — 시안의 3열 격자. **없는 값은 자리를 비우지 않고 `—`로 남긴다**(시안 2회차의 `시간`).
 *
 * 슬롯은 고정이다: 원두 · 물(또는 추출) · 시간 · 물온도 · 분쇄도·기구. 방식별 분기가 없는 flat
 * 스키마(V-8)라 화면도 분기하지 않는다.
 *
 * **시안과 갈린 것 ②**: 시안에 `추출`(`yield_ml`) 슬롯이 없다 — 핸드드립 예시만 그려서 생긴 공백이고,
 * 같은 스키마로 에스프레소도 기록된다(그쪽은 추출량이 핵심 수치다). 그래서 **값이 있을 때만 슬롯을
 * 보탠다** — 핸드드립 노트에서는 시안과 정확히 같은 6칸(4 + span 2)이 나오고, 에스프레소는 `추출`이
 * 한 칸 늘어 격자가 3의 배수로 다시 맞는다.
 *
 * 마지막 `분쇄도 · 기구` 칸이 남은 열을 채운다 — 앞의 칸들은 전부 1칸이라 순서가 곧 열 위치이고,
 * 그래서 테두리 규칙(3n번째의 오른쪽 선 제거)이 span이 있어도 성립한다.
 */
function Stats({ recipe }: { recipe: Recipe }) {
  const cells: { label: string; value: string | null }[] = [{ label: '원두', value: unit(recipe.dose_g, 'g') }]
  if (recipe.water_ml !== null || recipe.yield_ml === null) {
    cells.push({ label: '물', value: unit(recipe.water_ml, 'ml') })
  }
  if (recipe.yield_ml !== null) {
    cells.push({ label: '추출', value: unit(recipe.yield_ml, 'ml') })
  }
  cells.push({ label: '시간', value: recipe.time_sec === null ? null : formatSeconds(recipe.time_sec) })
  cells.push({ label: '물온도', value: unit(recipe.temp_c, '℃') })

  // 수치가 하나도 없는 레시피(문장만 남긴 기록)는 격자를 통째로 접는다 — `—`만 다섯 칸 세우지 않는다.
  if (cells.every((cell) => cell.value === null) && recipe.grind === null && recipe.machine === null) {
    return null
  }

  const rest = cells.length % 3
  return (
    <div className="brew__stats">
      {cells.map((cell) => (
        <div className="stat" key={cell.label}>
          <div className="stat__label">{cell.label}</div>
          <div className={cell.value === null ? 'stat__value stat__value--absent' : 'stat__value'}>
            {cell.value ?? '—'}
          </div>
        </div>
      ))}
      <div className="stat" style={{ gridColumn: `span ${rest === 0 ? 3 : 3 - rest}` }}>
        <div className="stat__label">{grindLabel(recipe)}</div>
        <div className="stat__pair">
          {recipe.grind === null && recipe.machine === null ? (
            <div className="stat__value stat__value--absent">—</div>
          ) : (
            <>
              {/* 기구만 있으면 그것이 이 칸의 값이다 — 라벨도 함께 바뀐다(grindLabel). */}
              <div className="stat__value">{recipe.grind ?? recipe.machine}</div>
              {recipe.grind !== null && recipe.machine !== null && (
                <div className="stat__machine">{recipe.machine}</div>
              )}
            </>
          )}
        </div>
      </div>
    </div>
  )
}

function grindLabel(recipe: Recipe): string {
  if (recipe.grind === null && recipe.machine !== null) {
    return '기구'
  }
  return recipe.machine === null ? '분쇄도' : '분쇄도 · 기구'
}

/** 문장류는 격자에 넣지 않는다 — 길이가 제각각이라 칸에 갇히면 읽히지 않는다(시안의 별도 블록). */
function sentenceRows(recipe: Recipe): { label: string; value: string }[] {
  return [
    recipe.pouring !== null && { label: '푸어링', value: recipe.pouring },
    recipe.feedback !== null && { label: '관찰', value: recipe.feedback },
  ].filter((row) => row !== false)
}

function unit(value: number | null, suffix: string): string | null {
  return value === null ? null : `${value}${suffix}`
}

/**
 * 출처는 `user`가 아닐 때만 보여준다 — 캡처 폼(`DraftForm`)과 같은 규칙·같은 어휘다.
 *
 * **시안과 갈린 것 ①**: 시안에는 출처 표시가 없다. 그래도 두는 것은 *"이 로스팅 표기는 내가 적은 게
 * 아니라 봉투 사진에서 읽은 것"*이 신뢰를 가르는 정보이기 때문이고(사용자 확정 2026-08-01, TΔ13a),
 * 시안이 정본인 범위와 부딪히지 않게 **값보다 작고 약하게** 붙인다 — TΔ12에서 필터 바가 시안보다
 * 나중의 델타 확정을 따른 것과 같은 판단이다.
 */
function SourceMark({ source }: { source?: Source }) {
  if (source === undefined || source === 'user') {
    return null
  }
  return <em className="detail__source">{SOURCE_LABELS[source]}</em>
}

/** `2026-07-02` → `2026. 7. 2` — 시안의 날짜 표기다. 형식이 아니면 받은 값을 그대로 쓴다. */
function formatDate(date: string): string {
  const matched = /^(\d{4})-(\d{2})-(\d{2})$/.exec(date)
  return matched === null ? date : `${matched[1]}. ${Number(matched[2])}. ${Number(matched[3])}`
}

/** 160 → `2:40` — 시안의 콜론 표기다. 에스프레소의 27초도 같은 형태로 `0:27`이 된다. */
function formatSeconds(seconds: number): string {
  const minutes = Math.floor(seconds / 60)
  return `${minutes}:${String(seconds % 60).padStart(2, '0')}`
}
