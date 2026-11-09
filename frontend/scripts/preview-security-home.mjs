import { createServer } from 'vite'

// 테스트용 Vite 인스턴스가 의존성 캐시를 갱신해도 미리보기를 유지한다.
const server = await createServer({
  cacheDir: 'node_modules/.vite-security-home-preview',
  server: { host: '127.0.0.1', port: 5174, strictPort: true },
})
await server.listen()
console.log('보안 홈 샘플: http://localhost:5174/security-home-preview.html')
console.log('실제 보안 홈: http://localhost:5174/security/home')
