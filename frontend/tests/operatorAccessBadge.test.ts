import assert from 'node:assert/strict'
import { afterEach, beforeEach, test } from 'node:test'
import {
  getUnreviewedOperatorAccessDetectionCount,
  reviewOperatorAccessDetection,
} from '../src/api/fds.ts'

const originalFetch = globalThis.fetch
const originalDocument = Object.getOwnPropertyDescriptor(globalThis, 'document')

beforeEach(() =>
  Object.defineProperty(globalThis, 'document', {
    configurable: true,
    value: { cookie: 'XSRF-TOKEN=operator-access-csrf' },
  }),
)

afterEach(() => {
  globalThis.fetch = originalFetch
  if (originalDocument) Object.defineProperty(globalThis, 'document', originalDocument)
  else Reflect.deleteProperty(globalThis, 'document')
})

test('미확인 고위험 탐지 수와 상세 확인 요청은 전용 API를 사용한다', async () => {
  const requests: Array<{ path: string; method?: string; cache?: RequestCache }> = []
  globalThis.fetch = async (input, init) => {
    requests.push({
      path: new URL(String(input), 'http://localhost').pathname,
      method: init?.method,
      cache: init?.cache,
    })
    return new Response(init?.method === 'POST' ? null : '3')
  }

  assert.equal(await getUnreviewedOperatorAccessDetectionCount(), 3)
  await reviewOperatorAccessDetection(17)

  assert.deepEqual(requests, [
    {
      path: '/api/fds/operator-access-detections/unreviewed-count',
      method: undefined,
      cache: 'no-store',
    },
    {
      path: '/api/fds/operator-access-detections/17/review',
      method: 'POST',
      cache: undefined,
    },
  ])
})
