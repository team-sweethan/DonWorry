import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  createIntroVisit,
  INTRO_COUNT_MS,
  INTRO_EXIT_MS,
  playLandingIntro,
} from '../src/pages/public/introPlayback.ts'

function memoryStorage() {
  const values = new Map<string, string>()
  return {
    getItem: (key: string) => values.get(key) ?? null,
    setItem: (key: string, value: string) => void values.set(key, value),
  }
}

// 최초 렌더의 읽기는 방문을 소비하지 않고, 완료한 뒤 재방문·새로고침에서 생략한다.
test('introVisit_whenCompleted_skipsLaterVisitsInSameTab', () => {
  const storage = memoryStorage()
  const visit = createIntroVisit(
    () => storage,
    () => false,
  )
  assert.equal(visit.shouldShow(), true)
  assert.equal(visit.shouldShow(), true)
  visit.complete()
  assert.equal(visit.shouldShow(), false)
  assert.equal(
    createIntroVisit(
      () => storage,
      () => false,
    ).shouldShow(),
    false,
  )
  assert.equal(createIntroVisit(memoryStorage, () => false).shouldShow(), true)
})

// 저장소 접근이 거부돼도 화면 진입을 막거나 같은 문서 안에서 연출을 반복하지 않는다.
test('introVisit_whenStorageBlocked_remembersCompletionInMemory', () => {
  const visit = createIntroVisit(
    () => {
      throw new Error('Storage blocked')
    },
    () => false,
  )
  assert.equal(visit.shouldShow(), true)
  assert.doesNotThrow(() => visit.complete())
  assert.equal(visit.shouldShow(), false)
})

// 움직임 줄이기를 선택한 사용자는 카운터·전환 연출을 거치지 않는다.
test('introVisit_whenReducedMotion_skipsWithoutReadingStorage', () => {
  const visit = createIntroVisit(
    () => {
      assert.fail('연출 생략 시 저장소 조회 불필요')
    },
    () => true,
  )
  assert.equal(visit.shouldShow(), false)
})

function fakeClock() {
  let time = 0
  let nextId = 0
  const frames = new Map<number, (now: number) => void>()
  const timers = new Map<number, { at: number; callback: () => void }>()
  return {
    now: () => time,
    requestFrame(callback: (now: number) => void) {
      frames.set(++nextId, callback)
      return nextId
    },
    cancelFrame: (id: number) => void frames.delete(id),
    setTimer(callback: () => void, delay: number) {
      timers.set(++nextId, { at: time + delay, callback })
      return nextId
    },
    clearTimer: (id: number) => void timers.delete(id),
    advance(now: number, paint = true) {
      time = now
      for (const [id, timer] of [...timers]) {
        if (timer.at <= now) {
          timers.delete(id)
          timer.callback()
        }
      }
      if (paint) {
        const pending = [...frames.values()]
        frames.clear()
        pending.forEach((callback) => callback(time))
      }
    },
    pending: () => frames.size + timers.size,
  }
}

function playback() {
  const clock = fakeClock()
  const progress: number[] = []
  const events: string[] = []
  const callbacks = {
    progress: (value: number) => void progress.push(value),
    exit: () => void events.push('exit'),
    complete: () => void events.push('complete'),
  }
  const cancel = playLandingIntro(callbacks, clock)
  return { clock, progress, events, callbacks, cancel }
}

// 숫자는 단조 증가하고 연출 종료 시 100에 도달한 뒤 한 번만 종료한다.
test('introPlayback_whenElapsed_reaches100ThenCompletesOnce', () => {
  const { clock, progress, events } = playback()
  for (const time of [0, 200, 400, 600, INTRO_COUNT_MS - 1]) clock.advance(time)
  assert.equal(progress[0], 0)
  assert.ok(progress.slice(1).every((n, i) => n >= progress[i]! && n < 100))
  clock.advance(INTRO_COUNT_MS)
  assert.equal(progress.at(-1), 100)
  assert.deepEqual(events, ['exit'])
  clock.advance(INTRO_COUNT_MS + INTRO_EXIT_MS)
  clock.advance(5000)
  assert.deepEqual(events, ['exit', 'complete'])
  assert.equal(clock.pending(), 0)
})

// 화면 갱신이 잦아도 DOM에 전달하는 숫자 업데이트는 40ms 간격으로 제한한다.
test('introPlayback_whenFramesFrequent_limitsNumberUpdates', () => {
  const { clock, progress, cancel } = playback()
  for (let time = 0; time <= 200; time += 10) clock.advance(time)
  assert.equal(progress.length, 6)
  cancel()
  assert.equal(clock.pending(), 0)
})

// 건너뛰기·언마운트 뒤에는 예약된 프레임·타이머가 다시 화면을 바꾸지 않는다.
test('introPlayback_whenCanceled_clearsAllWorkAndDoesNotComplete', () => {
  const { clock, progress, events, cancel } = playback()
  clock.advance(100)
  cancel()
  const before = [...progress]
  clock.advance(5000)
  assert.deepEqual(progress, before)
  assert.deepEqual(events, [])
  assert.equal(clock.pending(), 0)
})

// StrictMode의 정리 후 재시작과 백그라운드 rAF 중단에도 종료 타이머가 남아 있지 않는다.
test('introPlayback_whenRestartedWithoutFrames_completesOnlyActiveRun', () => {
  const { clock, callbacks, events, cancel } = playback()
  cancel()
  playLandingIntro(callbacks, clock)
  clock.advance(INTRO_COUNT_MS + INTRO_EXIT_MS, false)
  assert.deepEqual(events, ['exit', 'complete'])
  assert.equal(clock.pending(), 0)
})
