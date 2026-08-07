import { Fragment, useEffect, useRef, useState } from 'react'
import type { ReactNode } from 'react'
import type {
  NoteDetail,
  NoteDetailCup,
  NoteDetailReview,
  NoteEntryUpdate,
  NoteMetaUpdate,
  Rating,
  Recipe,
  Sourced,
} from '../api'
import { deleteNote, getNoteDetail, patchNoteEntry, patchNoteMeta, RATINGS } from '../api'
import { numberValue, SOURCE_LABELS, textValue, userValue } from '../formValues'
import { GALLERY, notePath } from '../routes'
import type { EntryDraft } from './noteEdits'
import {
  beanSlots,
  changed,
  mergeTargetOf,
  toEntryDrafts,
  toMetaUpdate,
  trimBeans,
  withBean,
  withDate,
  withNotes,
  withRecipe,
  withReview,
} from './noteEdits'

/**
 * 수정·삭제 화면 — 저장된 노트의 사실과 시음 기록을 필드 단위로 고친다 (changes/0029 TΔ13b, AC-5·AC-6).
 *
 * **이 화면이 이 델타의 결론이다.** delta.md §1.3이 *"UI는 이 버그를 고치는 게 아니라 이 버그가 존재할 수
 * 있는 구조를 제거한다"*고 적은 그 UI이고, 여기서 로스터리를 고치는 데 필요한 것은 입력 필드 하나뿐이다 —
 * 자연어 인코딩도, 동일성 판정도, 그 판정 키에 수정 대상이 섞이는 함정도 없다(§1.2).
 *
 * 시안은 `design/노트 수정.dc.html`이다(ADR-54: 시안이 디자인 source of truth). 형태를 이식했다 —
 * `EDIT NOTE` 헤더 · **구획 머리 바**(커피 정보 / 날짜) · 잠긴 커피명 블록 · **공식 노트 칩** ·
 * 점선 빈 슬롯 · **모노스페이스 날짜 + 요일 헤더와 접기/펼치기** · 회차 카드의 `레시피`/`평가` 구획 머리와
 * 부제 · `되돌리기` + `저장`. 시안과 갈린 것은 여섯이고 각각 그 자리 주석이 근거를 소유한다:
 * ① 출처 배지 ② 추출 ml 슬롯 ③ 분쇄도·기구 분리 ④ `feedback` 단일 필드 ⑤ 회차/날짜 추가·시음일 삭제 부재
 * ⑥ 저장 버튼의 자리와 노트 삭제 구역.
 *
 * **저장 단위가 섹션별인 것이 계약이다**(사용자 확정 2026-08-01). TΔ4a가 `applyEdit`을 `updateMeta`·
 * `replaceEntry`로 나눈 것을 화면이 그대로 드러낸다 — 커피 정보 [저장] 하나가 `PATCH /api/notes/{id}`
 * 하나이고, 날짜 [저장] 하나가 `PATCH /api/notes/{id}/entries/{date}` 하나다. 버튼을 하나로 합치면
 * 요청이 N개가 되고 **부분 실패**(메타는 저장됐고 7/2는 실패)를 화면이 표현해야 하는데, 나눠 두면 그
 * 상태가 애초에 성립하지 않는다.
 *
 * **이 화면이 딛는 것은 `GET /api/notes/{id}` 하나다**(TΔ13a·TΔ5a). 출처를 그대로 받아 그대로 되싣기
 * 때문에 **고치지 않은 필드는 원래 출처를 유지한다** — 로스터리만 고쳤는데 공식 노트가 `user`로 덮여
 * 이후 검색 보강이 닿지 못하는 값이 조용히 느는 것을 막는 자리다(V-6이 막으려던 방향의 반대편 사고).
 */
interface EditScreenProps {
  noteId: number
  onNavigate: (path: string) => void
}

