import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  createCaseBadgeStore,
  needActionCount,
  type CaseCounts,
  type ScopedCaseCounts,
} from '../src/utils/caseBadge.ts'

const counts = (over: Partial<CaseCounts> = {}): CaseCounts => ({
  OPEN: 0,
  INVESTIGATING: 0,
  DECISION_PENDING: 0,
  ACTION_PENDING: 0,
  ACTION_FAILED: 0,
  CLOSED: 0,
  ...over,
})

/** 내 건수와 전체 건수가 같은 응답 — 범위와 상관없는 저장소 동작을 볼 때 쓴다 */
const same = (c: CaseCounts): ScopedCaseCounts => ({ MINE: c, ALL: c })

test('처리 필요는 판정 대기 + 금융 처리 실패만 센다', () => {
  assert.equal(
    needActionCount(
      counts({
        OPEN: 5,
        INVESTIGATING: 4,
        DECISION_PENDING: 3,
        ACTION_PENDING: 2,
        ACTION_FAILED: 1,
        CLOSED: 9,
      }),
    ),
    4,
  )
  assert.equal(needActionCount(counts()), 0)
})

test('처음에는 null이고, 조회하면 숫자가 되며 구독자에게 알린다', async () => {
  const store = createCaseBadgeStore(async () => same(counts({ DECISION_PENDING: 2 })))
  let notified = 0
  store.subscribe(() => {
    notified += 1
  })
  assert.equal(store.getSnapshot(), null)
  await store.refresh()
  assert.equal(store.getSnapshot(), 2)
  assert.equal(notified, 1)
})

test('조회가 실패하면 직전 숫자를 유지한다', async () => {
  let fail = false
  const store = createCaseBadgeStore(async () => {
    if (fail) throw new Error('network')
    return same(counts({ ACTION_FAILED: 3 }))
  })
  await store.refresh()
  fail = true
  await store.refresh()
  assert.equal(store.getSnapshot(), 3)
})

test('늦게 끝난 이전 조회는 새 조회 결과를 덮지 않는다', async () => {
  const pending: Array<(c: ScopedCaseCounts) => void> = []
  const store = createCaseBadgeStore(
    () => new Promise<ScopedCaseCounts>((resolve) => pending.push(resolve)),
  )
  const first = store.refresh()
  const second = store.refresh()
  pending[1](same(counts({ DECISION_PENDING: 1 })))
  await second
  pending[0](same(counts({ DECISION_PENDING: 7 })))
  await first
  assert.equal(store.getSnapshot(), 1)
})

test('reset하면 null이 되고 그 전에 보낸 조회 결과는 버린다', async () => {
  const pending: Array<(c: ScopedCaseCounts) => void> = []
  const store = createCaseBadgeStore(
    () => new Promise<ScopedCaseCounts>((resolve) => pending.push(resolve)),
  )
  const before = store.refresh()
  store.reset()
  pending[0](same(counts({ DECISION_PENDING: 5 })))
  await before
  assert.equal(store.getSnapshot(), null)
})

test('apply는 화면이 받은 건수로 바로 맞추고, 그 전에 보낸 조회 결과가 덮지 않는다 (#553)', async () => {
  const pending: Array<(c: ScopedCaseCounts) => void> = []
  const store = createCaseBadgeStore(
    () => new Promise<ScopedCaseCounts>((resolve) => pending.push(resolve)),
  )
  const before = store.refresh()
  store.apply(same(counts({ DECISION_PENDING: 2, ACTION_FAILED: 1 })))
  assert.equal(store.getSnapshot(), 3)
  pending[0](same(counts({ DECISION_PENDING: 9 })))
  await before
  assert.equal(store.getSnapshot(), 3)
})

// #640 — 배지가 전체 직원 숫자를 쓰면 남의 사건까지 "처리 필요"로 보인다
test('배지는 조회 · apply 모두 내가 담당인 Case 건수로 센다', async () => {
  const store = createCaseBadgeStore(async () => ({
    MINE: counts({ DECISION_PENDING: 1 }),
    ALL: counts({ DECISION_PENDING: 5, ACTION_FAILED: 2 }),
  }))
  await store.refresh()
  assert.equal(store.getSnapshot(), 1)
  store.apply({ MINE: counts({ ACTION_FAILED: 2 }), ALL: counts({ DECISION_PENDING: 9 }) })
  assert.equal(store.getSnapshot(), 2)
})
