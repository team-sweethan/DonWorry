import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  createSessionActivity,
  sessionTime,
  formatRemaining,
  SESSION_TIMEOUT_MS,
  SESSION_WARNING_MS,
  SESSION_ACTIVITY_KEY,
} from '../src/api/sessionActivity.ts'

function setup() {
  let now = 1000
  let id = 0
  const values = new Map<string, string>()
  const storage = {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => {
      values.set(key, value)
    },
  }
  const tab = () =>
    createSessionActivity(
      () => now,
      () => storage,
      () => String(++id),
    )
  return {
    tab,
    values,
    advance: (ms: number) => {
      now += ms
    },
  }
}

// 예고·만료 경계와 절전 복귀는 현재 시각으로 판단한다.
test('sessionTime_whenAtBoundariesOrAfterSleep_calculatesFromCurrentTime', () => {
  const at = 1000
  const warningAt = at + SESSION_TIMEOUT_MS - SESSION_WARNING_MS
  assert.equal(sessionTime(at, warningAt - 1).warning, false)
  assert.deepEqual(sessionTime(at, warningAt), { remaining: SESSION_WARNING_MS, warning: true })
  assert.deepEqual(sessionTime(at, at + SESSION_TIMEOUT_MS - 1), { remaining: 1, warning: true })
  assert.deepEqual(sessionTime(at, at + SESSION_TIMEOUT_MS), { remaining: 0, warning: false })
  assert.deepEqual(sessionTime(at, at + 3 * SESSION_TIMEOUT_MS), { remaining: 0, warning: false })
  assert.equal(formatRemaining(1), '0:01')
  assert.equal(formatRemaining(299001), '5:00')
  assert.equal(formatRemaining(0), '0:00')
})

// 같은 탭은 즉시 알리고 다른 탭의 활동은 다시 읽어 반영한다.
test('activity_whenAnotherTabWorks_closesWarningAndUpdatesSubscribers', () => {
  const { tab, advance } = setup()
  const a = tab(),
    b = tab()
  let notices = 0
  a.subscribe(() => notices++)
  a.start(true)
  b.start()
  assert.equal(a.getSnapshot()?.id, b.getSnapshot()?.id)
  advance(SESSION_TIMEOUT_MS - SESSION_WARNING_MS)
  assert.equal(
    sessionTime(a.getSnapshot()!.at, 1000 + SESSION_TIMEOUT_MS - SESSION_WARNING_MS).warning,
    true,
  )
  b.touch(b.getSnapshot()?.id)
  a.sync()
  assert.equal(a.getSnapshot()?.at, b.getSnapshot()?.at)
  assert.equal(notices, 2)
  assert.equal(sessionTime(a.getSnapshot()!.at, a.getSnapshot()!.at).warning, false)
})

// 만료 직전에도 공유 시각을 읽어 다른 탭의 연장을 반영한다.
test('activity_whenRenewalPrecedesExpiry_usesSharedTimestamp', () => {
  const { tab, advance } = setup()
  const a = tab(),
    b = tab()
  a.start(true)
  b.start()
  const oldAt = a.getSnapshot()!.at
  advance(SESSION_TIMEOUT_MS - 1)
  b.touch(b.getSnapshot()?.id)
  advance(1)
  a.sync()
  assert.equal(sessionTime(oldAt, oldAt + SESSION_TIMEOUT_MS).remaining, 0)
  assert.equal(
    sessionTime(a.getSnapshot()!.at, oldAt + SESSION_TIMEOUT_MS).remaining,
    SESSION_TIMEOUT_MS - 1,
  )
})

// 이전 로그인의 응답·종료가 새 로그인 활동을 덮어쓰지 않는다.
test('activity_whenAnotherTabLogsIn_ignoresOldResponseAndStop', () => {
  const { tab, advance, values } = setup()
  const a = tab(),
    b = tab()
  a.start(true)
  b.start()
  const oldId = a.getSnapshot()!.id
  advance(10)
  b.start(true)
  const shared = values.get(SESSION_ACTIVITY_KEY)
  a.touch(oldId)
  assert.equal(a.getSnapshot(), null)
  a.stop()
  assert.equal(values.get(SESSION_ACTIVITY_KEY), shared)
  b.touch(oldId)
  assert.equal(values.get(SESSION_ACTIVITY_KEY), shared)
})

