import { useEffect, useRef } from 'react'
import type { LoginSecurityState } from '../../api/loginSecurity'
import { type Launch, type LaunchKind, stateWord } from './loginSecurityTimeline'

/** 레이더 위에 잠깐 띄울 문구 — 새로 보인 사건 하나당 한 번 */
export type RadarNotice = { key: string; text: string; tone: 'alert' | 'takeover' | 'block' }

// 관제 칸이라 앱 테마와 상관없이 어둡게 그린다. 색은 범례와 같다
const DOT: Record<LaunchKind, string> = {
  unknown: '#ff5d5d',
  fail: '#ff9f43',
  ok: '#4ade80',
  suspected: '#ffe066',
}
const RING: Record<LoginSecurityState, string> = {
  NORMAL: '#4ade80',
  CAUTION: '#fbbf24',
  UNDER_ATTACK: '#ff5d5d',
}
const NOTICE_COLOR: Record<RadarNotice['tone'], string> = {
  alert: '#ff5d5d',
  takeover: '#ffe066',
  block: '#ff5d5d',
}
const FONT = "'Pretendard Variable', Pretendard, -apple-system, 'Segoe UI', sans-serif"

type Particle = { spawn: number; arrive: number; angle: number; kind: LaunchKind }
type Impact = { at: number; angle: number; kind: LaunchKind }
type Label = { at: number; text: string; color: string; dur: number; block: boolean }

/**
 * 로그인 시도 레이더 (#788) — 시도 하나가 점 하나로 가장자리에서 가운데 DonWorry로 날아온다.
 * 방향은 무작위다(IP · 위치가 아님 — 탐지는 IP를 보지 않는다). 가운데 테두리 색은 상태(평소 · 주의 · 경계)를 따른다.
 */
