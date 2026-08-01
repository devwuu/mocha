import { useEffect, useRef, useState } from 'react'
import type { Draft } from '../api'
import { postAgentTurn, postNoteCommit } from '../api'
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
    setBusy(true)
    try {
      const saved = await postNoteCommit(draft)
      setDraft(null)
      setMessages((prev) => [...prev, { role: 'system', text: `저장했어요. (노트 #${saved.note_id})` }])
    } catch (error) {
      setMessages((prev) => [...prev, { role: 'system', text: describe(error, '저장하지 못했어요. 폼은 그대로 두었어요.') }])
    } finally {
      setBusy(false)
    }
  }

  function cancel() {
    // 폼은 클라이언트 상태라 버리는 것으로 끝난다. 서버 트랜스크립트 접힘(FoldTrigger.CANCEL_COMMIT)의
    // 배선은 TΔ4에서 버튼과 함께 끊긴 채이고 확정 API를 세우는 TΔ6이 다시 잇는다 — 그때 이 자리에서
    // 취소도 서버에 알린다.
    setDraft(null)
    setMessages((prev) => [...prev, { role: 'system', text: '작성 중이던 노트를 지웠어요.' }])
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

function describe(error: unknown, fallback: string): string {
  return error instanceof Error && error.message !== '' ? `${fallback} (${error.message})` : fallback
}
