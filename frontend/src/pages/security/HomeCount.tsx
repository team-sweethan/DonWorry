import { useLayoutEffect, useRef } from 'react'

// 화면 낭독기는 최종 건수만 읽고, 갱신 시에는 중간 숫자를 만들지 않는다.
export function HomeCount({ value }: { value: number }) {
  const node = useRef<HTMLSpanElement>(null)
  const finished = useRef(false)
  const initialValue = useRef(value)
  useLayoutEffect(() => {
    const target = node.current
    if (!target) return
    const preference = window.matchMedia('(prefers-reduced-motion: reduce)')
    const animate = !finished.current && initialValue.current === value && !preference.matches
    if (!animate || value === 0) {
      finished.current = true
      target.textContent = String(value)
      return
    }
    target.textContent = '0'
    let frame = 0
    const start = performance.now()
    const tick = (now: number) => {
      const progress = preference.matches ? 1 : Math.min((now - start) / 380, 1)
      target.textContent = String(Math.round(value * (1 - (1 - progress) ** 3)))
      if (progress < 1) frame = requestAnimationFrame(tick)
      else finished.current = true
    }
    frame = requestAnimationFrame(tick)
    return () => {
      cancelAnimationFrame(frame)
      target.textContent = String(value)
    }
  }, [value])
  return (
    <span className="security-home-count">
      <span aria-hidden="true" ref={node}>
        {value}
      </span>
      <span className="security-home-sr">{value}</span>
    </span>
  )
}