export function LoginAttackRadar({
  state,
  launches,
  notices,
}: {
  state: LoginSecurityState
  launches: Launch[]
  notices: RadarNotice[]
}) {
  const canvasRef = useRef<HTMLCanvasElement>(null)
  const rateRef = useRef<HTMLSpanElement>(null)
  const particles = useRef<Particle[]>([])
  const labels = useRef<Label[]>([])
  const stateRef = useRef(state)

  useEffect(() => {
    stateRef.current = state
  }, [state])

  const reduce =
    typeof window !== 'undefined' && window.matchMedia('(prefers-reduced-motion: reduce)').matches
  const flight = reduce ? 200 : 950

  useEffect(() => {
    const now = performance.now()
    const wall = Date.now()
    for (const l of launches) {
      // 브라우저 시계(Date) 기준 도착 시각을 그림 시계(performance)로 옮긴다
      const arrive = now + (l.arriveAt - wall)
      particles.current.push({
        spawn: Math.max(now, arrive - flight),
        arrive,
        angle: Math.random() * Math.PI * 2,
        kind: l.kind,
      })
    }
  }, [launches, flight])

  useEffect(() => {
    const now = performance.now()
    for (const n of notices) {
      labels.current.push({
        at: now,
        text: n.text,
        color: NOTICE_COLOR[n.tone],
        dur: n.tone === 'block' ? 5_000 : 4_000,
        block: n.tone === 'block',
      })
    }
  }, [notices])

  useEffect(() => {
    const canvas = canvasRef.current
    const ctx = canvas?.getContext('2d')
    if (!canvas || !ctx) return
    let w = 0
    let h = 0
    const resize = () => {
      const r = canvas.getBoundingClientRect()
      const dpr = Math.min(2, window.devicePixelRatio || 1)
      w = r.width
      h = r.height
      canvas.width = Math.round(w * dpr)
      canvas.height = Math.round(h * dpr)
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
    }
    const observer = new ResizeObserver(resize)
    observer.observe(canvas)
    resize()

    let impacts: Impact[] = []
    let arrivals: number[] = []
    let raf = 0

    const shield = (cx: number, cy: number, s: number) => {
      ctx.beginPath()
      ctx.moveTo(cx, cy - s)
      ctx.lineTo(cx + s * 0.82, cy - s * 0.62)
      ctx.lineTo(cx + s * 0.78, cy + s * 0.05)
      ctx.quadraticCurveTo(cx + s * 0.7, cy + s * 0.7, cx, cy + s)
      ctx.quadraticCurveTo(cx - s * 0.7, cy + s * 0.7, cx - s * 0.78, cy + s * 0.05)
      ctx.lineTo(cx - s * 0.82, cy - s * 0.62)
      ctx.closePath()
    }

    const frame = () => {
      raf = requestAnimationFrame(frame)
      if (!w || !h) return
      const now = performance.now()
      const st = stateRef.current
      const ring = RING[st]
      const cx = w / 2
      const cy = h / 2 - 6
      const r0 = Math.min(w, h) * 0.16
      const rOut = Math.hypot(w, h) / 2 + 10

      const bg = ctx.createRadialGradient(cx, cy, r0 * 0.5, cx, cy, rOut)
      bg.addColorStop(0, st === 'UNDER_ATTACK' ? '#2a0d1a' : '#0d1838')
      bg.addColorStop(1, '#050a1c')
      ctx.fillStyle = bg
      ctx.fillRect(0, 0, w, h)
      ctx.strokeStyle = 'rgba(120,150,255,.09)'
      ctx.lineWidth = 1
      for (let k = 1; k <= 4; k++) {
        ctx.beginPath()
        ctx.arc(cx, cy, r0 * (1 + k * 0.8), 0, Math.PI * 2)
        ctx.stroke()
      }
      for (let k = 0; k < 12; k++) {
        const a = (k * Math.PI) / 6
        ctx.beginPath()
        ctx.moveTo(cx + Math.cos(a) * r0 * 1.15, cy + Math.sin(a) * r0 * 1.15)
        ctx.lineTo(cx + Math.cos(a) * rOut, cy + Math.sin(a) * rOut)
        ctx.stroke()
      }

      // 날아오는 점
      particles.current = particles.current.filter((p) => {
        if (now < p.spawn) return true
        const span = Math.max(1, p.arrive - p.spawn)
        const k = Math.min(1, (now - p.spawn) / span)
        if (k >= 1) {
          impacts.push({ at: now, angle: p.angle, kind: p.kind })
          arrivals.push(now)
          return false
        }
        const ease = k * k * (3 - 2 * k)
        const startR = span < flight ? r0 + (rOut - r0) * (span / flight) : rOut
        const r = startR + (r0 - startR) * ease
        const color = DOT[p.kind]
        const px = cx + Math.cos(p.angle) * r
        const py = cy + Math.sin(p.angle) * r
        if (!reduce) {
          const tail = Math.min(70, startR - r + 4)
          const tx = cx + Math.cos(p.angle) * (r + tail)
          const ty = cy + Math.sin(p.angle) * (r + tail)
          const grad = ctx.createLinearGradient(tx, ty, px, py)
          grad.addColorStop(0, 'rgba(0,0,0,0)')
          grad.addColorStop(1, color)
          ctx.strokeStyle = grad
          ctx.globalAlpha = 0.85
          ctx.lineWidth = p.kind === 'suspected' ? 3 : 2
          ctx.beginPath()
          ctx.moveTo(tx, ty)
          ctx.lineTo(px, py)
          ctx.stroke()
        }
        ctx.globalAlpha = 1
        ctx.fillStyle = color
        ctx.beginPath()
        ctx.arc(px, py, p.kind === 'suspected' ? 5 : 3, 0, Math.PI * 2)
        ctx.fill()
        return true
      })

      // 가운데에 닿은 자리
      impacts = impacts.filter((im) => {
        const age = now - im.at
        const life = im.kind === 'suspected' ? 1_600 : 650
        if (age > life) return false
        const k = age / life
        ctx.globalAlpha = (1 - k) * 0.9
        ctx.strokeStyle = DOT[im.kind]
        ctx.lineWidth = im.kind === 'suspected' ? 3 : 2
        ctx.beginPath()
        if (im.kind === 'suspected') ctx.arc(cx, cy, r0 * (1 + k * 1.6), 0, Math.PI * 2)
        else if (im.kind === 'ok') ctx.arc(cx, cy, r0 * (1 - k * 0.25), 0, Math.PI * 2)
        else ctx.arc(cx, cy, r0 + 2 + k * 14, im.angle - 0.22, im.angle + 0.22)
        ctx.stroke()
        ctx.globalAlpha = 1
        return true
      })

      // 가운데 DonWorry
      const pulse = st === 'UNDER_ATTACK' && !reduce ? Math.sin(now / 180) * 3 : 0
      const blockOn = labels.current.some((l) => l.block && now - l.at < 1_800)
      ctx.save()
      ctx.shadowColor = ring
      ctx.shadowBlur = st === 'NORMAL' ? 8 : 22
      ctx.strokeStyle = ring
      ctx.lineWidth = 3
      ctx.beginPath()
      ctx.arc(cx, cy, r0 + pulse, 0, Math.PI * 2)
      ctx.stroke()
      ctx.restore()
      ctx.fillStyle =
        blockOn && Math.floor(now / 200) % 2 ? 'rgba(255,93,93,.35)' : 'rgba(10,20,50,.85)'
      ctx.beginPath()
      ctx.arc(cx, cy, r0 - 2, 0, Math.PI * 2)
      ctx.fill()
      shield(cx, cy - r0 * 0.12, r0 * 0.5)
      ctx.fillStyle = '#1d4ed8'
      ctx.fill()
      ctx.lineWidth = 2
      ctx.strokeStyle = '#93c5fd'
      ctx.stroke()
      ctx.fillStyle = '#fff'
      ctx.textAlign = 'center'
      ctx.textBaseline = 'middle'
      ctx.font = `700 ${Math.max(11, r0 * 0.2)}px ${FONT}`
      ctx.fillText('D', cx, cy - r0 * 0.14)
      ctx.font = `700 ${Math.max(12, r0 * 0.17)}px ${FONT}`
      ctx.fillText('DonWorry', cx, cy + r0 * 0.6)
      ctx.font = `600 ${Math.max(12, r0 * 0.16)}px ${FONT}`
      ctx.fillStyle = ring
      ctx.fillText(stateWord[st], cx, cy + r0 + 22)

      // 사건 문구 — 새 것이 아래(가운데 가까이)
      labels.current = labels.current.filter((l) => now - l.at < l.dur)
      labels.current.forEach((l, i, all) => {
        const age = now - l.at
        ctx.globalAlpha = Math.min(1, (l.dur - age) / 600)
        ctx.font = `700 ${w < 500 ? 14 : 16}px ${FONT}`
        const tw = ctx.measureText(l.text).width + 24
        const y = cy - r0 - 34 - (all.length - 1 - i) * 36
        ctx.fillStyle = 'rgba(5,10,28,.85)'
        ctx.strokeStyle = l.color
        ctx.lineWidth = 1.5
        ctx.beginPath()
        ctx.roundRect(cx - tw / 2, y - 15, tw, 30, 6)
        ctx.fill()
        ctx.stroke()
        ctx.fillStyle = l.color
        ctx.fillText(l.text, cx, y)
        ctx.globalAlpha = 1
      })

      arrivals = arrivals.filter((t) => now - t < 2_000)
      if (rateRef.current) rateRef.current.textContent = (arrivals.length / 2).toFixed(1)
    }
    raf = requestAnimationFrame(frame)
    return () => {
      cancelAnimationFrame(raf)
      observer.disconnect()
    }
  }, [flight, reduce])

  return (
    <section className="ls-radar" aria-label="로그인 시도 레이더">
      <canvas ref={canvasRef} className="ls-radar-canvas" aria-hidden="true" />
      <span className="ls-radar-title">지금 들어오는 로그인</span>
      <span className="ls-radar-rate">
        <strong ref={rateRef}>0.0</strong>
        초당 시도
      </span>
      <div className="ls-radar-foot">
        <span>점 하나 = 로그인 시도 하나 · 날아오는 방향은 의미 없음 (IP · 위치 아님)</span>
        <span className="ls-radar-legend">
          <span className="is-unknown">없는 아이디 실패</span>
          <span className="is-fail">있는 계정 실패</span>
          <span className="is-ok">성공</span>
          <span className="is-suspected">탈취 의심</span>
        </span>
      </div>
    </section>
  )
}
