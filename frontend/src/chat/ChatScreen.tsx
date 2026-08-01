import { useEffect, useRef, useState } from 'react'
import type { KeyboardEvent } from 'react'
import type { Draft } from '../api'
import { postAgentCancel, postAgentTurn, postNoteCommit, postPhotos } from '../api'
import { editPath, GALLERY } from '../routes'
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
 *
 * **사진은 ＋로 첨부해 발화와 한 전송으로 묶인다**(TΔ8a, D-11). 고른 순간 업로드되고(`POST /api/photos`)
 * 화면이 들고 있는 것은 스테이징 파일명뿐이며, 읽기(OCR)·검색 보강·필드 채움은 전부 서버 턴 안에서
 * 모델이 한다 — **이 파일에 병합 코드가 0줄인 것이 D-11의 결정이다.**
 */
interface ChatScreenProps {
  onNavigate: (path: string) => void
}

export function ChatScreen({ onNavigate }: ChatScreenProps) {
  const [messages, setMessages] = useState<Message[]>([GREETING])
  const [draft, setDraft] = useState<Draft | null>(null)
  const [busy, setBusy] = useState(false)
  const [input, setInput] = useState('')
  // 업로드가 끝나 스테이징에 서 있는 사진의 파일명 — 다음 전송에 실린다.
  const [photos, setPhotos] = useState<string[]>([])
  const [uploading, setUploading] = useState(false)
  const tail = useRef<HTMLDivElement>(null)
  const picker = useRef<HTMLInputElement>(null)
  const composer = useRef<HTMLTextAreaElement>(null)

  // 새 말풍선·폼 변화가 접히지 않게 항상 끝으로 붙인다.
  useEffect(() => {
    tail.current?.scrollIntoView({ block: 'end' })
  }, [messages, draft, busy, photos])

  /*
   * 입력창 높이를 내용에 맞춘다 — 상한(`max-height`)은 CSS가 소유하고 여기서는 `scrollHeight`만 옮긴다.
   * `auto`로 한 번 되돌리는 것이 요점이다: 그러지 않으면 `scrollHeight`가 이미 늘어난 높이를 포함해
   * 줄을 지워도 줄지 않는다. 전송으로 값이 비면 같은 경로로 한 줄 높이로 돌아온다.
   *
   * 테두리를 더하는 것은 `box-sizing: border-box`(전역) 때문이다 — `height`는 테두리를 포함하는데
   * `scrollHeight`는 포함하지 않아, 그냥 옮기면 매번 2px 모자라 스크롤바가 선다.
   */
  useEffect(() => {
    const box = composer.current
    if (box === null) {
      return
    }
    box.style.height = 'auto'
    box.style.height = `${box.scrollHeight + (box.offsetHeight - box.clientHeight)}px`
  }, [input])

  /**
   * ＋로 고른 사진을 즉시 올린다 — 전송 버튼을 기다리지 않는다(사용자가 발화를 쓰는 동안 겹쳐 진행된다).
   *
   * 한 장이라도 수용 포맷(JPEG/PNG)이 아니면 서버가 400으로 답하고 **아무것도 스테이징되지 않는다** —
   * 부분 성공이 없으므로 화면도 전부 실패로 다루고 사용자가 다시 고른다.
   */
  async function attach(files: FileList | null) {
    const picked = files === null ? [] : Array.from(files)
    if (picked.length === 0 || uploading || busy) {
      return
    }
    setUploading(true)
    try {
      const uploaded = await postPhotos(picked)
      setPhotos((prev) => [...prev, ...uploaded.photos.map((photo) => photo.name)])
    } catch (error) {
      setMessages((prev) => [
        ...prev,
        { role: 'system', text: describe(error, '사진을 올리지 못했어요. JPEG·PNG만 받을 수 있어요.') },
      ])
    } finally {
      setUploading(false)
      // 같은 파일을 다시 고를 수 있게 값을 비운다 — 안 비우면 change 이벤트가 안 뜬다.
      if (picker.current !== null) {
        picker.current.value = ''
      }
    }
  }

  async function send() {
    const utterance = input.trim()
    // 사진만 첨부하고 보내는 것도 유효한 턴이다(D-11) — 그때 재료는 사진에서 나온다.
    if ((utterance === '' && photos.length === 0) || busy || uploading) {
      return
    }
    const attached = photos
    setInput('')
    setMessages((prev) => [...prev, { role: 'user', text: utterance, photos: attached.length }])
    setBusy(true)
    try {
      const response = await postAgentTurn({ utterance, draft, photos: attached })
      // 서버가 받아 읽은 사진은 이 메시지의 것으로 소진된다 — 다음 턴에 다시 실으면 같은 사진을 또 읽는다.
      setPhotos([])
      setMessages((prev) => [...prev, { role: 'agent', text: response.reply }])
      // 제안 없는 턴(잡담·조회·검증 거부)은 draft가 null로 온다 — 폼을 지우지 않는다.
      if (response.draft !== null) {
        setDraft(response.draft)
      }
    } catch (error) {
      // 실패하면 첨부는 그대로 둔다 — 사진은 스테이징에 서 있고, 재시도가 이름만 다시 실으면 된다.
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
      // 보내지 않고 남은 첨부는 이 노트의 것이었다 — 저장이 끝났으니 화면에서도 내린다.
      setPhotos([])
      setMessages((prev) => [
        ...prev,
        {
          role: 'system',
          text: savedNotice(saved.note_id, merged),
          // 병합이면 폼에서 고친 커피 사실이 저장되지 않았다 — 그것을 고칠 수 있는 자리로 바로 보낸다.
          // TΔ11 이월 (a)가 닫히는 지점이고, 안내만 있고 갈 곳이 없던 상태가 여기서 끝난다(TΔ13b).
          action: merged ? { label: '커피 정보 고치기 ›', path: editPath(saved.note_id) } : undefined,
        },
      ])
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
    // 취소 자체를 되돌릴 이유는 없다. 사진 스테이징 폐기도 같은 통지가 겸한다(TΔ8a).
    setDraft(null)
    setPhotos([])
    setMessages((prev) => [...prev, { role: 'system', text: '작성 중이던 노트를 지웠어요.' }])
    try {
      await postAgentCancel()
    } catch (error) {
      // 사용자가 할 수 있는 일은 없지만 조용히 삼키지 않는다 — 다음 대화가 이상하면 이 줄이 단서다.
      setMessages((prev) => [...prev, { role: 'system', text: describe(error, '이전 대화 내용은 정리하지 못했어요.') }])
    }
  }

  /**
   * Enter 배분 — 데스크톱은 전송, 폰·태블릿은 줄바꿈 (사용자 확정 2026-08-02, TΔ25).
   *
   * 각 매체의 관습을 따른다: 물리 키보드 앞에서 Enter는 전송이고(줄바꿈은 Shift+Enter), 폰에서는
   * Enter가 줄바꿈이고 전송은 ↑ 버튼이다 — 소프트 키보드에는 Shift 조합이 사실상 없다.
   *
   * 판정은 **매번 새로 읽는다**. 마운트 시점에 굳히면 아이패드에 키보드를 붙였다 뗀 뒤로 배분이 어긋난 채
   * 남는다. `matchMedia`는 동기 조회라 값이 싸다.
   *
   * ⚠️ `isComposing` 가드가 이 핸들러의 핵심이다 — 한글 조합 중의 Enter는 **조합을 확정하는 키**다.
   *    가드가 없으면 "첼베사"를 치다 만 상태가 그대로 전송된다.
   */
  function handleKey(event: KeyboardEvent<HTMLTextAreaElement>) {
    if (event.key !== 'Enter' || event.nativeEvent.isComposing) {
      return
    }
    // Cmd/Ctrl+Enter는 매체와 무관하게 전송이다 — 데스크톱 관습이고, 폰에서는 눌릴 일이 없다.
    const shortcut = event.metaKey || event.ctrlKey
    if (!shortcut && (event.shiftKey || event.altKey || window.matchMedia('(pointer: coarse)').matches)) {
      return
    }
    event.preventDefault()
    void send()
  }

  return (
    <div className="shell">
      <div className="panel">
        <header className="header">
          <img className="header__mascot" src="/mascot-face.png" alt="" />
          <h1 className="header__title">모카와 대화</h1>
          {/* 갤러리로 가는 유일한 입구(TΔ12) — 시안에 없는 편차 ③이다. */}
          <button type="button" className="header__link" onClick={() => onNavigate(GALLERY)}>
            노트 목록 ›
          </button>
        </header>

        <div className="stream">
          {messages.map((message, index) => (
            <Bubble key={index} message={message} onNavigate={onNavigate} />
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
          {(photos.length > 0 || uploading) && (
            <div className="composer__attachments">
              {photos.map((name) => (
                <span key={name} className="attachment">
                  🖼 {name}
                </span>
              ))}
              {uploading && <span className="attachment attachment--pending">올리는 중…</span>}
            </div>
          )}
          {/*
            ＋는 *메시지에 사진을 첨부하는* 버튼이지 독립 동작이 아니다(D-11) — 고른 순간 업로드되지만
            사진이 노트에 닿는 것은 이 메시지가 전송될 때다. accept를 JPEG/PNG로 좁혀 두면 피커에서부터
            거부될 파일이 덜 보이지만, 그것은 편의일 뿐이고 실제 게이트는 서버의 매직바이트다(ADR-29).
          */}
          <input
            ref={picker}
            className="composer__picker"
            type="file"
            accept="image/jpeg,image/png"
            multiple
            onChange={(event) => void attach(event.target.files)}
          />
          <button
            type="button"
            className="composer__attach"
            aria-label="사진 첨부"
            disabled={busy || uploading}
            onClick={() => picker.current?.click()}
          >
            ＋
          </button>
          {/*
            여러 줄이 들어와야 한다(TΔ25) — ADR-61(다중 날짜 자동 분해)이 상정한 "여러 날짜가 섞인 메모"는
            줄바꿈 없이는 애초에 붙여 넣을 수 없다. 한 줄 `input`이던 시절엔 그 입력이 존재할 수 없었다.
          */}
          <textarea
            ref={composer}
            className="composer__input"
            value={input}
            rows={1}
            placeholder="메시지 쓰기…"
            onChange={(event) => setInput(event.target.value)}
            onKeyDown={handleKey}
          />
          <button
            type="submit"
            className="composer__send"
            aria-label="보내기"
            disabled={busy || uploading || (input.trim() === '' && photos.length === 0)}
          >
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
  /** 이 메시지에 함께 보낸 사진 장수 — 말풍선이 "무엇을 보냈는지"를 온전히 남기기 위한 것뿐이다. */
  photos?: number
  /** 안내가 가리키는 다음 자리 — 지금은 병합 저장 뒤의 수정 화면 하나뿐이다(TΔ13b). */
  action?: { label: string; path: string }
}

const GREETING: Message = { role: 'agent', text: '오늘의 한 잔, 어땠어요? ☕' }

function Bubble({ message, onNavigate }: { message: Message; onNavigate: (path: string) => void }) {
  if (message.role === 'system') {
    const action = message.action
    return (
      <div className="notice">
        {message.text}
        {action !== undefined && (
          <button type="button" className="notice__action" onClick={() => onNavigate(action.path)}>
            {action.label}
          </button>
        )}
      </div>
    )
  }
  if (message.role === 'user') {
    const attached = message.photos ?? 0
    return (
      <div className="row row--user">
        <div className="bubble bubble--user">
          {attached > 0 && <span className="bubble__photos">🖼 사진 {attached}장</span>}
          {message.text}
        </div>
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
 * (delta.md §1.2 — 다만 이번 것은 버그가 아니라 정책이다).
 *
 * **TΔ13b에서 그 안내에 갈 곳이 생겼다**: 병합 저장이면 안내 옆에 수정 화면 링크가 붙는다(TΔ11 이월 (a)
 * 해소). 필드를 잠그는 쪽은 고르지 않았다 — 폼의 값은 *"이 커피는 이런 커피다"*라는 사용자의 판단이고,
 * 저장되지 않는다는 이유로 입력조차 막으면 그 판단을 옮겨 적을 자리가 사라진다.
 */
function savedNotice(noteId: number, merged: boolean): string {
  return merged
    ? `기존 노트 #${noteId}에 오늘 기록을 더했어요. 커피 정보는 기존 노트의 값을 그대로 뒀어요.`
    : `저장했어요. (노트 #${noteId})`
}

function describe(error: unknown, fallback: string): string {
  return error instanceof Error && error.message !== '' ? `${fallback} (${error.message})` : fallback
}
