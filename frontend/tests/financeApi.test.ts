import assert from 'node:assert/strict'
import { beforeEach, afterEach, test } from 'node:test'
import { ApiError } from '../src/api/client.ts'
import {
  completePlannedExpense,
  completeRecurringOccurrence,
  createPlannedExpense,
  createRecurringExpense,
  deletePlannedExpense,
  getCashFlowForecast,
  getCashFlowSettings,
  getPlannedExpenses,
  getRecurringExpenses,
  getRecurringOccurrences,
  getSettlementReport,
  saveCashFlowSettings,
  stopRecurringExpense,
  updatePlannedExpense,
} from '../src/api/finance.ts'
const originalFetch = globalThis.fetch
const originalDocument = Object.getOwnPropertyDescriptor(globalThis, 'document')
const originalStorage = Object.getOwnPropertyDescriptor(globalThis, 'localStorage')
beforeEach(() =>
  Object.defineProperty(globalThis, 'document', {
    configurable: true,
    value: { cookie: 'XSRF-TOKEN=finance-token' },
  }),
)
afterEach(() => {
  globalThis.fetch = originalFetch
  if (originalDocument) Object.defineProperty(globalThis, 'document', originalDocument)
  else Reflect.deleteProperty(globalThis, 'document')
  if (originalStorage) Object.defineProperty(globalThis, 'localStorage', originalStorage)
  else Reflect.deleteProperty(globalThis, 'localStorage')
})
test('금융 조회는 쿠키·no-store·취소 신호와 날짜를 전달한다', async () => {
  const paths: string[] = []
  const controller = new AbortController()
  globalThis.fetch = async (input, init) => {
    paths.push(String(input))
    assert.equal(init?.credentials, 'include')
    assert.equal(init?.cache, 'no-store')
    assert.ok(init?.signal)
    return Response.json([])
  }
  await getCashFlowSettings(controller.signal)
  await getCashFlowForecast('2026-11-02', '2026-11-08', controller.signal)
  await getSettlementReport('2026-11-01', '2026-11-30')
  await getPlannedExpenses('2026-11-01', '2026-11-30')
  await getRecurringExpenses()
  await getRecurringOccurrences('2026-11-01', '2026-11-30')
  assert.deepEqual(paths, [
    '/api/v1/settlements/cash-flow/settings',
    '/api/v1/settlements/cash-flow/forecast?from=2026-11-02&to=2026-11-08',
    '/api/v1/settlements/report?from=2026-11-01&to=2026-11-30',
    '/api/v1/settlements/cash-flow/expenses?from=2026-11-01&to=2026-11-30',
    '/api/v1/settlements/cash-flow/recurring-expenses',
    '/api/v1/settlements/cash-flow/recurring-expenses/occurrences?from=2026-11-01&to=2026-11-30',
  ])
})
test('설정 미등록 404만 최초 설정으로 처리하고 API 비활성·권한·서버 오류는 전파한다', async () => {
  globalThis.fetch = async () =>
    Response.json({ code: 'SETTLEMENT_SETTING_REQUIRED' }, { status: 404 })
  assert.equal(await getCashFlowSettings(), null)
  for (const status of [401, 403, 404, 503]) {
    globalThis.fetch = async () => Response.json({ code: 'OTHER', detail: '실패' }, { status })
    await assert.rejects(getCashFlowSettings(), (e) => e instanceof ApiError && e.status === status)
  }
})
test('기록 변경은 CSRF를 사용하고 회차 완료에는 실제 완료일 대신 원래 예정일을 보낸다', async () => {
  const calls: { path: string; init: RequestInit }[] = []
  globalThis.fetch = async (input, init) => {
    calls.push({ path: String(input), init: init ?? {} })
    return new Response(null, { status: 204 })
  }
  const expense = {
    title: '임대료',
    expenseDate: '2026-12-31',
    amount: 100,
    category: 'RENT' as const,
    memo: null,
  }
  await saveCashFlowSettings({ openingBalance: 0, baseDate: '2026-11-02' })
  await createPlannedExpense(expense)
  await updatePlannedExpense(7, expense)
  await deletePlannedExpense(7)
  await completePlannedExpense(7)
  await createRecurringExpense({
    title: '급여',
    amount: 200,
    category: 'PAYROLL',
    memo: null,
    frequency: 'MONTHLY',
    startDate: '2026-11-30',
    endDate: null,
  })
  await stopRecurringExpense(8)
  await completeRecurringOccurrence(8, '2026-12-31')
  await completeRecurringOccurrence(8, '2026-12-31')
  assert.deepEqual(
    calls.map((c) => c.init.method),
    ['PUT', 'POST', 'PUT', 'DELETE', 'PUT', 'POST', 'DELETE', 'PUT', 'PUT'],
  )
  for (const c of calls) {
    assert.equal(new Headers(c.init.headers).get('X-XSRF-TOKEN'), 'finance-token')
    assert.equal(c.init.credentials, 'include')
  }
  assert.equal(
    calls[7].path,
    '/api/v1/settlements/cash-flow/recurring-expenses/8/occurrences/2026-12-31/completion',
  )
  assert.equal(calls[8].path, calls[7].path)
  assert.equal(calls[7].init.body, undefined)
  assert.deepEqual(JSON.parse(String(calls[1].init.body)), expense)
})
test('POST 네트워크 오류는 자동 재전송하거나 예시 저장 결과로 바꾸지 않는다', async () => {
  let calls = 0
  globalThis.fetch = async () => {
    calls++
    throw new TypeError('offline')
  }
  await assert.rejects(
    createPlannedExpense({
      title: '재료',
      expenseDate: '2026-11-02',
      amount: 1,
      category: 'MATERIAL',
      memo: null,
    }),
  )
  assert.equal(calls, 1)
})
test('취소 또는 계정 변경 뒤 도착한 읽기 결과는 화면 데이터로 반환하지 않는다', async () => {
  const controller = new AbortController()
  globalThis.fetch = async () => {
    controller.abort()
    return Response.json({})
  }
  await assert.rejects(
    getCashFlowForecast('2026-11-02', '2026-11-02', controller.signal),
    (e) => e instanceof DOMException && e.name === 'AbortError',
  )
  let version = 'first'
  Object.defineProperty(globalThis, 'localStorage', {
    configurable: true,
    value: {
      setItem() {},
      removeItem() {},
      getItem() {
        return version
      },
    },
  })
  globalThis.fetch = async () => {
    version = 'second'
    return Response.json({})
  }
  await assert.rejects(
    getCashFlowSettings(),
    (e) => e instanceof DOMException && e.name === 'AbortError',
  )
})
