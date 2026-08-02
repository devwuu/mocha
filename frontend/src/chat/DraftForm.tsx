import type { ReactNode } from 'react'
import type { Draft, Rating, Sourced } from '../api/contract'
import { RATINGS } from '../api/contract'
import { numberValue, SOURCE_LABELS, textValue, userList, userValue } from '../formValues'
import { MatchBadge } from './MatchBadge'
import { patchBean, patchEntry, patchNote, patchRecipe, patchTasting } from './draftEdits'

/**
 * 미리보기 폼 — 에이전트가 제안한 draft를 사용자가 확인하고 고치는 자리 (changes/0029 TΔ10, AC-1).
 *
 * 대화 흐름 안에 카드로 앉는다(design/채팅 - 말풍선.dc.html의 제안 카드 자리). **폼은 클라이언트 상태다**
 * — 서버는 작성 중인 내용을 기억하지 않고(OQ-1 ㉡, TΔ4에서 pending 폐기), 추가 발화 때 이 폼 전체가
 * draft로 동봉돼 나간다(TΔ2 계약).
 *
 * 출처 표시는 사용자가 고칠 대상을 고르는 근거다 — `photo`·`search`로 채워진 값은 에이전트가 넣은 것이고,
 * 고치는 순간 `user`가 되어 이후 검색 보강이 덮지 못한다(V-6, `draftEdits.ts`).
 */
interface DraftFormProps {
  draft: Draft
  busy: boolean
  onChange: (draft: Draft) => void
  onSave: () => void
  onCancel: () => void
}

