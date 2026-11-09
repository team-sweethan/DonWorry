import { useEffect, useRef } from 'react'
import { BrandMark } from '../../components/brand/BrandMark'
import { playLandingIntro } from './introPlayback'
import './LandingIntro.css'

export function LandingIntro({ onComplete }: { onComplete: () => void }) {
  const overlay = useRef<HTMLDivElement>(null)
  const count = useRef<HTMLSpanElement>(null)
  const fill = useRef<HTMLSpanElement>(null)
  const skip = useRef<HTMLButtonElement>(null)
  useEffect(() => {
    const previousOverflow = document.documentElement.style.overflow
    document.documentElement.style.overflow = 'hidden'
    skip.current?.focus({ preventScroll: true })
    const cancel = playLandingIntro(
      {
        progress(value) {
          if (count.current) count.current.textContent = String(value).padStart(2, '0')
          if (fill.current) fill.current.style.transform = `scaleX(${value / 100})`
        },
        exit() {
          overlay.current?.classList.add('is-leaving')
        },
        complete: onComplete,
      },
      {
        now: () => performance.now(),
        requestFrame: (callback) => window.requestAnimationFrame(callback),
        cancelFrame: (id) => window.cancelAnimationFrame(id),
        setTimer: (callback, delay) => window.setTimeout(callback, delay),
        clearTimer: (id) => window.clearTimeout(id),
      },
    )
    const motion = window.matchMedia('(prefers-reduced-motion: reduce)')
    const stopForMotion = () => {
      if (motion.matches) onComplete()
    }
    motion.addEventListener('change', stopForMotion)
    stopForMotion()
    return () => {
      cancel()
      document.documentElement.style.overflow = previousOverflow
      motion.removeEventListener('change', stopForMotion)
    }
  }, [onComplete])
  return (
    <div
      className="dw-intro"
      ref={overlay}
      role="dialog"
      aria-modal="true"
      aria-labelledby="dw-intro-title"
      onKeyDown={(event) => {
        if (event.key === 'Escape') onComplete()
        if (event.key === 'Tab') {
          event.preventDefault()
          skip.current?.focus()
        }
      }}
    >
      <div className="dw-intro-brand">
        <BrandMark size={40} />
        <span>돈워리</span>
      </div>
      <div className="dw-intro-center">
        <p id="dw-intro-title">걱정은 덜고, 가게에 집중하세요.</p>
        <div className="dw-intro-count" aria-hidden="true">
          <span ref={count}>00</span>
          <span className="dw-intro-percent">%</span>
        </div>
        <div className="dw-intro-track" aria-hidden="true">
          <span ref={fill} />
        </div>
      </div>
      <button type="button" className="dw-intro-skip" ref={skip} onClick={onComplete}>
        바로 시작하기 <span aria-hidden="true">↗</span>
      </button>
    </div>
  )
}
