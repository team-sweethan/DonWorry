import assert from 'node:assert/strict'
import { test } from 'node:test'
import { createPreviewLoader } from '../src/pages/public/landingPreviewLoader.ts'

// 네트워크 오류가 발생해도 다음 시도는 실제 로더를 다시 실행한다.
test('previewLoader_whenFirstRequestFails_retriesAndSharesSuccessfulResult', async () => {
  let attempts = 0
  const frame = { name: 'preview' }
  const load = createPreviewLoader(async () => {
    if (++attempts === 1) throw new Error('offline')
    return frame
  })
  const failed = load()
  assert.equal(load(), failed)
  await assert.rejects(failed, /offline/)
  const retry = load()
  assert.notEqual(retry, failed)
  assert.equal(load(), retry)
  assert.equal(await retry, frame)
  assert.equal(await load(), frame)
  assert.equal(attempts, 2)
})

// 로더가 Promise를 반환하기 전에 던진 오류도 재시도를 막지 않는다.
test('previewLoader_whenLoaderThrowsSynchronously_allowsNextAttempt', async () => {
  let attempts = 0
  const load = createPreviewLoader(() => {
    if (++attempts === 1) throw new Error('load failed')
    return Promise.resolve('ready')
  })
  await assert.rejects(load(), /load failed/)
  assert.equal(await load(), 'ready')
})