export function DraftForm({ draft, busy, onChange, onSave, onCancel }: DraftFormProps) {
  const { note } = draft
  /*
   * 수정 모드에서는 **노트 레벨 전체를 잠그고 회차만 연다** (TΔ28a, 사용자 확정 2026-08-02, AC-14).
   *
   * 기준은 «이 값이 다른 회차에도 걸리는가»다. 커피명·로스터리·원두·로스팅·공식 노트는 **노트 1건에
   * 하나씩 있고 그 노트의 모든 회차가 함께 딛는 값**이라, 한 회차를 고치는 자리에서 바꾸면 손대지 않은
   * 다른 날 감상까지 뜻이 달라진다. 회차(레시피·감상)만 그 날 그 잔의 것이다.
   *
   * 정체성 둘(커피명·로스터리)은 그중에서도 더 세다 — 바뀌면 아예 다른 커피가 된다(V-9, 싱글 오리진은
   * 커피명이 산지·농장·품종에서 와 로스터리가 다르면 같은 이름의 다른 커피다). 그건 «새 노트»에서 할
   * 일이지 수정 폼으로 흘러들어올 일이 아니다.
   *
   * **목록에서 들어간 수정 화면(TΔ13b)은 이 값들을 연다.** 그 화면은 노트 전체를 펼쳐 다른 기록들이
   * 함께 보이므로 무엇에 영향을 주는지가 눈에 있지만, **채팅 안의 폼은 구조상 그것을 보여줄 수 없다** —
   * 대화 흐름에 앉은 카드 하나라 회차 1건만 담는다. 같은 규칙이 아니라 **같은 이유의 다른 답**이다.
   *
   * 그래서 이 폼이 저장하는 것은 **엔트리 하나**이고, 메타 PATCH를 보낼 이유가 없다(TΔ28b가 받는다).
   */
  const locked = draft.match.type === 'edit'
  /*
   * 수정 대상의 «원래 날짜» — 수정 모드에서만 있고, 날짜 입력이 열리는 신호이자 이동 판정의 기준이다
   * (TΔ28c). 폼의 날짜가 이 값과 달라지면 그것이 곧 날짜 이동 요청이고, 서버는 경로의 이 날짜를 대상으로
   * 찾아 본문의 날짜로 옮긴다(`note-update.contract.json`의 `date_move`).
   */
  const target = draft.match.type === 'edit' ? draft.match.date : null
  // 날짜가 빈 폼은 보내지 않는다 — 엔트리의 유일 키라(V-3) 서버가 400으로 답할 요청이고, 그 실패를
  // 「저장하지 못했어」로 옮기면 사용자는 무엇이 문제인지 모른 채 폼을 들여다보게 된다.
  const incomplete = note.entries.some((entry) => entry.date === '')

  return (
    <section className="draft" aria-label={locked ? '고치는 중인 기록' : '작성 중인 노트'}>
      {/* 매칭 배지 — 탭하면 후보 시트가 열리고 판정을 양방향으로 바꿀 수 있다(TΔ11).
          수정 모드에서는 시트가 «어느 커피 → 어느 날 기록»의 두 걸음이 된다(TΔ28a). */}
      <MatchBadge draft={draft} busy={busy} onChange={onChange} />

      {/* 잠긴 칸이 "왜"까지 말하지는 못한다 — 고칠 자리가 어디인지는 알려 줘야 막다른 길이 되지 않는다. */}
      {locked && <p className="draft__locked-note">커피 정보는 이 기록에만 딸린 값이 아니라서 노트 화면에서 고쳐.</p>}

      <div className="draft__grid">
        <SourcedText
          label="커피명"
          field={note.coffee_name}
          locked={locked}
          onChange={(next) => onChange(patchNote(draft, { coffee_name: next }))}
        />
        <SourcedText
          label="로스터리"
          field={note.roastery}
          locked={locked}
          onChange={(next) => onChange(patchNote(draft, { roastery: next }))}
        />
        <SourcedText
          label="로스팅"
          field={note.roast_level}
          locked={locked}
          onChange={(next) => onChange(patchNote(draft, { roast_level: next }))}
        />
        {/* 잠긴 채로 비어 있는 칸은 지우고 보여준다 — 고칠 수도 없고 값도 없으면 "여기 뭘 넣나"만 묻게 된다. */}
        {!(locked && note.official_notes === null) && (
          <Field label="공식 노트" source={note.official_notes?.source} locked={locked}>
            <input
              className={locked ? 'locked' : undefined}
              value={note.official_notes?.value.join(', ') ?? ''}
              placeholder="쉼표로 구분"
              readOnly={locked}
              onChange={(event) => onChange(patchNote(draft, { official_notes: userList(event.target.value) }))}
            />
          </Field>
        )}
      </div>

      {note.beans.length > 0 && (
        <div className="draft__section">
          <h3>원두</h3>
          {note.beans.map((bean, beanIndex) => (
            <div className="draft__grid" key={beanIndex}>
              <SourcedText
                label="원산지·품종"
                field={bean.description}
                locked={locked}
                onChange={(next) =>
                  // description이 비면 서버가 그 원두를 통째로 드롭한다(V-14) — 폼에서 지우는 것이
                  // 곧 "이 원두는 아니었다"이므로 별도 삭제 버튼을 두지 않는다.
                  onChange(patchBean(draft, beanIndex, { description: next ?? { value: '', source: 'user' } }))
                }
              />
              <SourcedText
                label="가공방식"
                field={bean.process}
                locked={locked}
                onChange={(next) => onChange(patchBean(draft, beanIndex, { process: next }))}
              />
            </div>
          ))}
        </div>
      )}

      {note.entries.map((entry, entryIndex) => (
        // key가 날짜가 아니라 인덱스인 것은 날짜가 편집 가능해졌기 때문이다(TΔ28c) — 날짜를 키로 두면
        // 값이 바뀔 때마다 섹션이 통째로 다시 마운트돼 입력 중 포커스가 날아간다. 폼 안에서 엔트리가
        // 재정렬되거나 늘고 주는 일이 없어 인덱스가 안정적인 키다.
        <div className="draft__section" key={entryIndex}>
          {/*
            **날짜는 수정 모드에서만 열린다**(TΔ28c, 사용자 확정 2026-08-02). 서버 의미론이 이미 있는
            자리라서다: PATCH는 «경로=대상, 본문=결과»로 날짜 이동을 규정하고(D-12) 수정 폼은 엔트리를
            1건만 담아 **같은 날짜가 둘 생길 자리가 없다**. 캡처 모드에서 열면 다중 날짜 분해(ADR-61)로
            여러 엔트리를 든 draft에서 날짜가 겹칠 수 있고, 그때 서버는 병합하지 않고 UNIQUE로 깨진다(V-3).
          */}
          {target === null ? (
            <h3>{entry.date}</h3>
          ) : (
            <div className="draft__date">
              <input
                className="draft__date-input"
                type="date"
                value={entry.date}
                aria-label="시음 날짜"
                onChange={(event) => onChange(patchEntry(draft, entryIndex, { date: event.target.value }))}
              />
              {/*
                이동은 «알리기만» 한다(D-12 — 합병에는 잃는 것이 없어 경고가 아니라 안내다). 다만 이
                화면은 **이동처에 기록이 있는지 모른다**: 수정 화면(TΔ13b)은 노트 전문을 들고 있어 저장
                전에 충돌을 판정하지만(`mergeTargetOf`) 채팅 폼은 기록 1건만 담는다. 그래서 아는 것까지만
                말하고, 실제로 합쳐졌는지는 저장 응답이 답한다(`ChatScreen`의 저장 안내).
              */}
              {entry.date !== '' && entry.date !== target && (
                <p className="draft__date-move">
                  {target} 기록을 이 날짜로 옮겨. 그날 이미 기록이 있으면 뒤 회차로 합쳐져.
                </p>
              )}
            </div>
          )}
          {entry.brews.map((brew, brewIndex) => (
            <div className="draft__brew" key={brewIndex}>
              <div className="draft__brew-no">{brewIndex + 1}회차</div>
              <div className="draft__grid">
                <Field label="방식">
                  <input
                    value={brew.recipe?.method ?? ''}
                    onChange={(event) =>
                      onChange(patchRecipe(draft, entryIndex, brewIndex, { method: textValue(event.target.value) }))
                    }
                  />
                </Field>
                <Field label="분쇄도">
                  <input
                    value={brew.recipe?.grind ?? ''}
                    onChange={(event) =>
                      onChange(patchRecipe(draft, entryIndex, brewIndex, { grind: textValue(event.target.value) }))
                    }
                  />
                </Field>
                <NumberField
                  label="원두 g"
                  value={brew.recipe?.dose_g ?? null}
                  onChange={(next) => onChange(patchRecipe(draft, entryIndex, brewIndex, { dose_g: next }))}
                />
                <NumberField
                  label="물 ml"
                  value={brew.recipe?.water_ml ?? null}
                  onChange={(next) => onChange(patchRecipe(draft, entryIndex, brewIndex, { water_ml: next }))}
                />
                <NumberField
                  label="추출 ml"
                  value={brew.recipe?.yield_ml ?? null}
                  onChange={(next) => onChange(patchRecipe(draft, entryIndex, brewIndex, { yield_ml: next }))}
                />
                <NumberField
                  label="시간 초"
                  value={brew.recipe?.time_sec ?? null}
                  onChange={(next) => onChange(patchRecipe(draft, entryIndex, brewIndex, { time_sec: next }))}
                />
                <NumberField
                  label="온도 ℃"
                  value={brew.recipe?.temp_c ?? null}
                  onChange={(next) => onChange(patchRecipe(draft, entryIndex, brewIndex, { temp_c: next }))}
                />
                <Field label="기구">
                  <input
                    value={brew.recipe?.machine ?? ''}
                    onChange={(event) =>
                      onChange(patchRecipe(draft, entryIndex, brewIndex, { machine: textValue(event.target.value) }))
                    }
                  />
                </Field>
              </div>
              <Field label="푸어링">
                <textarea
                  rows={2}
                  value={brew.recipe?.pouring ?? ''}
                  onChange={(event) =>
                    onChange(patchRecipe(draft, entryIndex, brewIndex, { pouring: textValue(event.target.value) }))
                  }
                />
              </Field>
              <Field label="관찰·다음 계획">
                <textarea
                  rows={2}
                  value={brew.recipe?.feedback ?? ''}
                  onChange={(event) =>
                    onChange(patchRecipe(draft, entryIndex, brewIndex, { feedback: textValue(event.target.value) }))
                  }
                />
              </Field>
              <Field label="감상">
                <textarea
                  rows={2}
                  value={brew.tasting?.my_taste ?? ''}
                  onChange={(event) =>
                    onChange(patchTasting(draft, entryIndex, brewIndex, { my_taste: textValue(event.target.value) }))
                  }
                />
              </Field>
              <Field label="평가">
                <select
                  value={brew.tasting?.rating ?? ''}
                  onChange={(event) =>
                    onChange(
                      patchTasting(draft, entryIndex, brewIndex, {
                        rating: event.target.value === '' ? null : (event.target.value as Rating),
                      }),
                    )
                  }
                >
                  <option value="">미언급</option>
                  {RATINGS.map((rating) => (
                    <option key={rating} value={rating}>
                      {rating}
                    </option>
                  ))}
                </select>
              </Field>
            </div>
          ))}
        </div>
      ))}

      {note.sources.length > 0 && (
        <ul className="draft__sources">
          {note.sources.map((source) => (
            <li key={source}>
              <a href={source} target="_blank" rel="noreferrer">
                {source}
              </a>
            </li>
          ))}
        </ul>
      )}

      <div className="draft__actions">
        <button type="button" className="button button--ghost" onClick={onCancel} disabled={busy}>
          취소
        </button>
        <button type="button" className="button button--primary" onClick={onSave} disabled={busy || incomplete}>
          저장
        </button>
      </div>
    </section>
  )
}


