import assert from 'node:assert/strict'
import { test } from 'node:test'
import { createOwnerIntegrationStore } from '../src/utils/ownerIntegration.ts'
import { ApiError } from '../src/api/client.ts'
import type { IntegrationInfo } from '../src/api/integration.ts'

const info = (status: IntegrationInfo['status']): IntegrationInfo => ({
  merchantCode: 'TESTSHOP',
  status,
  keyVersion: 1,
  keyIssuedAt: null,
  connectedAt: null,
  histories: [],
  totalElements: 0,
})

// 조회 실패를 키 없음으로 다루지 않고 재조회 중에는 과거 연동 완료 상태도 숨긴다.
test('ownerIntegration_whenRefreshFails_hidesPreviousStatusAndRecovers', async () => {
  let fail = false
  const store = createOwnerIntegrationStore(async () => {
    if (fail) throw new TypeError('offline')
    return info('CONNECTED')
  })
  assert.deepEqual(store.getSnapshot(), { kind: 'loading' })
  await store.refresh()
  assert.deepEqual(store.getSnapshot(), { kind: 'ready', data: info('CONNECTED') })
  fail = true
  const pending = store.refresh()
  assert.deepEqual(store.getSnapshot(), { kind: 'loading' })
  await pending
  assert.deepEqual(store.getSnapshot(), { kind: 'error', inactive: false })
  fail = false
  await store.refresh()
  assert.equal(store.getSnapshot().kind, 'ready')
})

// 이전 조회 결과와 화면 종료 후 응답은 새로운 키 상태를 덮어쓰지 않는다.
test('ownerIntegration_whenRequestsRaceOrUnmount_ignoresLateResponses', async () => {
  const requests: Array<{ resolve: (value: IntegrationInfo) => void; signal: AbortSignal }> = []
  const store = createOwnerIntegrationStore(
    (signal) => new Promise((resolve) => requests.push({ resolve, signal })),
  )
  const first = store.refresh()
  const second = store.refresh()
  assert.equal(requests[0].signal.aborted, true)
  requests[1].resolve(info('NO_KEY'))
  await second
  requests[0].resolve(info('CONNECTED'))
  await first
  assert.deepEqual(store.getSnapshot(), { kind: 'ready', data: info('NO_KEY') })
  const third = store.refresh()
  store.reset()
  requests[2].resolve(info('CONNECTED'))
  await third
  assert.deepEqual(store.getSnapshot(), { kind: 'loading' })
})

// 가맹점 정지를 통신 장애와 구분하고 실패 뒤 재조회가 가능하다.
test('ownerIntegration_whenMerchantInactive_marksRestriction', async () => {
  const store = createOwnerIntegrationStore(async () => {
    throw new ApiError(403, 'INTEGRATION_005')
  })
  await store.refresh()
  assert.deepEqual(store.getSnapshot(), { kind: 'error', inactive: true })
})