// 종료는 다른 탭에 전달하고 저장소가 차단돼도 현재 탭은 동작한다.
test('activity_whenStoppedOrStorageBlocked_stopsSharedOrKeepsLocalTracking', () => {
  const { tab } = setup()
  const a = tab(),
    b = tab()
  a.start(true)
  b.start()
  a.stop()
  b.sync()
  assert.equal(b.getSnapshot(), null)
  let now = 100
  const blocked = createSessionActivity(
    () => now,
    () => {
      throw new Error('blocked')
    },
    () => 'local',
  )
  blocked.start()
  now = 200
  blocked.touch('local')
  blocked.sync()
  assert.equal(blocked.getSnapshot()?.at, 200)
  blocked.stop()
  assert.equal(blocked.getSnapshot(), null)
})

// 잘못된 저장 값·미래 시각을 무시하고 추적 전 응답으로 시작하지 않는다.
test('activity_whenStorageMalformedOrInactive_doesNotAdoptInvalidActivity', () => {
  const { tab, values } = setup()
  const a = tab()
  a.touch(undefined)
  assert.equal(a.getSnapshot(), null)
  for (const value of ['{', 'null', '{}', '{"id":"future","at":999999,"ended":false}']) {
    values.set(SESSION_ACTIVITY_KEY, value)
    // 각 손상 값은 종료 이력이 없는 새 탭에서 검사한다.
    const fresh = tab()
    fresh.start()
    assert.notEqual(fresh.getSnapshot()?.id, 'future')
    assert.equal(fresh.getSnapshot()?.at, 1000)
    fresh.stop()
  }
})

// 이전 형식의 종료 표식도 만료로 읽어 자동 복원을 허용하지 않는다.
test('activity_whenLegacyEndedMarker_treatsItAsExpiry', () => {
  const { tab, values } = setup()
  values.set(SESSION_ACTIVITY_KEY, JSON.stringify({ id: 'legacy', at: 1000, ended: true }))
  const activity = tab()
  assert.equal(activity.endReason(), 'expired')
  assert.equal(activity.liveId(), null)
})

// 최초 확인의 명시적 참여만 허용하고 인증 성공 전에는 시각·공유 표식을 바꾸지 않는다.
test('activity_whenInitialMeJoinsSharedTracking_preservesTimestampAndId', () => {
  const { tab, values, advance } = setup()
  const a = tab(),
    b = tab()
  a.start(true)
  const shared = values.get(SESSION_ACTIVITY_KEY)
  advance(100)
  b.beginRequest()()
  assert.equal(b.getSnapshot(), null)
  const finish = b.beginRequest(true)
  assert.equal(b.getSnapshot()?.id, a.getSnapshot()?.id)
  assert.equal(b.getSnapshot()?.at, a.getSnapshot()?.at)
  assert.equal(values.get(SESSION_ACTIVITY_KEY), shared)
  assert.equal(b.hasPendingRequests(), true)
  finish()
  assert.equal(b.hasPendingRequests(), false)
})

// 종료된 추적은 최초 요청의 참여로 재생성하지 않고 실제 새 로그인만 참여시킨다.
test('activity_whenEndedTrackingExists_rejectsInitialRequestJoin', () => {
  for (const reason of ['expired', 'logout'] as const) {
    const { tab, values } = setup()
    const a = tab(),
      b = tab()
    a.start(true)
    a.stop(reason)
    const shared = values.get(SESSION_ACTIVITY_KEY)
    b.beginRequest(true)()
    assert.equal(b.getSnapshot(), null)
    assert.equal(values.get(SESSION_ACTIVITY_KEY), shared)
    assert.equal(b.start(), false)
    assert.equal(values.get(SESSION_ACTIVITY_KEY), shared)
    a.start(true)
    const finish = b.beginRequest(true)
    assert.equal(b.getSnapshot()?.id, a.getSnapshot()?.id)
    finish()
  }
  const local = createSessionActivity(
    () => 1000,
    () => {
      throw new Error('blocked')
    },
    () => 'local',
  )
  local.start(true)
  local.stop()
  assert.equal(local.start(), false)
  assert.equal(local.start(true), true)
})