/**
 * 출처 표시 텍스트 필드.
 *
 * **잠금은 `readOnly`이지 `disabled`가 아니다** — 잠긴 값도 읽히고 복사되고 스크린리더가 읽어야 한다.
 * 고칠 수 없다는 것과 없는 것은 다르고, 수정 모드에서 커피명·로스터리는 *"무엇을 고치는 중인가"*를 말하는
 * 가장 중요한 두 값이다.
 *
 * **잠긴 채로 비어 있으면 자리를 비운다**: 고칠 수도 없고 값도 없는 칸은 *"여기 뭘 넣나"*만 묻게 하고
 * 답이 아니오다. 저장된 노트의 커피명은 non-null이라(V-9) 실제로 사라지는 것은 값이 안 잡힌 필드뿐이다.
 */
function SourcedText({
  label,
  field,
  locked = false,
  onChange,
}: {
  label: string
  field: Sourced<string> | null
  locked?: boolean
  onChange: (next: Sourced<string> | null) => void
}) {
  if (locked && field === null) {
    return null
  }
  return (
    <Field label={label} source={field?.source} locked={locked}>
      <input
        className={locked ? 'locked' : undefined}
        value={field?.value ?? ''}
        readOnly={locked}
        onChange={(event) => onChange(userValue(event.target.value))}
      />
    </Field>
  )
}

