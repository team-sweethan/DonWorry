import assert from 'node:assert/strict'
import { test } from 'node:test'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { createServer } from 'vite'

// 세 기능을 항상 보여주면서, 연결 완료 후 키 원문 다시 보기 금지와 처리 중 조작 제한을 유지한다.
test('integrationKeyActions_keepsAllActionsVisibleAndPreservesExistingStateGuards', async () => {
  const server = await createServer({
    cacheDir: 'node_modules/.vite-integration-key-actions-test',
    server: { middlewareMode: true, ws: false, hmr: false, watch: null },
    appType: 'custom',
  })
  try {
    const { IntegrationKeyActions } = await server.ssrLoadModule(
      '/src/pages/owner/IntegrationKeyActions.tsx',
    )
    for (const status of ['NO_KEY', 'PENDING_CONNECTION', 'CONNECTED'] as const) {
      for (const disabled of [false, true]) {
        const html = renderToStaticMarkup(
          createElement(IntegrationKeyActions, { status, disabled, onAction: () => {} }),
        )
        const buttons = [...html.matchAll(/<button\b([^>]*)>([\s\S]*?)<\/button>/g)].map(
          (match) => ({
            disabled: /\sdisabled=/.test(match[1]),
            text: match[2].replace(/<[^>]+>/g, ''),
          }),
        )
        assert.equal(buttons.length, 3, `${status}: 발급·다시 보기·재발급 버튼이 모두 보여야 한다`)
        assert.deepEqual(
          buttons.map((button) => button.text),
          ['시크릿 키 발급', '키 다시 보기', '시크릿 키 재발급'],
        )
        assert.equal(buttons[0].disabled, disabled || status !== 'NO_KEY')
        assert.equal(buttons[1].disabled, disabled || status !== 'PENDING_CONNECTION')
        assert.equal(buttons[2].disabled, disabled || status === 'NO_KEY')
        assert.doesNotMatch(html, /<details/)
      }
    }
  } finally {
    await server.close()
  }
})
