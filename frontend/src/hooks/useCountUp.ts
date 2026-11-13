import { useEffect, useState } from 'react'

const reducedMotion = () =>
  typeof window !== 'undefined' && window.matchMedia?.('(prefers-reduced-motion: reduce)').matches

/**
 * 처음 보이는 숫자만 0에서 목표값까지 올라간다 (시연용 연출).
 * - 한 번 다 센 뒤 새로고침으로 값이 바뀌면 다시 세지 않고 바로 바꾼다 — 갱신마다 연출을 반복하지 않는다
 * - 동작 줄이기 설정이면 바로 정확한 값을 보여준다
 * - 화면 읽기 프로그램에는 정확한 금액을 주도록 쓰는 쪽에서 aria-label을 붙인다
 */
export function useCountUp(target: number | null | undefined, duration = 900, delay = 0) {
  const [played, setPlayed] = useState(false)
  const [frame, setFrame] = useState<{ target: number; value: number } | null>(null)
  const ready = target !== null && target !== undefined
  useEffect(() => {
    // 동작 줄이기는 렌더에서 바로 목표값을 보여준다
    if (!ready || played || reducedMotion()) return
    let id = 0
    let start: number | null = null
    const step = (now: number) => {
      start ??= now
      const x = Math.min(1, Math.max(0, (now - start - delay) / duration))
      if (x >= 1) {
        setPlayed(true)
        setFrame(null)
        return
      }
      setFrame({ target, value: Math.round(target * (1 - Math.pow(1 - x, 3))) })
      id = requestAnimationFrame(step)
    }
    id = requestAnimationFrame(step)
    return () => cancelAnimationFrame(id)
  }, [ready, played, target, duration, delay])
  if (!ready) return null
  if (played || reducedMotion()) return target
  // 세는 중인 값은 같은 목표값일 때만 쓴다. 첫 프레임 전에는 0을 보여 금액이 번쩍이지 않게 한다
  return frame?.target === target ? frame.value : 0
}
