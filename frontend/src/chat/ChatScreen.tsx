import { useEffect, useRef, useState } from 'react'
import type { Draft } from '../api'
import { postAgentCancel, postAgentTurn, postNoteCommit } from '../api'
import { DraftForm } from './DraftForm'

/**
 * 캡처 화면 — 발화 → 에이전트 응답 → 미리보기 폼 → [저장]/[취소] (changes/0029 TΔ10, OQ-5 채팅형).
 *
 * 시안은 `design/채팅 - 말풍선.dc.html`이다(ADR-54: 시안이 디자인 source of truth).
 *
 * **작성 중인 내용은 전부 이 컴포넌트의 상태다.** 서버에는 pending이 없다(OQ-1 ㉡, TΔ4) — 새로고침하면
 * 폼도 대화도 사라진다. 그것이 이 델타가 고른 트레이드오프이고, 대신 "폼에서 고친 값이 서버 재구성으로
 * 되돌아가는" 실패(delta.md §1.2)가 구조적으로 성립하지 않는다.
 *
 * 추가 발화는 **현재 폼 전체를 동봉**해 나간다(TΔ2 계약) — 에이전트의 과업이 "이 draft 위에 이 발화를
 * 반영하라"이기 때문이다. 제안이 없던 턴은 응답의 draft가 null이고, 그때 폼은 건드리지 않는다.
 */
export function ChatScreen() {
  const [messages, setMessages] = useState<Message[]>([GREETING])
  const [draft, setDraft] = useState<Draft | null>(null)
  const [busy, setBusy] = useState(false)
  const [input, setInput] = useState('')
  const tail = useRef<HTMLDivElement>(null)

  // 새 말풍선·폼 변화가 접히지 않게 항상 끝으로 붙인다.
  useEffect(() => {
    tail.current?.scrollIntoView({ block: 'end' })
  }, [messages, draft, busy])

  async function send() {
    const utterance = input.trim()
    if (utterance === '' || busy) {
      return
    }
    setInput('')
    setMessages((prev) => [...prev, { role: 'user', text: utterance }])
    setBusy(true)
    try {
      const response = await postAgentTurn({ utterance, draft })
      setMessages((prev) => [...prev, { role: 'agent', text: response.reply }])
      // 제안 없는 턴(잡담·조회·검증 거부)은 draft가 null로 온다 — 폼을 지우지 않는다.
      if (response.draft !== null) {
        setDraft(response.draft)
      }
    } catch (error) {
      // POLICY: 턴 실패는 조용히 삼키지 않는다 — 노트 무변화 + 재요청 안내가 서버 규약이고(ADR-48),
      //         화면도 같은 자리에 실패를 남긴다. 사용자가 방금 한 말은 말풍선에 그대로 있다.
      setMessages((prev) => [...prev, { role: 'system', text: describe(error, '잘 못 들었어요. 다시 한 번 말해 주세요.') }])
    } finally {
      setBusy(false)
    }
  }

  async function save() {
    if (draft === null || busy) {
      return
    }
    const merged = draft.match.type === 'existing'
    setBusy(true)
    try {
      const saved = await postNoteCommit(draft)
      setDraft(null)
      setMessages((prev) => [...prev, { role: 'system', text: savedNotice(saved.note_id, merged) }])
    } catch (error) {
      setMessages((prev) => [...prev, { role: 'system', text: describe(error, '저장하지 못했어요. 폼은 그대로 두었어요.') }])
    } finally {
      setBusy(false)
    }
  }

  async function cancel() {
    // 폼은 클라이언트 상태라 버리는 것으로 끝나지만 서버 대화 문맥의 접힘은 통지가 필요하다(TΔ6b) —
    // 알리지 않으면 버린 작업의 문맥이 TTL까지 남아 다음 커피의 첫 발화에 섞인다.
    // 폼은 응답을 기다리지 않고 즉시 버린다: 사용자가 [취소]를 누른 순간 그것이 의도이고, 통지 실패가
    // 취소 자체를 되돌릴 이유는 없다.
    setDraft(null)
    setMessages((prev) => [...prev, { role: 'system', text: '작성 중이던 노트를 지웠어요.' }])
    try {
      await postAgentCancel()
    } catch (error) {
      // 사용자가 할 수 있는 일은 없지만 조용히 삼키지 않는다 — 다음 대화가 이상하면 이 줄이 단서다.
      setMessages((prev) => [...prev, { role: 'system', text: describe(error, '이전 대화 내용은 정리하지 못했어요.') }])
    }
  }

  return (
    <div className="shell">
      <div className="panel">
        <header className="header">
          <img className="header__mascot" src="/mascot-face.png" alt="" />
          <h1 className="header__title">모카와 대화</h1>
        </header>

        <div className="stream">
          {messages.map((message, index) => (
            <Bubble key={index} message={message} />
          ))}

          {draft !== null && (
            <div className="row row--agent">
              <img className="avatar" src="/mascot-face.png" alt="" />
              <DraftForm draft={draft} busy={busy} onChange={setDraft} onSave={save} onCancel={cancel} />
            </div>
          )}

          {busy && (
            <div className="row row--agent">
              <img className="avatar" src="/mascot-face.png" alt="" />
              <div className="bubble bubble--agent typing" aria-label="모카가 생각 중">
                <i />
                <i />
                <i />
              </div>
            </div>
          )}
          <div ref={tail} />
        </div>

        <form
          className="composer"
          onSubmit={(event) => {
            event.preventDefault()
            void send()
          }}
        >
          {/* 사진 첨부는 업로드 → EXIF 제거 → 즉시 OCR 경로가 서 있어야 동작한다(TΔ8a). 자리만 둔다. */}
          <button type="button" className="composer__attach" aria-label="사진 첨부" disabled>
            ＋
          </button>
          <input
            className="composer__input"
            value={input}
            placeholder="메시지 쓰기…"
            onChange={(event) => setInput(event.target.value)}
          />
          <button type="submit" className="composer__send" aria-label="보내기" disabled={busy || input.trim() === ''}>
            ↑
          </button>
        </form>
      </div>
    </div>
  )
}

