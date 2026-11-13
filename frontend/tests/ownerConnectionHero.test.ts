import assert from 'node:assert/strict'
import { test } from 'node:test'
import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { createServer } from 'vite'

// 대기 상태를 연결 완료로 보이지 않고, 현재 단계와 다음 안내를 서버 상태에 맞춰 보여준다.
test('ownerConnectionHero_preservesServerStatusAndShowsCurrentSetupStep', async () => {
  const server = await createServer({
    cacheDir: 'node_modules/.vite-owner-connection-test',
    server: { middlewareMode: true, ws: false, hmr: false, watch: null },
    appType: 'custom',
  })
  try {
    const { OwnerConnectionHero } = await server.ssrLoadModule(
      '/src/pages/owner/OwnerConnectionHero.tsx',
    )
    const render = (status: 'NO_KEY' | 'PENDING_CONNECTION' | 'CONNECTED') =>
      renderToStaticMarkup(
        createElement(OwnerConnectionHero, {
          status,
          description: '쇼핑몰 서버 설정 후 상태를 새로고침해 주세요.',
        }),
      )
    const noKey = render('NO_KEY')
    assert.match(noKey, /아직 쇼핑몰과 연결되지 않았어요/)
    assert.match(noKey, /class="is-current" aria-current="step"/)
    assert.doesNotMatch(noKey, /class="och-check/)
    assert.doesNotMatch(noKey, /class="is-done"/)
    const pending = render('PENDING_CONNECTION')
    assert.match(pending, /is-waiting/)
    assert.match(pending, /쇼핑몰 서버의 연결 확인을 기다리고 있어요/)
    assert.equal((pending.match(/class="is-done"/g) ?? []).length, 1)
    assert.equal((pending.match(/aria-current="step"/g) ?? []).length, 1)
    assert.doesNotMatch(pending, /class="och-check/)
    const done = render('CONNECTED')
    assert.match(done, /쇼핑몰과 연결됐어요/)
    assert.match(done, /class="och-check/)
    assert.equal((done.match(/class="is-done"/g) ?? []).length, 3)
    assert.doesNotMatch(done, /aria-current="step"/)
    assert.match(done, /class="dw-mascot-float"/)
    assert.match(done, /쇼핑몰 서버 설정 후 상태를 새로고침해 주세요./)
  } finally {
    await server.close()
  }
})
