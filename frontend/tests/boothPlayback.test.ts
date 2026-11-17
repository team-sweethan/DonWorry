import assert from 'node:assert/strict'
import { test } from 'node:test'
import { boothPlaybackDelay, scheduleBoothPlayback } from '../src/booth/boothPlayback.ts'

test('boothPlaybackDelay_givesLongerCaptionsTimeAndHoldsEnding', () => {
  assert.equal(boothPlaybackDelay('짧은 안내'), 4000)
  assert.equal(boothPlaybackDelay('가'.repeat(46)), 6000)
  assert.equal(boothPlaybackDelay('가'.repeat(76)), 8000)
  assert.equal(boothPlaybackDelay('마무리', true), 7000)
})

test('scheduleBoothPlayback_waitsDuringLoadingOrPause', () => {
  for (const state of [
    { playing: false, ready: true, delayMs: 4000 },
    { playing: true, ready: false, delayMs: 4000 },
  ]) {
    const cleanup = scheduleBoothPlayback(
      state,
      () => assert.fail('진행하면 안 됨'),
      () => assert.fail('예약하면 안 됨'),
    )
    cleanup()
  }
})

test('scheduleBoothPlayback_pauseOrNavigationInvalidatesAlreadyQueuedCallback', () => {
  let pending = () => {}
  let cancelled = false
  let calls = 0
  const cleanup = scheduleBoothPlayback(
    { playing: true, ready: true, delayMs: 6000 },
    () => calls++,
    (callback, ms) => {
      assert.equal(ms, 6000)
      pending = callback
      return () => {
        cancelled = true
      }
    },
  )
  cleanup()
  pending()
  assert.equal(cancelled, true)
  assert.equal(calls, 0)
})

test('scheduleBoothPlayback_readyScreenAdvancesOnce_thenNewScreenGetsItsOwnTimer', () => {
  const timers: Array<() => void> = []
  const schedule = (callback: () => void) => {
    timers.push(callback)
    return () => {}
  }
  let current = 0
  const first = scheduleBoothPlayback(
    { playing: true, ready: true, delayMs: 4000 },
    () => current++,
    schedule,
  )
  timers[0]()
  timers[0]()
  assert.equal(current, 1)
  first()
  const second = scheduleBoothPlayback(
    { playing: true, ready: true, delayMs: 4000 },
    () => current++,
    schedule,
  )
  timers[1]()
  assert.equal(current, 2)
  second()
})
