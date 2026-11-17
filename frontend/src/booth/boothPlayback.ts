/** 한 박스를 읽을 시간. 긴 설명은 더 오래, 마지막 인사는 잠시 유지한 뒤 반복한다. */
export function boothPlaybackDelay(note: string, ending = false) {
  if (ending) return 3500
  if (note.length > 75) return 4000
  return note.length > 45 ? 3000 : 2000
}

type Schedule = (callback: () => void, delayMs: number) => () => void

const browserSchedule: Schedule = (callback, delayMs) => {
  const timer = window.setTimeout(callback, delayMs)
  return () => window.clearTimeout(timer)
}

/** 준비가 끝난 화면만 넘긴다. 일시정지·화면 전환 뒤 대기열에 남은 콜백도 무효로 한다. */
export function scheduleBoothPlayback(
  state: { playing: boolean; ready: boolean; delayMs: number },
  advance: () => void,
  schedule: Schedule = browserSchedule,
) {
  if (!state.playing || !state.ready) return () => {}
  let active = true
  const cancel = schedule(() => {
    if (active) {
      active = false
      advance()
    }
  }, state.delayMs)
  return () => {
    active = false
    cancel()
  }
}