export function EditScreen({ noteId, onNavigate }: EditScreenProps) {
  const [note, setNote] = useState<NoteDetail | null>(null)
  const [failed, setFailed] = useState(false)
  const [meta, setMeta] = useState<NoteMetaUpdate | null>(null)
  const [entries, setEntries] = useState<EntryDraft[]>([])
  const [collapsed, setCollapsed] = useState<string[]>([])
  const [busy, setBusy] = useState(false)
  const [notice, setNotice] = useState<Notice | null>(null)
  const confirm = useRef<HTMLDialogElement>(null)

  /**
   * 성공 알림만 스스로 사라진다 — **실패는 남는다.**
   *
   * 성공 문구는 *방금 일어난 일*의 확인이라 읽고 나면 값이 없지만, 실패 문구는 **다음 행동의 근거**다
   * (*"입력한 값은 그대로 두었어요"* = 다시 누르면 된다). 3초 뒤 사라지면 자리를 비운 동안 사용자가
   * 저장됐다고 오해할 수 있고, 그것이 이 델타가 없애러 온 실패의 모양이다(§1.1 — *"성공을 보고하면서
   * 틀린 데이터를 쓴 조용한 오작동"*). 실패 알림은 탭하거나 다음 저장이 갈아치울 때만 닫힌다.
   */
  useEffect(() => {
    if (notice === null || notice.tone !== 'ok') {
      return
    }
    const timer = setTimeout(() => setNotice(null), 3200)
    return () => clearTimeout(timer)
  }, [notice])

  useEffect(() => {
    let live = true
    getNoteDetail(noteId)
      .then((loaded) => {
        if (live) {
          adopt(loaded)
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
  }, [noteId])

  /**
   * 서버가 돌려준 노트를 편집 상태의 새 기준선으로 삼는다.
   *
   * 저장 후 폼을 손으로 갱신하지 않고 **응답으로 통째 다시 세우는** 것이 의도다: 날짜 이동은 시음일을
   * 합치고 없애며(D-12) 정규화는 빈 원두·빈 감상을 드롭한다(V-14·V-15). 그 규칙을 화면이 따라 계산하면
   * 서버 규칙이 클라이언트에 이중화되고, 어긋나는 순간 화면이 저장되지 않은 것을 저장된 것처럼 보여준다.
   *
   * **접힘 상태는 유지하지 않는다** — 저장으로 날짜가 합쳐지면 어느 섹션이 접혀 있었는지가 의미를 잃는다.
   */
  function adopt(loaded: NoteDetail) {
    setNote(loaded)
    setMeta(toMetaUpdate(loaded))
    setEntries(toEntryDrafts(loaded))
    setCollapsed([])
  }

  async function saveMeta() {
    if (meta === null || busy) {
      return
    }
    setBusy(true)
    setNotice(null)
    try {
      adopt(await patchNoteMeta(noteId, trimBeans(meta)))
      setNotice({ tone: 'ok', text: '커피 정보를 고쳤어요.' })
    } catch (error) {
      // POLICY: 실패해도 폼을 되돌리지 않는다 — 사용자가 방금 입력한 값이 남아 있어야 다시 누를 수 있다.
      //         캡처 폼의 저장 실패가 같은 답을 낸다(`ChatScreen.save`).
      setNotice({ tone: 'error', text: describe(error, '커피 정보를 고치지 못했어요. 입력한 값은 그대로 두었어요.') })
    } finally {
      setBusy(false)
    }
  }

  async function saveEntry(draft: EntryDraft) {
    if (busy) {
      return
    }
    setBusy(true)
    setNotice(null)
    try {
      const merged = mergeTargetOf(entries, draft)
      adopt(await patchNoteEntry(noteId, draft.targetDate, draft.value))
      setNotice({
        tone: 'ok',
        text:
          merged === null
            ? `${draft.value.date} 기록을 고쳤어요.`
            : `${draft.value.date} 기록에 합쳤어요. 회차가 ${merged.value.cups.length + draft.value.cups.length}개가 됐어요.`,
      })
    } catch (error) {
      setNotice({ tone: 'error', text: describe(error, '기록을 고치지 못했어요. 입력한 값은 그대로 두었어요.') })
    } finally {
      setBusy(false)
    }
  }

  async function remove() {
    confirm.current?.close()
    setBusy(true)
    try {
      await deleteNote(noteId)
      // 지운 노트의 상세로 되돌아갈 수는 없다 — 목록이 유일하게 말이 되는 다음 자리다.
      onNavigate(GALLERY)
    } catch (error) {
      setNotice({ tone: 'error', text: describe(error, '노트를 지우지 못했어요.') })
      setBusy(false)
    }
  }

  if (note === null || meta === null) {
    return (
      <Frame title=" " onBack={() => onNavigate(notePath(noteId))}>
        <div className="detail__notice">{failed ? '노트를 불러오지 못했어요.' : '불러오는 중…'}</div>
      </Frame>
    )
  }

  const baselineEntries = toEntryDrafts(note)

  return (
    <Frame
      title={note.coffee_name.value}
      onBack={() => onNavigate(notePath(noteId))}
      toast={notice === null ? null : <Toast notice={notice} onClose={() => setNotice(null)} />}
    >
      <MetaSection
        note={note}
        meta={meta}
        busy={busy}
        onChange={setMeta}
        onRevert={() => setMeta(toMetaUpdate(note))}
        onSave={() => void saveMeta()}
      />

      {note.entries.length === 0 && <div className="edit__empty">아직 시음 기록이 없어요.</div>}

      {entries.map((draft, index) => (
        <DateSection
          key={draft.targetDate}
          draft={draft}
          merged={mergeTargetOf(entries, draft)}
          dirty={changed(baselineEntries[index]?.value, draft.value)}
          collapsed={collapsed.includes(draft.targetDate)}
          busy={busy}
          onToggle={() =>
            setCollapsed((prev) =>
              prev.includes(draft.targetDate)
                ? prev.filter((date) => date !== draft.targetDate)
                : [...prev, draft.targetDate],
            )
          }
          onChange={(next) => setEntries(entries.map((item, i) => (i === index ? { ...item, value: next } : item)))}
          onRevert={() => setEntries(entries.map((item, i) => (i === index ? baselineEntries[index] : item)))}
          onSave={() => void saveEntry(draft)}
        />
      ))}

      {/*
        시안과 갈린 것 ⑥ — 시안 맨 아래는 `＋ 새 날짜 기록 추가`이고 노트 삭제 구역이 없다. 새 날짜는
        대화로 쌓는 것이 캡처 경로이고(D-1: 생성은 자연어를 유지한다), AC-6이 요구하는 노트 삭제는
        어딘가에 있어야 한다. 그래서 그 자리를 삭제가 대신 쓰되 시안의 점선 박스 어휘는 그대로다.
      */}
      <section className="edit__danger">
        <div className="edit__danger-why">이 커피의 모든 시음 기록·사진·카드가 함께 사라져요. 되돌릴 수 없어요.</div>
        <button type="button" className="edit__danger-button" disabled={busy} onClick={() => confirm.current?.showModal()}>
          이 노트 삭제
        </button>
      </section>

      {/*
        <dialog> + showModal()이다 — ESC·백드롭·포커스 트랩을 브라우저에서 얻는다(TΔ11 후보 시트와 같은 판단).
        확인을 거는 것은 hard delete라서다(AC-6): 지운 뒤에 되돌릴 자리가 없다.
      */}
      <dialog className="sheet" ref={confirm}>
        <div className="sheet__body">
          <div className="sheet__head">
            <h2 className="sheet__title">{note.coffee_name.value}</h2>
          </div>
          <p className="edit__danger-why">
            시음 기록 {note.entries.length}일치와 사진이 모두 지워져요. 되돌릴 수 없어요.
          </p>
          <div className="edit__actions">
            <button type="button" className="edit__revert" onClick={() => confirm.current?.close()}>
              그만두기
            </button>
            <button type="button" className="edit__danger-button" onClick={() => void remove()}>
              지울래요
            </button>
          </div>
        </div>
      </dialog>
    </Frame>
  )
}

/** 상세와 같은 액자·같은 3분할 헤더 — 두 화면이 한 노트의 두 면이라 틀이 갈리면 이동이 화면 전환처럼 읽힌다. */
function Frame({
  title,
  onBack,
  toast,
  children,
}: {
  title: string
  onBack: () => void
  toast?: ReactNode
  children: ReactNode
}) {
  return (
    <div className="shell">
      <div className="panel">
        <header className="edit__header">
          {toast}
          <div className="detail__bar">
            <button type="button" className="detail__back" onClick={onBack}>
              ‹ 상세
            </button>
            <div className="detail__eyebrow">EDIT NOTE</div>
            <div className="detail__bar-pad" />
          </div>
          <h1 className="edit__title">{title}</h1>
        </header>
        <div className="edit__body">{children}</div>
      </div>
    </div>
  )
}

/** 커피 정보 = 저장 단위 하나 — `PATCH /api/notes/{id}` 하나에 대응한다. */
function MetaSection({
  note,
  meta,
  busy,
  onChange,
  onRevert,
  onSave,
}: {
  note: NoteDetail
  meta: NoteMetaUpdate
  busy: boolean
  onChange: (next: NoteMetaUpdate) => void
  onRevert: () => void
  onSave: () => void
}) {
  return (
    <section className="edit__section">
      <div className="edit__section-head">
        <span className="edit__section-title">커피 정보</span>
      </div>

      <div className="edit__section-body">
        {/*
          커피명은 잠겨 있다 — 노트 생성 후 불변이고(V-9) 이름이 다르면 다른 커피다. 계약(`NoteMetaUpdate`)에
          필드 자체가 없어 구조로도 막히지만, 화면이 그 사실을 말해 주지 않으면 사용자는 왜 못 고치는지 모른다.
          시안이 라벨 → 값 → 이유의 세로 스택으로 그렸고 그대로 이식했다.
        */}
        <div className="edit__locked">
          <div className="efield__label">커피명</div>
          <div className="edit__locked-value">{note.coffee_name.value}</div>
          <div className="edit__locked-why">이름이 다르면 다른 커피예요 — 새로 기록해 주세요.</div>
        </div>

        <div className="edit__pair">
          <TextField
            label="로스터리"
            field={meta.roastery}
            onChange={(next) => onChange({ ...meta, roastery: next })}
          />
          <TextField
            label="로스팅"
            field={meta.roast_level}
            onChange={(next) => onChange({ ...meta, roast_level: next })}
          />
        </div>

        <NoteChips meta={meta} onChange={onChange} />

        <div className="edit__group">
          <div className="edit__group-title">원두</div>
          <div className="edit__pair">
            {/* 마지막 줄은 항상 비어 있다 — 원두가 0개로 저장된 노트에 채워 넣을 유일한 경로다(`beanSlots`).
                시안이 점선 테두리 + "입력" placeholder로 그 자리를 그렸다. */}
            {beanSlots(meta).map((bean, index) => (
              <Fragment key={index}>
                <TextField
                  label="원산지 · 품종"
                  field={bean.description.value === '' ? null : bean.description}
                  placeholder="입력"
                  onChange={(next) => onChange(withBean(meta, index, { description: next ?? BLANK_DESCRIPTION }))}
                />
                <TextField
                  label="가공방식"
                  field={bean.process}
                  placeholder="입력"
                  onChange={(next) => onChange(withBean(meta, index, { process: next }))}
                />
              </Fragment>
            ))}
          </div>
        </div>

        {/* 참조 링크는 표시만 한다 — 캡처 폼과 같은 취급이고 FR-21의 수정 범위 목록에도 없다. */}
        {meta.sources.length > 0 && (
          <ul className="edit__sources">
            {meta.sources.map((source) => (
              <li key={source}>
                <a href={source} target="_blank" rel="noreferrer">
                  {source}
                </a>
              </li>
            ))}
          </ul>
        )}

        <Actions dirty={changed(toMetaUpdate(note), meta)} busy={busy} onRevert={onRevert} onSave={onSave} />
      </div>
    </section>
  )
}

/**
 * 공식 노트 — **칩 목록 + ✕ + 점선 입력 칩**(시안).
 *
 * 캡처 폼의 쉼표 구분 한 줄에서 갈린 자리다. 값이 `string[]`이라 칩이 데이터에 더 맞고, 쉼표를 구분자로
 * 쓰면 **값 안의 쉼표와 충돌**한다 — 갤러리 필터가 다중 축을 쉼표로 잇지 않은 것과 같은 이유다(TΔ12).
 *
 * 고치면 출처가 `user`가 된다: 검색이 채운 노트를 손보는 순간 그것은 사용자의 값이고, 그래야 이후 보강이
 * 덮지 못한다(V-6). 목록이 통째로 비면 필드 자체가 없어진다.
 */
function NoteChips({ meta, onChange }: { meta: NoteMetaUpdate; onChange: (next: NoteMetaUpdate) => void }) {
  const [draft, setDraft] = useState('')
  const notes = meta.official_notes?.value ?? []

  function commit() {
    const added = draft.trim()
    if (added !== '' && !notes.includes(added)) {
      onChange(withNotes(meta, [...notes, added]))
    }
    setDraft('')
  }

  return (
    <div className="edit__group">
      <div className="edit__group-head">
        <span className="efield__label">공식 노트</span>
        <span className="edit__hint">한 칸에 하나씩</span>
        {meta.official_notes !== null && meta.official_notes.source !== 'user' && (
          <em className="efield__source">{SOURCE_LABELS[meta.official_notes.source]}</em>
        )}
      </div>
      <div className="edit__chips">
        {notes.map((entry) => (
          <span className="edit__chip" key={entry}>
            {entry}
            <button
              type="button"
              className="edit__chip-x"
              aria-label={`${entry} 지우기`}
              onClick={() => onChange(withNotes(meta, notes.filter((item) => item !== entry)))}
            >
              ✕
            </button>
          </span>
        ))}
        <input
          className="edit__chip-input"
          value={draft}
          placeholder="＋ 노트"
          onChange={(event) => setDraft(event.target.value)}
          // Enter로도 포커스 이동으로도 커밋된다 — 폰에서 키보드를 내리는 것이 곧 blur라 그쪽이 실제 경로다.
          onKeyDown={(event) => {
            if (event.key === 'Enter') {
              event.preventDefault()
              commit()
            }
          }}
          onBlur={commit}
        />
      </div>
    </div>
  )
}

/**
 * 날짜 하나 = 저장 단위 하나 — `PATCH /api/notes/{id}/entries/{date}` 하나에 대응한다.
 *
 * 시안의 날짜 헤더 바를 이식했다: 모노스페이스 날짜 + 요일, 펼치면 짙은 갈색·접으면 밝은 톤. **다만 날짜가
 * 표시가 아니라 입력이다**(시안에는 날짜를 고치는 자리가 없다) — 날짜 이동이 이 화면의 기능이고(V-10·D-12,
 * delta §1.1 실측에서 실사용 수정 5회 중 1회가 날짜 이동이었다) 헤더의 그 값이 곧 고칠 대상이다.
 *
 * 회차를 더하거나 지우지 않는다(시안과 갈린 것 ⑤): 새 회차는 대화로 쌓는 것이 캡처 경로이고(ADR-4·59),
 * 회차 삭제·시음일 삭제는 spec에 없는 동작이다(루트 §3 — `DELETE`는 노트 단위 하나뿐이다).
 */
function DateSection({
  draft,
  merged,
  dirty,
  collapsed,
  busy,
  onToggle,
  onChange,
  onRevert,
  onSave,
}: {
  draft: EntryDraft
  merged: EntryDraft | null
  dirty: boolean
  collapsed: boolean
  busy: boolean
  onToggle: () => void
  onChange: (next: NoteEntryUpdate) => void
  onRevert: () => void
  onSave: () => void
}) {
  const entry = draft.value

  return (
    <section className="edit__section">
      <div className={collapsed ? 'edit__date-head edit__date-head--folded' : 'edit__date-head'}>
        <div className="edit__date-left">
          {collapsed ? (
            <span className="edit__date-text">{formatDate(entry.date)}</span>
          ) : (
            <input
              className="edit__date-input"
              type="date"
              value={entry.date}
              aria-label="시음 날짜"
              onChange={(event) => onChange(withDate(entry, event.target.value))}
            />
          )}
          <span className="edit__weekday">{weekdayOf(entry.date)}</span>
        </div>
        <button type="button" className="edit__fold" onClick={onToggle}>
          {collapsed ? '펼치기 ▾' : '접기 ▴'}
        </button>
      </div>

      {collapsed ? (
        <div className="edit__section-body">
          {entry.cups.map((cup, index) => (
            <div className="edit__summary" key={index}>
              {summarize(cup) || `${index + 1}회차`}
            </div>
          ))}
        </div>
      ) : (
        <div className="edit__section-body">
          {/*
            이동처에 기록이 있으면 **합쳐진다**(D-12) — 경고가 아니라 안내인 것이 이 델타의 개정 지점이다.
            구 V-10은 이동처를 덮어썼는데, 시음일이 하루치 감상 1건이던 시절(changes/0012)의 규칙이라 회차
            배열(ADR-59) 위에서는 그날의 N회차를 통째로 지우는 뜻이 됐다. 캡처 경로가 같은 상황에 이미 회차
            append로 답하고 있고(ADR-4·59), 사용자 의도도 "그날로 옮긴다"이지 "그날을 지운다"가 아니다.
          */}
          {merged !== null && (
            <p className="edit__merge">
              {entry.date}에 이미 기록이 있어요. 그날 기록에 {merged.value.cups.length + 1}회차부터 합쳐집니다.
            </p>
          )}

          {entry.cups.map((cup, cupIndex) => (
            <CupCard
              key={cupIndex}
              cup={cup}
              no={cupIndex + 1}
              onRecipe={(patch) => onChange(withRecipe(entry, cupIndex, patch))}
              onReview={(patch) => onChange(withReview(entry, cupIndex, patch))}
            />
          ))}

          {/* 시안과 갈린 것 ⑥ — 시안은 [저장]을 회차 카드 안에 뒀지만 그 카드가 하나뿐인 그림이다.
              PATCH는 **시음일 단위**라(회차별 저장 API가 없다) 회차마다 버튼을 두면 각 버튼이 그 날짜
              전체를 저장하게 되어 거짓말이 된다. 저장 단위가 곧 이 섹션이므로 버튼도 섹션 발치에 둔다. */}
          <Actions dirty={dirty} busy={busy || entry.date === ''} onRevert={onRevert} onSave={onSave} />
        </div>
      )}
    </section>
  )
}

/**
 * 회차 1개 = 카드 하나 — 머리(회차·방식) · **`레시피` 구획** · **`평가` 구획**(시안).
 *
 * 구획 머리와 부제(*"어떻게 내렸는지"* / *"마셔보니 어땠는지"*)는 시안 그대로다. 레시피·감상이 회차 안에서
 * 1:1로 묶이는 구조(ADR-59)를 화면이 두 구획으로 드러내는 형태이고, **방식별 분기는 없다**(flat 스키마 V-8).
 */
function CupCard({
  cup,
  no,
  onRecipe,
  onReview,
}: {
  cup: NoteDetailCup
  no: number
  onRecipe: (patch: Partial<Recipe>) => void
  onReview: (patch: Partial<NoteDetailReview>) => void
}) {
  const recipe = cup.recipe

  return (
    <div className="ecup">
      <div className="ecup__head">
        <span className="ecup__no">{no}회차</span>
        {/* 시안은 `핸드드립 ▾` 드롭다운 칩인데 `method`는 자유 문자열이다(FR-18) — 열거할 값 집합이 없어
            칩 모양만 살리고 입력으로 둔다(갤러리 원산지 축이 칩 대신 입력인 것과 같은 사정, TΔ12). */}
        <input
          className="ecup__method"
          value={recipe?.method ?? ''}
          placeholder="방식"
          aria-label="추출 방식"
          onChange={(event) => onRecipe({ method: textValue(event.target.value) })}
        />
      </div>

      <div className="ecup__band">
        <span className="ecup__band-title">레시피</span>
        <span className="edit__hint">어떻게 내렸는지</span>
      </div>

      <div className="ecup__stats">
        <NumberInput label="원두 g" value={recipe?.dose_g ?? null} onChange={(next) => onRecipe({ dose_g: next })} />
        <NumberInput label="물 ml" value={recipe?.water_ml ?? null} onChange={(next) => onRecipe({ water_ml: next })} />
        {/* 시안과 갈린 것 ② — 시안 격자에 `추출 ml`(yield_ml) 자리가 없다. 핸드드립 예시만 그려서 생긴
            공백이고 같은 flat 스키마로 에스프레소도 기록된다(그쪽은 추출량이 핵심 수치다). 상세는 값이
            있을 때만 슬롯을 보탰지만(TΔ13a 편차 ②) **폼에서는 그 답이 성립하지 않는다** — 슬롯이 없으면
            없는 값을 넣을 수가 없다. */}
        <NumberInput label="추출 ml" value={recipe?.yield_ml ?? null} onChange={(next) => onRecipe({ yield_ml: next })} />
        <NumberInput label="시간 초" value={recipe?.time_sec ?? null} onChange={(next) => onRecipe({ time_sec: next })} />
        <NumberInput label="물온도 ℃" value={recipe?.temp_c ?? null} onChange={(next) => onRecipe({ temp_c: next })} />
        {/* 시안과 갈린 것 ③ — 시안은 `분쇄도 (그라인더 · 클릭)` 한 칸에 "매버릭 2.0 · 210클릭"을 담는데
            도메인은 `grind`·`machine` 별도 필드다(V-8). 합치면 저장할 때 어느 쪽에 넣을지 결정론이 없다. */}
        <PlainInput label="분쇄도" value={recipe?.grind ?? ''} onChange={(next) => onRecipe({ grind: textValue(next) })} />
        <PlainInput
          label="기구"
          span
          value={recipe?.machine ?? ''}
          onChange={(next) => onRecipe({ machine: textValue(next) })}
        />
      </div>

      <div className="ecup__block">
        <TextArea
          label="푸어링"
          rows={2}
          value={recipe?.pouring ?? ''}
          onChange={(next) => onRecipe({ pouring: textValue(next) })}
        />
        {/* 시안과 갈린 것 ④ — 시안은 `내리면서 본 것`·`다음에 바꿀 것` 두 칸인데 도메인은 `feedback`
            하나다(FR-18: *"그 시도의 관찰·진단·다음 계획"*). 가르는 것은 스키마 변경이라 프롬프트·eval
            코퍼스까지 번진다 — 여기서 할 일이 아니고, 라벨만 시안의 두 뜻을 함께 담는다. */}
        <TextArea
          label="내리면서 본 것 · 다음에 바꿀 것"
          rows={3}
          value={recipe?.feedback ?? ''}
          onChange={(next) => onRecipe({ feedback: textValue(next) })}
        />
      </div>

      <div className="ecup__band">
        <span className="ecup__band-title">평가</span>
        <span className="edit__hint">마셔보니 어땠는지</span>
      </div>

      <div className="ecup__block">
        {/*
          고치는 것은 정규화본(`my_taste`)뿐이다 — 원문(`my_taste_original`)은 계약에 없고, 저장하면
          서버가 정규화본을 양쪽에 담는다(V-11 뒷문장). "말한 그대로"가 편집본으로 수렴하는 것이
          이 화면이 받아들인 대가다(사용자 확정 2026-08-01).
        */}
        <TextArea
          label="감상"
          rows={5}
          value={cup.review?.my_taste ?? ''}
          onChange={(next) => onReview({ my_taste: textValue(next) })}
        />
        <label className="efield">
          <span className="efield__label">평가</span>
          <select
            className="efield__select"
            value={cup.review?.rating ?? ''}
            onChange={(event) =>
              onReview({ rating: event.target.value === '' ? null : (event.target.value as Rating) })
            }
          >
            <option value="">미언급</option>
            {RATINGS.map((rating) => (
              <option key={rating} value={rating}>
                {rating}
              </option>
            ))}
          </select>
        </label>
      </div>
    </div>
  )
}

/** 시안의 `되돌리기` + `저장` — 되돌리기는 로드 시점(마지막 저장 결과)으로 이 섹션만 돌린다. */
function Actions({
  dirty,
  busy,
  onRevert,
  onSave,
}: {
  dirty: boolean
  busy: boolean
  onRevert: () => void
  onSave: () => void
}) {
  return (
    <div className="edit__actions">
      <button type="button" className="edit__revert" disabled={busy || !dirty} onClick={onRevert}>
        되돌리기
      </button>
      <button type="button" className="edit__save" disabled={busy || !dirty} onClick={onSave}>
        저장
      </button>
    </div>
  )
}

/**
 * 출처 표시 텍스트 필드 — 고치면 그 필드의 출처가 `user`가 된다(`formValues`가 소유하는 규칙).
 *
 * **시안과 갈린 것 ①**: 시안에는 출처 표시가 없다. 그래도 두는 것은 수정 폼에서 그것이 *무엇을 고칠지
 * 고르는 근거*이기 때문이고(캡처 폼·상세와 같은 판단, TΔ13a 편차 ①), 시안이 정본인 범위와 부딪히지 않게
 * **값보다 작고 약하게** 붙인다.
 */
function TextField({
  label,
  field,
  placeholder,
  onChange,
}: {
  label: string
  field: Sourced<string> | null
  placeholder?: string
  onChange: (next: Sourced<string> | null) => void
}) {
  return (
    <label className="efield">
      <span className="efield__label">
        {label}
        {field !== null && field.source !== 'user' && (
          <em className="efield__source">{SOURCE_LABELS[field.source]}</em>
        )}
      </span>
      <input
        className={field === null ? 'efield__input efield__input--blank' : 'efield__input'}
        value={field?.value ?? ''}
        placeholder={placeholder}
        onChange={(event) => onChange(userValue(event.target.value))}
      />
    </label>
  )
}

/** 수치 레시피 칸 — 시안의 굵은 14px 값. 출처 개념이 없다(레시피는 사용자 발화 전용, FR-18). */
function NumberInput({
  label,
  value,
  onChange,
}: {
  label: string
  value: number | null
  onChange: (next: number | null) => void
}) {
  return (
    <label className="efield">
      <span className="efield__label">{label}</span>
      <input
        className="efield__input efield__input--number"
        inputMode="decimal"
        value={value ?? ''}
        onChange={(event) => onChange(numberValue(event.target.value))}
      />
    </label>
  )
}

function PlainInput({
  label,
  value,
  span,
  onChange,
}: {
  label: string
  value: string
  span?: boolean
  onChange: (next: string) => void
}) {
  return (
    <label className={span ? 'efield efield--span' : 'efield'}>
      <span className="efield__label">{label}</span>
      <input className="efield__input" value={value} onChange={(event) => onChange(event.target.value)} />
    </label>
  )
}

function TextArea({
  label,
  rows,
  value,
  onChange,
}: {
  label: string
  rows: number
  value: string
  onChange: (next: string) => void
}) {
  return (
    <label className="efield">
      <span className="efield__label">{label}</span>
      <textarea className="efield__input" rows={rows} value={value} onChange={(event) => onChange(event.target.value)} />
    </label>
  )
}

/** 접힌 날짜의 한 줄 요약(시안) — `핸드드립 · 15g / 240ml · 92℃ · 매버릭 2.0 210클릭`. */
function summarize(cup: NoteDetailCup): string {
  const recipe = cup.recipe
  if (recipe === null) {
    return cup.review?.my_taste ?? ''
  }
  const amounts = [unit(recipe.dose_g, 'g'), unit(recipe.water_ml ?? recipe.yield_ml, 'ml')].filter((v) => v !== null)
  const gear = [recipe.machine, recipe.grind].filter((v) => v !== null).join(' ')
  return [recipe.method, amounts.join(' / ') || null, unit(recipe.temp_c, '℃'), gear || null]
    .filter((part) => part !== null && part !== '')
    .join(' · ')
}

function unit(value: number | null, suffix: string): string | null {
  return value === null ? null : `${value}${suffix}`
}

/** `2026-06-28` → `2026. 06. 28`(시안의 모노스페이스 표기). 형식이 아니면 받은 값을 그대로 쓴다. */
function formatDate(date: string): string {
  const matched = DATE_PATTERN.exec(date)
  return matched === null ? date : `${matched[1]}. ${matched[2]}. ${matched[3]}`
}

/** 시안의 요일 표기 — 로컬 시간대 해석이 하루를 밀지 않게 숫자로 만든다(`new Date('2026-06-28')`은 UTC다). */
function weekdayOf(date: string): string {
  const matched = DATE_PATTERN.exec(date)
  if (matched === null) {
    return ''
  }
  const day = new Date(Number(matched[1]), Number(matched[2]) - 1, Number(matched[3])).getDay()
  return `${WEEKDAYS[day]}요일`
}

const DATE_PATTERN = /^(\d{4})-(\d{2})-(\d{2})$/
const WEEKDAYS = ['일', '월', '화', '수', '목', '금', '토']

const BLANK_DESCRIPTION: Sourced<string> = { value: '', source: 'user' }

/** 저장 결과 알림 — 톤이 수명을 정한다(성공은 스스로 사라지고 실패는 남는다). */
interface Notice {
  tone: 'ok' | 'error'
  text: string
}

/**
 * 저장 결과 토스트 — **패널 위에 떠서 위에서 내려왔다 사라진다**(2026-08-01 사용자 지적).
 *
 * 구 판본은 이 문구를 `커피 정보` 띠 **위 흐름 안에** 끼웠는데 둘이 틀어졌다: ① 알림이 뜨고 지는 순간마다
 * 아래 전부가 ~52px씩 밀려 **읽던 자리가 움직였다** ② 이 화면의 문법은 패널 폭을 꽉 채우는 **가로 띠**인데
 * (`.edit__section`) 좌우 여백을 가진 상자가 그 사이에 끼어 구획이 깨져 보였다.
 *
 * **흐름 밖이 옳은 자리다** — 저장 결과는 화면의 일부가 아니라 *방금 한 일에 대한 답*이라 문서를 밀 이유가
 * 없다. 그래서 `position: absolute`로 패널 최상단에 앉히고 스크롤과 무관하게 같은 자리에 뜬다.
 *
 * 탭하면 닫힌다 — 실패 알림에는 그것이 유일한 해제 수단이고(자동으로 사라지지 않는다), 성공 알림에는
 * 3.2초를 기다리지 않는 지름길이다. `role="status"`라 스크린리더가 낭독하되 포커스를 뺏지 않는다.
 */
function Toast({ notice, onClose }: { notice: Notice; onClose: () => void }) {
  return (
    <div className={`edit__toast edit__toast--${notice.tone}`} role="status">
      <button type="button" className="edit__toast-body" onClick={onClose} aria-label="알림 닫기">
        {notice.text}
      </button>
    </div>
  )
}

function describe(error: unknown, fallback: string): string {
  return error instanceof Error && error.message !== '' ? `${fallback} (${error.message})` : fallback
}
