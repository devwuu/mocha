import { useEffect, useRef, useState } from 'react'
import type { KeyboardEvent } from 'react'
import type { Draft, NoteDetail } from '../api'
import { patchNoteEntry, postAgentCancel, postAgentTurn, postNoteCommit, postPhotos } from '../api'
import type { EntryDraft } from '../edit/noteEdits'
import { toEntryUpdate } from '../edit/noteEdits'
import { editPath, GALLERY, notePath } from '../routes'
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
 * 화면이 들고 있는 것은 스테이징 파일명 + **로컬 미리보기 URL**뿐이며, 읽기(OCR)·검색 보강·필드 채움은
 * 전부 서버 턴 안에서 모델이 한다 — **이 파일에 병합 코드가 0줄인 것이 D-11의 결정이다.**
 *
 * **묶인 결과가 눈에 보인다**(TΔ26): 전송 전에는 입력창 위에 썸네일로 서고(개별 취소 가능), 전송 후에는
 * 그 사진이 말풍선에 남는다. 사진과 발화를 *사용자가 직접* 묶는 구조라 그 묶음이 보여야 확인이 가능하다.
 */
interface ChatScreenProps {
  onNavigate: (path: string) => void
}

export function ChatScreen({ onNavigate }: ChatScreenProps) {
  const [messages, setMessages] = useState<Message[]>([GREETING])
  const [draft, setDraft] = useState<Draft | null>(null)
  const [busy, setBusy] = useState(false)
  const [input, setInput] = useState('')
  // 업로드가 끝나 스테이징에 서 있는 사진 — 다음 전송에 실린다.
  const [photos, setPhotos] = useState<Attachment[]>([])
  const [uploading, setUploading] = useState(false)
  const tail = useRef<HTMLDivElement>(null)
  const picker = useRef<HTMLInputElement>(null)
  const composer = useRef<HTMLTextAreaElement>(null)
  // 이 화면이 만든 objectURL 전부 — 언마운트 때 남은 것을 걷는다(아래 정리 effect).
  const objectUrls = useRef<string[]>([])

  /*
   * 대화를 떠나면 미리보기 URL을 전부 폐기한다.
   *
   * 이 화면을 벗어나면 대화도 폼도 사라지므로(OQ-1 ㉡ — 서버에 pending이 없다) 말풍선에 실린 사진도
   * 함께 사라진다. 즉 **말풍선이 사라지는 시점 = 이 컴포넌트의 언마운트**이고, 여기가 그 자리다.
   * 걷지 않으면 고른 사진의 바이트가 탭이 닫힐 때까지 메모리에 남는다.
   */
  useEffect(() => {
    const urls = objectUrls.current
    return () => urls.forEach((url) => URL.revokeObjectURL(url))
  }, [])

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
   *
   * **미리보기는 로컬 파일에서 만든다**(TΔ26) — 업로드 응답이 주는 것은 이름뿐이고, 스테이징 사진에
   * 서빙 경로를 여는 안은 기각했다: **확인 전 사진에 URL이 생겨** 노출 면적만 늘고, 대화가 세션 수명이라
   * 서버에서 다시 읽을 일도 없다.
   */
  async function attach(files: FileList | null) {
    const picked = files === null ? [] : Array.from(files)
    if (picked.length === 0 || uploading || busy) {
      return
    }
    setUploading(true)
    try {
      const uploaded = await postPhotos(picked)
      // 스테이징 이름은 올린 순서로 온다(`PhotoService.stage`) — 그 순서로 원본 파일과 짝지어 미리보기를
      // 만든다. URL 생성은 setState **밖**이다: 상태 갱신 함수는 StrictMode에서 두 번 불려 URL이 겹친다.
      const attached = uploaded.photos.map((photo, index) => preview(photo.name, picked[index]))
      setPhotos((prev) => [...prev, ...attached])
    } catch (error) {
      setMessages((prev) => [
        ...prev,
        { role: 'system', text: describe(error, '사진을 올리지 못했어. JPEG·PNG만 받을 수 있어.') },
      ])
    } finally {
      setUploading(false)
      // 같은 파일을 다시 고를 수 있게 값을 비운다 — 안 비우면 change 이벤트가 안 뜬다.
      if (picker.current !== null) {
        picker.current.value = ''
      }
    }
  }

  /** 미리보기 URL을 만들고 정리 목록에 올린다 — 만드는 자리와 걷는 자리가 갈리지 않게 한 곳에 둔다. */
  function preview(name: string, file: File): Attachment {
    const url = URL.createObjectURL(file)
    objectUrls.current.push(url)
    return { name, url }
  }

  /** 전송 전 첨부 취소 — 잘못 고른 사진을 알아채는 자리가 저장 후 갤러리이던 것이 여기서 닫힌다. */
  function detach(target: Attachment) {
    setPhotos((prev) => prev.filter((photo) => photo !== target))
    release([target])
  }

  /**
   * 첨부를 화면에서 내릴 때 그 URL을 폐기한다 — **말풍선이 참조하지 않는 것만.**
   *
   * 전송이 실패한 턴은 첨부를 그대로 남기므로(재시도가 이름만 다시 싣는다) 같은 URL이 *이미 대화에 남은
   * 말풍선*과 첨부 스트립 양쪽에 걸린다. 그때 폐기하면 말풍선의 사진이 깨진 이미지가 된다 — 그래서
   * "화면에서 내린다"와 "URL을 폐기한다"가 같은 일이 아니다. 남는 것은 언마운트가 걷는다.
   *
   * 스테이징 파일 자체는 서버에 남는다 — 폐기는 [취소]/[저장] 통지가 겸하고(`PhotoService.discard`),
   * 이 화면이 그 사이에 개별 삭제 API를 새로 열지 않는다(TΔ26은 서버 무변경이다).
   */
  function release(dropped: Attachment[]) {
    const shown = new Set(messages.flatMap((message) => message.photos ?? []).map((photo) => photo.url))
    dropped.forEach((photo) => {
      if (!shown.has(photo.url)) {
        URL.revokeObjectURL(photo.url)
      }
    })
  }

  async function send() {
    const utterance = input.trim()
    // 사진만 첨부하고 보내는 것도 유효한 턴이다(D-11) — 그때 재료는 사진에서 나온다.
    if ((utterance === '' && photos.length === 0) || busy || uploading) {
      return
    }
    const attached = photos
    setInput('')
    // 사진은 **말풍선으로 대화에 남는다**(TΔ26) — 무엇을 이 발화에 묶었는지가 사후에도 읽혀야 한다.
    setMessages((prev) => [...prev, { role: 'user', text: utterance, photos: attached }])
    setBusy(true)
    try {
      const response = await postAgentTurn({ utterance, draft, photos: attached.map((photo) => photo.name) })
      // 서버가 받아 읽은 사진은 이 메시지의 것으로 소진된다 — 다음 턴에 다시 실으면 같은 사진을 또 읽는다.
      // URL은 폐기하지 않는다: 말풍선이 그대로 이어받았고, 여기서 걷으면 방금 보낸 사진이 깨진다.
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
      setMessages((prev) => [...prev, { role: 'system', text: describe(error, '잘 못 들었어. 다시 한 번 말해 줘.') }])
    } finally {
      setBusy(false)
    }
  }

  /**
   * [저장] — **출구가 둘이다** (TΔ28b, D-14, AC-13).
   *
   * `match.type`이 `edit`이면 *"그때 그 기록이 틀렸다"*이므로 **있던 회차를 갈아끼우고**(시음일 PATCH),
   * `new`·`existing`이면 *"오늘 이걸 마셨다"*이므로 **회차가 는다**(`POST /api/notes`). 같은 노트를
   * 가리켜도 결과가 반대라 축을 합칠 수 없다 — 수정하려던 사람이 `existing`으로 새면 고치려던 기록은
   * 그대로 두고 회차만 하나 더 붙는다.
   *
   * **요청을 만드는 코드는 `edit/noteEdits.ts` 한 벌이다**(D-14 ③) — 수정 화면과 같은 PATCH를 이 화면이
   * 따로 조립하면 한쪽만 고치는 순간 delta.md §1.2와 같은 조용한 어긋남이 난다.
   *
   * ⚠️ **수정 모드에 첨부한 사진은 노트에 닿지 않는다** — 사진 확정은 커밋 경로(`NoteService.commit`)의
   * 일이고 PATCH에는 그 자리가 없다. 그 사진은 그 턴의 OCR 재료로만 쓰이고 아래 [취소] 통지가 폐기한다.
   */
  async function save() {
    if (draft === null || busy) {
      return
    }
    const current = draft
    const match = current.match
    setBusy(true)
    try {
      const notices = match.type === 'edit' ? await saveEdit(current, match.note_id) : await saveCommit(current)
      setDraft(null)
      // 보내지 않고 남은 첨부는 이 노트의 것이었다 — 저장이 끝났으니 화면에서도 내린다.
      setPhotos([])
      release(photos)
      setMessages((prev) => [...prev, ...notices])
    } catch (error) {
      setMessages((prev) => [...prev, { role: 'system', text: describe(error, '저장하지 못했어. 폼은 그대로 뒀어.') }])
    } finally {
      setBusy(false)
    }
  }

  /** 신규·병합 저장 — 서버가 접힘(SAVE_COMMIT)과 사진 확정을 겸한다(`NoteController.commit`). */
  async function saveCommit(current: Draft): Promise<Message[]> {
    const merged = current.match.type === 'existing'
    const saved = await postNoteCommit(current)
    return [
      {
        role: 'system',
        text: savedNotice(saved.note_id, merged),
        // 병합이면 폼에서 고친 커피 사실이 저장되지 않았다 — 그것을 고칠 수 있는 자리로 바로 보낸다.
        // TΔ11 이월 (a)가 닫히는 지점이고, 안내만 있고 갈 곳이 없던 상태가 여기서 끝난다(TΔ13b).
        action: merged ? { label: '커피 정보 고치기 ›', path: editPath(saved.note_id) } : undefined,
      },
    ]
  }

  /**
   * 수정 저장 — 그 날짜의 회차를 갈아끼운다. **회차가 늘지 않는 것이 AC-13의 증명 지점이다.**
   *
   * 저장 뒤 [취소] 통지를 이어 부르는 것은 **뒷정리 때문이다**(사용자 확정 2026-08-02): `POST /api/notes`는
   * 서버가 트랜스크립트 접힘(ADR-46 *"확정된 작업의 문맥은 버린다"*)과 사진 스테이징 정리를 겸하는데,
   * PATCH는 수정 **화면**용으로 열린 경로라 그 자리가 없다. 알리지 않으면 끝난 작업의 대화 문맥이 TTL까지
   * 남아 다음 커피의 첫 발화에 섞인다 — TΔ6b가 [취소] 통지를 만든 바로 그 이유다.
   *
   * **정리 실패를 저장 실패로 보고하지 않는다**: 노트는 이미 바뀌었으므로 되돌릴 것이 없고, 사용자가
   * 재시도할 것도 없다. 그래도 조용히 삼키지는 않는다 — 다음 대화가 이상하면 이 줄이 단서다.
   */
  async function saveEdit(current: Draft, noteId: number): Promise<Message[]> {
    const update = toEntryUpdate(current)
    const saved = await patchNoteEntry(noteId, update.targetDate, update.value)
    const notice: Message = {
      role: 'system',
      text: editedNotice(saved, update),
      action: { label: '기록 보기 ›', path: notePath(saved.note_id) },
    }
    try {
      await postAgentCancel()
    } catch (error) {
      return [notice, { role: 'system', text: describe(error, '이전 대화 내용은 정리하지 못했어.') }]
    }
    return [notice]
  }

  async function cancel() {
    // 폼은 클라이언트 상태라 버리는 것으로 끝나지만 서버 대화 문맥의 접힘은 통지가 필요하다(TΔ6b) —
    // 알리지 않으면 버린 작업의 문맥이 TTL까지 남아 다음 커피의 첫 발화에 섞인다.
    // 폼은 응답을 기다리지 않고 즉시 버린다: 사용자가 [취소]를 누른 순간 그것이 의도이고, 통지 실패가
    // 취소 자체를 되돌릴 이유는 없다. 사진 스테이징 폐기도 같은 통지가 겸한다(TΔ8a).
    setDraft(null)
    setPhotos([])
    release(photos)
    setMessages((prev) => [...prev, { role: 'system', text: '작성 중이던 노트를 지웠어.' }])
    try {
      await postAgentCancel()
    } catch (error) {
      // 사용자가 할 수 있는 일은 없지만 조용히 삼키지 않는다 — 다음 대화가 이상하면 이 줄이 단서다.
      setMessages((prev) => [...prev, { role: 'system', text: describe(error, '이전 대화 내용은 정리하지 못했어.') }])
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
          {/*
            전송 «전»의 확인 자리(TΔ26) — 무엇을 붙였는지 보이고 한 장씩 뺄 수 있다. 이것이 없으면 잘못
            고른 사진을 알아채는 자리가 저장 후 갤러리다.
          */}
          {(photos.length > 0 || uploading) && (
            <div className="composer__attachments">
              {photos.map((photo, index) => (
                <span key={photo.name} className="attachment">
                  {/* 대체 텍스트에 순번을 넣는다 — 스테이징 이름은 사용자가 고른 이름이 아니라 알려 줄 것이
                      "몇 번째 사진인가"뿐이고, 취소 버튼도 같은 순번으로 무엇을 지우는지 가리킨다. */}
                  <img className="attachment__thumb" src={photo.url} alt={`첨부한 사진 ${index + 1}`} />
                  <button
                    type="button"
                    className="attachment__remove"
                    aria-label={`${index + 1}번째 사진 첨부 취소`}
                    disabled={busy}
                    onClick={() => detach(photo)}
                  >
                    ×
                  </button>
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

/**
 * 첨부 사진 1장 — 스테이징 이름(서버가 아는 것)과 미리보기 URL(화면만 아는 것)의 짝이다.
 *
 * **전송 전 스트립과 전송 후 말풍선이 같은 객체를 공유한다**(TΔ26) — 갈라 두면 전송 시점에 목록을 옮겨
 * 담게 되고 그 사이가 어긋난다. 그래서 URL의 수명도 이 객체 하나를 따라간다.
 */
interface Attachment {
  /** `POST /api/photos`가 돌려준 스테이징 파일명 — 턴 요청에 실리는 것은 이것뿐이다. */
  name: string
  /** `URL.createObjectURL`로 만든 로컬 미리보기 — 서버는 사진에 URL을 주지 않는다(V-4의 정신). */
  url: string
}

interface Message {
  role: 'user' | 'agent' | 'system'
  text: string
  /** 이 메시지에 함께 보낸 사진 — 말풍선이 "무엇을 보냈는지"를 온전히 남기기 위한 것뿐이다. */
  photos?: Attachment[]
  /** 안내가 가리키는 다음 자리 — 지금은 병합 저장 뒤의 수정 화면 하나뿐이다(TΔ13b). */
  action?: { label: string; path: string }
}

const GREETING: Message = { role: 'agent', text: '오늘의 한 잔, 어땠어? ☕' }

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
    const attached = message.photos ?? []
    return (
      <div className="row row--user">
        <div className="bubble bubble--user">
          {/* 전송 «후»의 확인 자리(TΔ26) — 사진과 발화를 사용자가 직접 묶는 구조라(D-11) 묶인 결과가
              보여야 그 결정이 확인 가능해진다. 사진만 보낸 턴은 본문이 빈 말풍선이 된다. */}
          {attached.length > 0 && (
            <span className="bubble__photos">
              {attached.map((photo, index) => (
                <img key={photo.name} className="bubble__photo" src={photo.url} alt={`보낸 사진 ${index + 1}`} />
              ))}
            </span>
          )}
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
 * "재기록은 그날의 시음일을 쌓는 일이지 커피의 사실을 다시 쓰는 일이 아니다"(ADR-4). 그것을 알리지 않으면
 * 사용자가 고친 값이 화면에 남은 채 조용히 무시되고, 그 형태가 이 델타가 없애려는 실패와 닮는다
 * (delta.md §1.2 — 다만 이번 것은 버그가 아니라 정책이다).
 *
 * **TΔ13b에서 그 안내에 갈 곳이 생겼다**: 병합 저장이면 안내 옆에 수정 화면 링크가 붙는다(TΔ11 이월 (a)
 * 해소). 필드를 잠그는 쪽은 고르지 않았다 — 폼의 값은 *"이 커피는 이런 커피다"*라는 사용자의 판단이고,
 * 저장되지 않는다는 이유로 입력조차 막으면 그 판단을 옮겨 적을 자리가 사라진다.
 */
function savedNotice(noteId: number, merged: boolean): string {
  return merged
    ? `기존 노트 #${noteId}에 오늘 기록을 더했어. 커피 정보는 기존 노트의 값을 그대로 뒀어.`
    : `저장했어. (노트 #${noteId})`
}

/**
 * 수정 저장 안내 — 제자리 수정 · 날짜 이동 · 회차 병합 셋으로 갈린다 (TΔ28c, D-12).
 *
 * **판정을 응답에서 읽는 것이 요점이다.** 수정 화면(TΔ13b)은 노트 전문을 들고 있어 저장 *전에* 충돌을
 * 알리지만(`mergeTargetOf`) 채팅 폼은 기록 1건만 담아 그럴 수 없다 — 그래서 폼은 *"그날 기록이 있으면
 * 합쳐져"*까지만 말하고, **무슨 일이 실제로 일어났는지는 여기서 답한다.** 서버가 돌려준 노트가 결과를
 * 이미 담고 있으므로 요청을 하나도 더 보내지 않는다.
 *
 * 병합 판정이 *"보낸 회차보다 많다"*인 것은 그 자리에 원래 있던 회차 뒤로 이어 붙기 때문이다(D-12 —
 * 기존이 앞, 옮겨 온 것이 뒤). 이동처가 비어 있었으면 보낸 그대로라 수가 같다.
 */
function editedNotice(saved: NoteDetail, update: EntryDraft): string {
  const suffix = `(노트 #${saved.note_id})`
  if (update.value.date === update.targetDate) {
    return `${update.value.date} 기록을 고쳤어. ${suffix}`
  }
  const cups = saved.entries.find((entry) => entry.date === update.value.date)?.cups.length ?? 0
  return cups > update.value.cups.length
    ? `${update.targetDate} 기록을 ${update.value.date} 기록에 합쳤어. 이제 ${cups}회차야. ${suffix}`
    : `${update.targetDate} 기록을 ${update.value.date}로 옮겼어. ${suffix}`
}

function describe(error: unknown, fallback: string): string {
  return error instanceof Error && error.message !== '' ? `${fallback} (${error.message})` : fallback
}
