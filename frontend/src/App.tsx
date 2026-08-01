import { ChatScreen } from './chat/ChatScreen'

/**
 * 앱 진입점 (changes/0029).
 *
 * 지금은 화면이 하나다 — 캡처(TΔ10). 갤러리·상세(S2)가 들어오면 그때 라우터가 여기 선다.
 * SPA fallback은 이미 서 있으므로(TΔ23 `WebConfig`) 경로가 늘어도 서버는 손대지 않는다.
 */
function App() {
  return <ChatScreen />
}

export default App
