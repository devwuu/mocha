/**
 * 배관 확인용 셸 (changes/0029 TΔ23).
 *
 * 이 화면이 세우는 것은 화면이 아니라 배관이다 — Vite 빌드 → Gradle processResources →
 * Spring 정적 서빙 → SPA 라우팅 fallback이 실제로 이어지는지를 눈으로 확인한다.
 * 실제 화면(캡처·갤러리·상세)은 S1·S2 슬라이스에서 들어온다.
 */
function App() {
  return (
    <main>
      <h1>모카</h1>
      <p>클라이언트 셸이 떴다. 화면은 아직 없다.</p>
      {/* fallback 확인 지점: /아무경로나 를 쳐도 이 화면이 뜨고 경로가 그대로 보존된다. */}
      <p className="path">
        경로 <code>{window.location.pathname}</code>
      </p>
    </main>
  )
}

export default App
