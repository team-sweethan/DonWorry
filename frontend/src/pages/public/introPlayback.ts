export const INTRO_COUNT_MS = 1680
export const INTRO_EXIT_MS = 320
const INTRO_KEY = 'donworry.ui.landingIntroSeen'

type IntroStorage = Pick<Storage, 'getItem' | 'setItem'>

// 저장소가 막혀 있어도 같은 문서 안에서 메뉴 이동으로 연출을 반복하지 않는다.
export function createIntroVisit(storage: () => IntroStorage, reducedMotion: () => boolean) {
  let seen = false
  return {
    shouldShow() {
      if (seen || reducedMotion()) return false
      try {
        return storage().getItem(INTRO_KEY) !== '1'
      } catch {
        return true
      }
    },
    complete() {
      seen = true
      try {
        storage().setItem(INTRO_KEY, '1')
      } catch {
        // 저장에 실패해도 메모리에는 이번 방문을 기억한다.
      }
    },
  }
}

type IntroClock = {
  now: () => number
  requestFrame: (callback: (time: number) => void) => number
  cancelFrame: (id: number) => void
  setTimer: (callback: () => void, delay: number) => number
  clearTimer: (id: number) => void
}

type IntroCallbacks = {
  progress: (value: number) => void
  exit: () => void
  complete: () => void
}

// 숫자는 진입 연출이다. 실제 네트워크 진행률을 측정하거나 서버를 조회하지 않는다.
export function playLandingIntro(callbacks: IntroCallbacks, clock: IntroClock) {
  const startedAt = clock.now()
  let lastPaint = -Infinity
  let active = true
  let counting = true
  let frame = 0
  const update = (now: number) => {
    if (!active || !counting) return
    if (now - lastPaint >= 40) {
      const elapsed = Math.min(1, Math.max(0, (now - startedAt) / INTRO_COUNT_MS))
      callbacks.progress(Math.min(99, Math.round((1 - (1 - elapsed) ** 3) * 100)))
      lastPaint = now
    }
    frame = clock.requestFrame(update)
  }
  frame = clock.requestFrame(update)
  const exitTimer = clock.setTimer(() => {
    if (!active) return
    counting = false
    clock.cancelFrame(frame)
    callbacks.progress(100)
    callbacks.exit()
  }, INTRO_COUNT_MS)
  // 백그라운드 탭에서 rAF가 멈춰도 복귀 후 오버레이에 갇히지 않는다.
  const finishTimer = clock.setTimer(() => {
    if (!active) return
    active = false
    clock.cancelFrame(frame)
    callbacks.complete()
  }, INTRO_COUNT_MS + INTRO_EXIT_MS)
  return () => {
    active = false
    clock.cancelFrame(frame)
    clock.clearTimer(exitTimer)
    clock.clearTimer(finishTimer)
  }
}