function NumberField({
  label,
  value,
  onChange,
}: {
  label: string
  value: number | null
  onChange: (next: number | null) => void
}) {
  return (
    <Field label={label}>
      <input inputMode="decimal" value={value ?? ''} onChange={(event) => onChange(numberValue(event.target.value))} />
    </Field>
  )
}

/**
 * 출처는 `user`가 아닐 때만 보여준다 — 어휘는 `formValues`가 소유하고 형태는 이 화면 시안이 정한다.
 *
 * 수정 폼(TΔ13b)이 같은 값을 다른 크기·다른 배치로 보여주는 것은 **시안이 다르기 때문**이다(ADR-54) —
 * 규칙은 공유하고 컴포넌트는 공유하지 않는 것이 이 저장소가 두 시안을 다루는 방식이다.
 */
function Field({
  label,
  source,
  locked = false,
  children,
}: {
  label: string
  source?: string
  locked?: boolean
  children: ReactNode
}) {
  return (
    <label className="field">
      <span className="field__label">
        {label}
        {source && source !== 'user' && <em className="field__source">{SOURCE_LABELS[source] ?? source}</em>}
        {/* 잠금은 형태(점선·바탕)로도 읽히지만 그것만으로는 "왜"가 없다 — 라벨에 한 낱말을 둔다. */}
        {locked && <em className="field__lock">잠김</em>}
      </span>
      {children}
    </label>
  )
}