interface Message {
  role: 'user' | 'agent' | 'system'
  text: string
}

const GREETING: Message = { role: 'agent', text: '오늘의 한 잔, 어땠어요? ☕' }

function Bubble({ message }: { message: Message }) {
  if (message.role === 'system') {
    return <div className="notice">{message.text}</div>
  }
  if (message.role === 'user') {
    return (
      <div className="row row--user">
        <div className="bubble bubble--user">{message.text}</div>
      </div>
    )
  }
  return (
    <div className="row row--agent">
      <img className="avatar" src="/mascot-face.png" alt="" />
      <div className="bubble bubble--agent">{message.text}</div>
    </div>
  )
}

/**
 * 저장 완료 문구 — 신규/병합으로 갈린다 (TΔ6b, 사용자 확정 2026-08-01).
 *
 * 기존 노트에 병합하면 폼에서 고친 커피 사실(로스터리·원두·로스팅·공식 노트)은 저장되지 않는다 —
 * "재기록은 그날의 엔트리를 쌓는 일이지 커피의 사실을 다시 쓰는 일이 아니다"(ADR-4). 그것을 알리지 않으면
 * 사용자가 고친 값이 화면에 남은 채 조용히 무시되고, 그 형태가 이 델타가 없애려는 실패와 닮는다
 * (delta.md §1.2 — 다만 이번 것은 버그가 아니라 정책이다). 사실을 고치는 경로는 상세 수정 화면(TΔ13)이고,
 * 필드 잠금·수정 안내는 그 화면이 설 때 판단한다.
 */
function savedNotice(noteId: number, merged: boolean): string {
  return merged
    ? `기존 노트 #${noteId}에 오늘 기록을 더했어요. 커피 정보는 기존 노트의 값을 그대로 뒀어요.`
    : `저장했어요. (노트 #${noteId})`
}

function describe(error: unknown, fallback: string): string {
  return error instanceof Error && error.message !== '' ? `${fallback} (${error.message})` : fallback
}
