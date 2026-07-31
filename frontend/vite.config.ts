import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// 모카 프론트엔드 빌드 설정 (changes/0029 TΔ23, delta.md D-10 ④).
export default defineConfig({
  plugins: [react()],
  build: {
    // POLICY: 산출물은 레포 루트 build/ 아래로 낸다 — src/main/resources/static/에 직접 쓰면
    // 소스 트리에 빌드 산출물이 섞이고 .gitignore 예외가 필요해진다 (ref: tasks.md TΔ23).
    // 여기서 Gradle의 processResources가 가져가 static/으로 얹는다.
    outDir: '../build/frontend',
    emptyOutDir: true,
  },
  server: {
    // 개발 편의(HMR) 전용이다. 배포 산출물은 항상 Gradle 통합 경로 하나이고,
    // 두 경로가 갈리지 않도록 프록시 대상을 /api 단일 접두로 유지한다
    // (ref: delta.md#D-4 경로 규약, #D-10 ④).
    proxy: {
      '/api': 'http://localhost:8080',
    },
  },
})
