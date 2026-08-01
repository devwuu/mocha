import type { ReactNode } from 'react'
import type { Draft, Rating, Sourced } from '../api/contract'
import { RATINGS } from '../api/contract'
import { numberValue, SOURCE_LABELS, textValue, userList, userValue } from '../formValues'
import { MatchBadge } from './MatchBadge'
import { patchBean, patchNote, patchRecipe, patchTasting } from './draftEdits'

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

  return (
    <section className="draft" aria-label="작성 중인 노트">
      {/* 매칭 배지 — 탭하면 후보 시트가 열리고 판정을 양방향으로 바꿀 수 있다(TΔ11). */}
      <MatchBadge draft={draft} busy={busy} onChange={onChange} />

      <div className="draft__grid">
        <SourcedText
          label="커피명"
          field={note.coffee_name}
          onChange={(next) => onChange(patchNote(draft, { coffee_name: next }))}
        />
        <SourcedText
          label="로스터리"
          field={note.roastery}
          onChange={(next) => onChange(patchNote(draft, { roastery: next }))}
        />
        <SourcedText
          label="로스팅"
          field={note.roast_level}
          onChange={(next) => onChange(patchNote(draft, { roast_level: next }))}
        />
        <Field label="공식 노트" source={note.official_notes?.source}>
          <input
            value={note.official_notes?.value.join(', ') ?? ''}
            placeholder="쉼표로 구분"
            onChange={(event) => onChange(patchNote(draft, { official_notes: userList(event.target.value) }))}
          />
        </Field>
      </div>

      {note.beans.length > 0 && (
        <div className="draft__section">
          <h3>원두</h3>
          {note.beans.map((bean, beanIndex) => (
            <div className="draft__grid" key={beanIndex}>
              <SourcedText
                label="원산지·품종"
                field={bean.description}
                onChange={(next) =>
                  // description이 비면 서버가 그 원두를 통째로 드롭한다(V-14) — 폼에서 지우는 것이
                  // 곧 "이 원두는 아니었다"이므로 별도 삭제 버튼을 두지 않는다.
                  onChange(patchBean(draft, beanIndex, { description: next ?? { value: '', source: 'user' } }))
                }
              />
              <SourcedText
                label="가공방식"
                field={bean.process}
                onChange={(next) => onChange(patchBean(draft, beanIndex, { process: next }))}
              />
            </div>
          ))}
        </div>
      )}

      {note.entries.map((entry, entryIndex) => (
        <div className="draft__section" key={entry.date}>
          <h3>{entry.date}</h3>
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
        <button type="button" className="button button--primary" onClick={onSave} disabled={busy}>
          저장
        </button>
      </div>
    </section>
  )
}


function SourcedText({
  label,
  field,
  onChange,
}: {
  label: string
  field: Sourced<string> | null
  onChange: (next: Sourced<string> | null) => void
}) {
  return (
    <Field label={label} source={field?.source}>
      <input value={field?.value ?? ''} onChange={(event) => onChange(userValue(event.target.value))} />
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
function Field({ label, source, children }: { label: string; source?: string; children: ReactNode }) {
  return (
    <label className="field">
      <span className="field__label">
        {label}
        {source && source !== 'user' && <em className="field__source">{SOURCE_LABELS[source] ?? source}</em>}
      </span>
      {children}
    </label>
  )
}
