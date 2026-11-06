import assert from 'node:assert/strict'
import { test } from 'node:test'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { createServer } from 'vite'

// 키 보기에는 가맹점 코드와 키, 개별 복사 및 두 값 설정 후 연결 확인 안내를 함께 표시한다.
test('integrationCredentials_whenShowingKey_includesMerchantCodeAndSetupSteps', async () => {
  const server = await createServer({
    server: { middlewareMode: true, ws: false, hmr: false, watch: null },
    appType: 'custom',
  })
  try {
    const { IntegrationCredentials } = await server.ssrLoadModule(
      '/src/pages/owner/IntegrationCredentials.tsx',
    )
    const html = renderToStaticMarkup(
      createElement(IntegrationCredentials, {
        merchantCode: 'NEWSTORE575',
        secret: 'test-only-base64-key==',
        onCopyError: () => {},
      }),
    )
    for (const value of [
      'NEWSTORE575',
      'test-only-base64-key==',
      '가맹점 코드 복사',
      '시크릿 키 복사',
      '가맹점 코드와 시크릿 키를 모두 쇼핑몰 서버에 설정하세요',
      '204 응답',
      '새로고침해 연동 완료',
    ])
      assert.ok(html.includes(value), value)
  } finally {
    await server.close()
  }
})
