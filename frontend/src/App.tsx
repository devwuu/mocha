import { useEffect, useState } from 'react'
import { ChatScreen } from './chat/ChatScreen'
import { DetailScreen } from './detail/DetailScreen'
import { GalleryScreen } from './gallery/GalleryScreen'
import { GALLERY, matchNote } from './routes'

/**
 * 앱 진입점 + 라우팅 (changes/0029, TΔ12에서 화면이 둘이 됐다).
 *
 * **라우터 라이브러리를 들이지 않는다**(루트 §4 right-sizing). 지금 필요한 것은 경로 하나로 화면을 고르고
 * 뒤로 가기를 살리는 것뿐이고, 그 전부가 아래 20줄이다. 중첩 라우트·로더·코드 스플리팅이 실제로 필요해지면
 * 그때가 판단 지점이다 — 그때 바꿀 곳도 이 파일 하나다.
 *
 * SPA fallback은 이미 서 있으므로(TΔ23 `WebConfig`) 경로가 늘어도 서버는 손대지 않는다 — `/notes`를
 * 새로고침해도 index.html이 오고 여기서 다시 갈린다.
 *
 * TΔ13a가 `/notes/{id}`(상세)를 보탰다 — 화면이 셋이 됐지만 고르는 방식은 그대로다.
 */
function App() {
  const [path, setPath] = useState(() => window.location.pathname)

  // 브라우저 뒤로/앞으로 — pushState는 이 이벤트를 쏘지 않으므로 navigate가 직접 상태를 옮긴다.
  useEffect(() => {
    const sync = () => setPath(window.location.pathname)
    window.addEventListener('popstate', sync)
    return () => window.removeEventListener('popstate', sync)
  }, [])

  function navigate(next: string) {
    if (next === window.location.pathname) {
      return
    }
    window.history.pushState(null, '', next)
    setPath(next)
  }

  if (path === GALLERY) {
    return <GalleryScreen onNavigate={navigate} />
  }
  // 상세는 경로가 값을 담는 유일한 화면이라 id가 곧 조회 키다. key로도 쓴다 — 상세에서 상세로 옮겨가면
  // (아직 그런 경로는 없지만) 컴포넌트가 새로 서야 이전 노트의 내용이 잠깐 비치지 않는다.
  const noteId = matchNote(path)
  if (noteId !== null) {
    return <DetailScreen key={noteId} noteId={noteId} onNavigate={navigate} />
  }
  // 모르는 경로는 대화로 떨어진다 — 캡처가 이 앱의 기본 화면이다(델타가 S1을 최우선으로 둔 것과 같은 이유).
  return <ChatScreen onNavigate={navigate} />
}

export default App
