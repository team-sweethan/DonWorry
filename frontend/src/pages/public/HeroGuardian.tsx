import { useEffect, useId, useRef, type CSSProperties, type RefObject } from 'react'
import '../../components/brand/brand.css'
import './HeroGuardian.css'

// 랜딩 첫 화면 연출 (#397) — 동전이가 내려와 방패를 앞으로 내려치면 육각형 방어막이 펼쳐지고,
// 이후 의심 결제 카드가 방어막에 막혀 보류되는 장면이 반복된다. 장식이므로 화면 읽기에서 뺀다.
// 움직임 줄이기 설정이면 CSS 기본값(마지막 자세)만 보이고 카드도 날아오지 않는다.

const NS = 'http://www.w3.org/2000/svg'
/** 방어막 반지름 (방어막 SVG 좌표 = 화면 px) */
const R = 215
/** 육각 칸 한 변 12 — 무늬(pattern)와 같은 크기 */
const HEX = [
  [-12, 0],
  [-6, -10.392],
  [6, -10.392],
  [12, 0],
  [6, 10.392],
  [-6, 10.392],
]
const hexPoints = (cx: number, cy: number) => HEX.map(([x, y]) => `${cx + x},${cy + y}`).join(' ')

const centers: [number, number][] = []
for (let i = -8; i <= 8; i++) {
  for (let j = -12; j <= 12; j++) {
    centers.push([12 + 36 * i, 10.392 + 20.785 * j], [30 + 36 * i, 20.785 * (j + 1)])
  }
}

/** 렌더마다 바뀌지 않는 의사 난수 (0~1) */
const rand = (i: number, salt: number) => {
  const v = Math.sin(i * 12.9898 + salt * 78.233) * 43758.5453
  return v - Math.floor(v)
}
const between = (i: number, salt: number, min: number, max: number) =>
  min + rand(i, salt) * (max - min)

/** 쾅 하는 순간 사방으로 튀는 금빛 입자 */
const burst = Array.from({ length: 18 }, (_, i) => {
  const a = (Math.PI * 2 * i) / 18 + between(i, 1, 0, 0.3)
  const d = between(i, 2, 120, 230)
  return { dx: Math.cos(a) * d, dy: Math.sin(a) * d * 0.8, size: between(i, 3, 5, 12) }
})
/** 잔잔히 떠오르는 금가루 */
const motes = Array.from({ length: 14 }, (_, i) => ({
  x: between(i, 4, 5, 95),
  size: between(i, 5, 2, 5),
  duration: between(i, 6, 3.5, 6),
  delay: between(i, 7, 2.2, 7),
}))
/** 방어막 가장자리에서 가끔 반짝이는 칸 */
const edgeCells = centers.filter(([x, y]) => {
  const r = Math.hypot(x, y)
  return r > 120 && r < 205
})
const blinkCells = Array.from({ length: 16 }, (_, i) => ({
  at: edgeCells[Math.floor(rand(i, 8) * edgeCells.length)],
  gold: i % 3 === 0,
  delay: between(i, 9, 2.4, 8),
}))

/** 쾅 하는 순간의 집중선 (안쪽 끝, 바깥 끝) */
const speedLines = Array.from({ length: 20 }, (_, i) => {
  const a = (Math.PI * 2 * i) / 20 + between(i, 10, -0.08, 0.08)
  const inner = between(i, 11, 70, 95)
  const outer = inner + between(i, 12, 60, 110)
  return [Math.cos(a) * inner, Math.sin(a) * inner, Math.cos(a) * outer, Math.sin(a) * outer]
})
/** 방어막 둘레에서 반짝이는 별 (x, y, 크기) */
const stars: [number, number, number][] = [
  [-188, -96, 9],
  [168, -132, 7],
  [204, 34, 10],
  [-206, 84, 7],
  [-84, -204, 8],
  [118, -190, 6],
]
const starPath = (x: number, y: number, r: number) => {
  const k = r * 0.28
  return `M${x} ${y - r}L${x + k} ${y - k}L${x + r} ${y}L${x + k} ${y + k}L${x} ${y + r}L${x - k} ${y + k}L${x - r} ${y}L${x - k} ${y - k}Z`
}

// 랜딩 문구와 같은 말("보류")을 쓴다. 실제 FDS 데이터와 연결하지 않은 연출용 예시다.
const holdLabels = [
  '카드 테스팅 보류',
  '고위험 국가 IP 보류',
  '다른 기기 결제 보류',
  '의심 결제 보류',
]

/** 애니메이션이 끝나거나 취소되면 요소를 지운다 */
const removeAfter = (animation: Animation, el: Element) =>
  animation.finished.then(
    () => el.remove(),
    () => el.remove(),
  )

/** 의심 결제 카드가 날아와 방어막에 막히는 장면을 반복한다. 화면에 보일 때만 돈다. */
function useBlockedCards(
  root: RefObject<HTMLDivElement | null>,
  layer: RefObject<HTMLDivElement | null>,
  hits: RefObject<SVGGElement | null>,
  shieldGlow: RefObject<SVGPathElement | null>,
) {
  useEffect(() => {
    const host = root.current
    const cards = layer.current
    const glow = hits.current
    if (!host || !cards || !glow) return
    if (window.matchMedia('(prefers-reduced-motion: reduce)').matches) return

    let timer: number | undefined
    let waveTimer: number | undefined
    let visible = false
    let alive = true
    let count = 0

    /** (x0, y0)에서 가까운 칸부터 번지듯 육각 칸이 금빛으로 번쩍인다 */
    const wave = (x0: number, y0: number, reach: number, msPerPx: number) => {
      for (const [x, y] of centers) {
        const d = Math.hypot(x - x0, y - y0)
        if (d > reach || Math.hypot(x, y) > R - 3) continue
        const cell = document.createElementNS(NS, 'polygon')
        cell.setAttribute('points', hexPoints(x, y))
        cell.setAttribute('fill', d < 22 ? '#f8e3a6' : 'rgba(248, 227, 166, 0.55)')
        cell.setAttribute('stroke', '#e9c165')
        glow.appendChild(cell)
        void removeAfter(
          cell.animate([{ opacity: 0 }, { opacity: 1, offset: 0.2 }, { opacity: 0 }], {
            duration: 900,
            delay: d * msPerPx,
            fill: 'both',
          }),
          cell,
        )
      }
    }

    const impact = (hx: number, hy: number, cx: number, cy: number) => {
      // 부딪힌 자리 육각 칸이 번쩍이고, 동전이 방패도 함께 빛난다
      wave(hx, hy, 60, 6)
      shieldGlow.current?.animate(
        [
          { opacity: 1, strokeWidth: 16 },
          { opacity: 1, strokeWidth: 7 },
        ],
        { duration: 500, easing: 'ease-out' },
      )
      const ring = document.createElementNS(NS, 'circle')
      ring.setAttribute('cx', String(hx))
      ring.setAttribute('cy', String(hy))
      ring.setAttribute('r', '10')
      ring.setAttribute('class', 'dw-g-hit-ring')
      glow.appendChild(ring)
      void removeAfter(
        ring.animate(
          [
            { transform: 'scale(0.4)', opacity: 1 },
            { transform: 'scale(5)', opacity: 0 },
          ],
          { duration: 600, easing: 'ease-out' },
        ),
        ring,
      )

      // 카드 조각
      for (let i = 0; i < 7; i++) {
        const shard = document.createElement('i')
        shard.className = 'dw-g-shard'
        cards.appendChild(shard)
        const angle = Math.atan2(cy, cx) + (Math.random() - 0.5) * 2.4
        const dist = 30 + Math.random() * 40
        const ex = hx + Math.cos(angle) * dist
        const ey = hy + Math.sin(angle) * dist + 20
        const turn = (Math.random() - 0.5) * 600
        void removeAfter(
          shard.animate(
            [
              { transform: `translate(${hx}px, ${hy}px)`, opacity: 1 },
              { transform: `translate(${ex}px, ${ey}px) rotate(${turn}deg)`, opacity: 0 },
            ],
            { duration: 650, easing: 'cubic-bezier(0.2, 0.7, 0.4, 1)' },
          ),
          shard,
        )
      }

      // "보류" 말풍선
      const tag = document.createElement('span')
      tag.className = 'dw-g-tag'
      tag.textContent = holdLabels[count % holdLabels.length]
      cards.appendChild(tag)
      const tx = hx + cx * 26
      const ty = hy + cy * 26
      const at = (dy: number, scale: number) =>
        `translate(${tx}px, ${ty + dy}px) translate(-50%, -50%) scale(${scale})`
      void removeAfter(
        tag.animate(
          [
            { transform: at(0, 0.6), opacity: 0 },
            { transform: at(-6, 1), opacity: 1, offset: 0.15 },
            { transform: at(-14, 1), opacity: 1, offset: 0.75 },
            { transform: at(-22, 1), opacity: 0 },
          ],
          { duration: 1600, easing: 'ease-out', fill: 'both' },
        ),
        tag,
      )
    }

    const spawn = () => {
      count += 1
      // 위쪽 반원과 양옆에서 날아온다 (아래 받침 쪽은 피한다)
      const side = Math.random() < 0.3 ? (Math.random() < 0.5 ? -1 : 1) * Math.PI * 0.35 : 0
      const a = -Math.PI * 0.05 - Math.random() * Math.PI * 0.9 + side
      const cx = Math.cos(a)
      const cy = Math.sin(a)
      const card = document.createElement('i')
      card.className = 'dw-g-card'
      cards.appendChild(card)
      const spin = (Math.random() - 0.5) * 80
      const flight = card.animate(
        [
          { transform: `translate(${cx * 420}px, ${cy * 420}px) rotate(${spin}deg)`, opacity: 0 },
          { opacity: 1, offset: 0.25 },
          { transform: `translate(${cx * R}px, ${cy * R}px) rotate(${spin * 0.3}deg)`, opacity: 1 },
        ],
        { duration: 750, easing: 'cubic-bezier(0.5, 0, 0.9, 0.5)' },
      )
      void removeAfter(flight, card).then(() => {
        if (alive && flight.playState === 'finished') impact(cx * R, cy * R, cx, cy)
      })
    }

    const schedule = (delay: number) => {
      timer = window.setTimeout(() => {
        if (visible && !document.hidden) spawn()
        schedule(1800 + Math.random() * 1000)
      }, delay)
    }

    const observer = new IntersectionObserver(([entry]) => {
      visible = entry.isIntersecting
    })
    observer.observe(host)
    // 방패가 땅을 치는 순간(CSS --dw-g-hit), 그 자리에서 방어막 전체로 금빛 물결이 번진다
    waveTimer = window.setTimeout(() => wave(-29, 67, 500, 2.2), 1900)
    // 등장 연출(약 3초)이 끝난 뒤부터
    schedule(3600)

    return () => {
      alive = false
      window.clearTimeout(timer)
      window.clearTimeout(waveTimer)
      observer.disconnect()
      for (const animation of host.getAnimations({ subtree: true })) {
        if (!(animation instanceof CSSAnimation)) animation.cancel()
      }
      cards.replaceChildren()
      glow.replaceChildren()
    }
  }, [root, layer, hits, shieldGlow])
}

/** 랜딩 첫 화면 오른쪽 — 방패를 든 동전이와 보안 방어막 */
export function HeroGuardian() {
  const p = `dwg${useId().replace(/[^a-zA-Z0-9]/g, '')}`
  const u = (name: string) => `url(#${p}-${name})`
  const root = useRef<HTMLDivElement>(null)
  const layer = useRef<HTMLDivElement>(null)
  const hits = useRef<SVGGElement>(null)
  const shieldGlow = useRef<SVGPathElement>(null)
  useBlockedCards(root, layer, hits, shieldGlow)

  const shieldPath = 'M130 66l30 10v24c0 22-13 36-30 44-17-8-30-22-30-44V76z'
  const hexTile = 'M0 10.392L6 0H18L24 10.392L18 20.785H6ZM24 10.392H36'
  const orbitPath = 'M-232 0a232 58 0 1 0 464 0a232 58 0 1 0-464 0'

  return (
    <div className="dw-guardian" ref={root} aria-hidden="true">
      {/* 방어막 뒤쪽: 후광 · 육각 돔 · 궤도 고리 · 바닥 받침 */}
      <svg className="dw-g-barrier" viewBox="-230 -230 460 460" focusable="false">
        <defs>
          <pattern id={`${p}-hex`} width="36" height="20.785" patternUnits="userSpaceOnUse">
            <path d={hexTile} fill="none" stroke="#4a6fd1" strokeWidth="1.1" />
          </pattern>
          <pattern id={`${p}-hexGold`} width="36" height="20.785" patternUnits="userSpaceOnUse">
            <path d={hexTile} fill="none" stroke="#f3cf6e" strokeWidth="2" />
          </pattern>
          <radialGradient id={`${p}-halo`} r="0.5">
            <stop offset="0.55" stopColor="#dfe7ff" stopOpacity="0.7" />
            <stop offset="1" stopColor="#dfe7ff" stopOpacity="0" />
          </radialGradient>
          <radialGradient id={`${p}-domeFade`} r="0.5">
            <stop offset="0" stopColor="#fff" stopOpacity="0.06" />
            <stop offset="0.6" stopColor="#fff" stopOpacity="0.22" />
            <stop offset="0.92" stopColor="#fff" stopOpacity="0.8" />
            <stop offset="1" stopColor="#fff" stopOpacity="0" />
          </radialGradient>
          <mask id={`${p}-domeMask`} maskContentUnits="userSpaceOnUse">
            <circle r={R} fill={u('domeFade')} />
          </mask>
          <radialGradient id={`${p}-domeTint`} r="0.5">
            <stop offset="0" stopColor="#e8eeff" stopOpacity="0" />
            <stop offset="0.75" stopColor="#c9d6ff" stopOpacity="0.25" />
            <stop offset="1" stopColor="#9fb4f2" stopOpacity="0.5" />
          </radialGradient>
          <radialGradient id={`${p}-spec`} cx="0.3" cy="0.22" r="0.35">
            <stop offset="0" stopColor="#fff" stopOpacity="0.7" />
            <stop offset="1" stopColor="#fff" stopOpacity="0" />
          </radialGradient>
          <linearGradient id={`${p}-rim`} x1="0" y1="0" x2="1" y2="1">
            <stop offset="0" stopColor="#f8e3a6" />
            <stop offset="0.5" stopColor="#6f8fe0" />
            <stop offset="1" stopColor="#e9c165" />
          </linearGradient>
          <linearGradient id={`${p}-scanG`} x1="0" y1="0" x2="0" y2="1">
            <stop offset="0" stopColor="#fff" stopOpacity="0" />
            <stop offset="0.5" stopColor="#fff" />
            <stop offset="1" stopColor="#fff" stopOpacity="0" />
          </linearGradient>
          <mask id={`${p}-scanMask`} maskContentUnits="userSpaceOnUse">
            <rect
              className="dw-g-scan"
              x="-230"
              y="-40"
              width="460"
              height="80"
              fill={u('scanG')}
            />
          </mask>
          {/* 방패가 땅을 친 자리에서 퍼져 나가며 생기는 방어막 */}
          <mask id={`${p}-reveal`} maskContentUnits="userSpaceOnUse">
            <circle className="dw-g-reveal" cx="-29" cy="67" r="330" fill="#fff" />
          </mask>
          <clipPath id={`${p}-dome`}>
            <circle r={R - 2} />
          </clipPath>
          <radialGradient id={`${p}-pad`} r="0.5">
            <stop offset="0" stopColor="#f8e3a6" stopOpacity="0.9" />
            <stop offset="0.6" stopColor="#9fb4f2" stopOpacity="0.35" />
            <stop offset="1" stopColor="#9fb4f2" stopOpacity="0" />
          </radialGradient>
        </defs>
        <circle className="dw-g-halo" r="250" fill={u('halo')} />
        <g mask={u('reveal')}>
          <circle r={R} fill={u('domeTint')} />
          <circle r={R} fill={u('hex')} mask={u('domeMask')} />
          <g clipPath={u('dome')}>
            <g mask={u('scanMask')}>
              <circle r={R} fill={u('hexGold')} />
            </g>
          </g>
          <g clipPath={u('dome')}>
            {blinkCells.map(({ at: [x, y], gold, delay }, i) => (
              <polygon
                key={i}
                className="dw-g-cell"
                points={hexPoints(x, y)}
                fill={gold ? 'rgba(248, 227, 166, 0.6)' : 'rgba(111, 143, 224, 0.35)'}
                style={{ animationDelay: `${delay}s` }}
              />
            ))}
          </g>
          <circle r={R} fill={u('spec')} />
        </g>
        <g ref={hits} clipPath={u('dome')} />
        <circle className="dw-g-rim" r={R} fill="none" stroke={u('rim')} strokeWidth="3" />
        <circle
          className="dw-g-rim"
          r={R + 7}
          fill="none"
          stroke="#6f8fe0"
          strokeWidth="1"
          strokeDasharray="2 6"
          opacity="0.5"
        />
        <g className="dw-g-orbit" transform="rotate(-14)">
          <ellipse
            rx="232"
            ry="58"
            fill="none"
            stroke="#6f8fe0"
            strokeWidth="1.2"
            strokeDasharray="3 7"
            opacity="0.55"
          />
          <circle className="dw-g-dot" r="4" fill="#f3cf6e">
            <animateMotion dur="5s" repeatCount="indefinite" path={orbitPath} />
          </circle>
          <circle className="dw-g-dot" r="3" fill="#6f8fe0">
            <animateMotion dur="5s" begin="-2.5s" repeatCount="indefinite" path={orbitPath} />
          </circle>
        </g>
        {/* 착지 전에 바닥에 그려지는 소환진 */}
        <g transform="translate(0 176) scale(1 0.2)">
          <circle
            className="dw-g-summon"
            r="200"
            fill="none"
            stroke="#e9c165"
            strokeWidth="5"
            pathLength={1}
            strokeDasharray="1"
          />
          <circle
            className="dw-g-summon dw-g-summon-late"
            r="174"
            fill="none"
            stroke="#6f8fe0"
            strokeWidth="4"
            pathLength={1}
            strokeDasharray="1"
          />
        </g>
        {stars.map(([x, y, size], i) => (
          <path
            key={i}
            className="dw-g-star"
            d={starPath(x, y, size)}
            fill="#fff6d6"
            stroke="#e9c165"
            strokeWidth="1"
            style={{ animationDelay: `${2.6 + i * 0.45}s` }}
          />
        ))}
        <g className="dw-g-pad" transform="translate(0 176) scale(1 0.2)">
          <circle r="200" fill={u('pad')} />
          <g className="dw-g-spin">
            <circle r="188" fill="none" stroke="#e9c165" strokeWidth="5" strokeDasharray="6 16" />
          </g>
          <g className="dw-g-spin-rev">
            <circle
              r="158"
              fill="none"
              stroke="#6f8fe0"
              strokeWidth="6"
              strokeDasharray="60 18 10 18"
            />
          </g>
          <circle r="128" fill="none" stroke="#f3cf6e" strokeWidth="3" opacity="0.8" />
        </g>
      </svg>

      <div className="dw-g-motes">
        {motes.map((m, i) => (
          <i
            key={i}
            style={{
              left: `${m.x}%`,
              width: m.size,
              height: m.size,
              animationDuration: `${m.duration}s`,
              animationDelay: `${m.delay}s`,
            }}
          />
        ))}
      </div>
      <div className="dw-g-beam" />
      <div className="dw-g-pillar" />

      {/* 동전이 */}
      <svg className="dw-g-mascot" viewBox="0 0 190 190" focusable="false">
        <defs>
          <radialGradient id={`${p}-bodyG`} cx="0.38" cy="0.3" r="0.8">
            <stop offset="0" stopColor="#fff5cf" />
            <stop offset="0.6" stopColor="#fbdc85" />
            <stop offset="1" stopColor="#efc25c" />
          </radialGradient>
          <linearGradient id={`${p}-limbG`} x1="0" y1="0" x2="0" y2="1">
            <stop offset="0" stopColor="#ffe39a" />
            <stop offset="1" stopColor="#f2c862" />
          </linearGradient>
          <linearGradient id={`${p}-navyG`} x1="0" y1="0" x2="0" y2="1">
            <stop offset="0" stopColor="#2a4590" />
            <stop offset="1" stopColor="#14224f" />
          </linearGradient>
          <linearGradient id={`${p}-goldG`} x1="0" y1="0" x2="1" y2="1">
            <stop offset="0" stopColor="#f8e3a6" />
            <stop offset="1" stopColor="#cfa24a" />
          </linearGradient>
          <clipPath id={`${p}-shield`}>
            <path d={shieldPath} />
          </clipPath>
          <filter id={`${p}-blur`} x="-50%" y="-50%" width="200%" height="200%">
            <feGaussianBlur stdDeviation="3" />
          </filter>
        </defs>
        <g className="dw-g-float">
          <g className="dw-g-descend">
            <g className="dw-g-squash">
              <ellipse cx="90" cy="176" rx="62" ry="5" fill="#13204a" opacity="0.18" />
              <ellipse
                cx="62"
                cy="142"
                rx="10"
                ry="6.4"
                fill={u('limbG')}
                stroke="#2e2a4f"
                strokeWidth="2.6"
              />
              <ellipse
                cx="94"
                cy="142"
                rx="10"
                ry="6.4"
                fill={u('limbG')}
                stroke="#2e2a4f"
                strokeWidth="2.6"
              />
              <ellipse
                cx="24"
                cy="98"
                rx="7.5"
                ry="8.5"
                transform="rotate(25 24 98)"
                fill={u('limbG')}
                stroke="#2e2a4f"
                strokeWidth="2.6"
              />
              <path
                d="M133 86c0 28-24 52-55 52"
                fill="none"
                stroke="#eebc4f"
                strokeWidth="7"
                strokeLinecap="round"
              />
              <path
                d="M78 34c33 0 55 22 55 52 0 30-24 50-55 50s-55-20-55-50c0-30 22-52 55-52z"
                fill={u('bodyG')}
                stroke="#2e2a4f"
                strokeWidth="3"
              />
              <ellipse
                cx="78"
                cy="86"
                rx="45"
                ry="42"
                fill="none"
                stroke="#e3ad45"
                strokeWidth="1.6"
              />
              <path
                d="M70 50l2.6 9 2.8-6 2.8 6 2.6-9M68.6 55h13.8"
                fill="none"
                stroke="#dca33a"
                strokeWidth="2.6"
                strokeLinecap="round"
                strokeLinejoin="round"
              />
              <path
                d="M42 56q3-9 11-12"
                fill="none"
                stroke="#fff"
                strokeWidth="4"
                strokeLinecap="round"
                opacity="0.9"
              />
              <path
                className="dw-g-brow-soft"
                d="M56 75.5l8 3.2M100 75.5l-8 3.2"
                stroke="#2e2a4f"
                strokeWidth="3.6"
                strokeLinecap="round"
              />
              <path
                className="dw-g-brow-serious"
                d="M53 71l12 6.4M103 71l-12 6.4"
                stroke="#2e2a4f"
                strokeWidth="4.4"
                strokeLinecap="round"
              />
              <g className="dw-blink">
                <ellipse cx="62" cy="89" rx="5" ry="6" fill="#2b1d12" />
                <circle cx="63.8" cy="86.8" r="2" fill="#fff" />
              </g>
              <g className="dw-blink">
                <ellipse cx="94" cy="89" rx="5" ry="6" fill="#2b1d12" />
                <circle cx="95.8" cy="86.8" r="2" fill="#fff" />
              </g>
              <ellipse cx="45" cy="104" rx="12.5" ry="8.6" fill="#ffa6a2" opacity="0.9" />
              <ellipse cx="111" cy="104" rx="12.5" ry="8.6" fill="#ffa6a2" opacity="0.9" />
              <path
                d="M74.6 103.6l3.4-2.6 3.4 2.6"
                fill="none"
                stroke="#2e2a4f"
                strokeWidth="2.4"
                strokeLinecap="round"
                strokeLinejoin="round"
              />
              <path
                className="dw-g-struggle"
                d="M124 44c0 0-5 6-5 9a5 5 0 0 0 10 0c0-3-5-9-5-9z"
                fill="#bfe3ff"
                stroke="#2e2a4f"
                strokeWidth="2"
              />
              <ellipse className="dw-g-dust" cx="40" cy="175" rx="12" ry="4.5" fill="#e9dfc2" />
              <ellipse className="dw-g-dust" cx="116" cy="175" rx="12" ry="4.5" fill="#e9dfc2" />

              {/* 방패 + 잡은 손 — 옆에서 들어 올렸다가 몸 앞으로 내려친다 */}
              <g className="dw-g-shield">
                <g transform="translate(24 8) rotate(-8 130 108)">
                  <path
                    ref={shieldGlow}
                    className="dw-g-shield-glow"
                    d={shieldPath}
                    fill="none"
                    stroke="#ffe7a0"
                    strokeWidth="7"
                    filter={u('blur')}
                  />
                  <path
                    d={shieldPath}
                    fill={u('navyG')}
                    stroke="#2e2a4f"
                    strokeWidth="3"
                    strokeLinejoin="round"
                  />
                  <path
                    d="M130 70l26 8.6v21c0 19.6-11.6 32-26 39-14.4-7-26-19.4-26-39v-21z"
                    fill="none"
                    stroke={u('goldG')}
                    strokeWidth="1.4"
                    strokeLinejoin="round"
                  />
                  <path
                    d="M130 74l23 7.6v18c0 17-10 28-23 34-13-6-23-17-23-34v-18z"
                    fill="none"
                    stroke={u('goldG')}
                    strokeWidth="2.2"
                    strokeLinejoin="round"
                  />
                  <g clipPath={u('shield')}>
                    <rect
                      className="dw-g-shine"
                      x="112"
                      y="50"
                      width="16"
                      height="110"
                      fill="#fff"
                      opacity="0.45"
                      transform="skewX(-20)"
                    />
                  </g>
                  <g className="dw-g-emblem">
                    <circle
                      cx="130"
                      cy="102"
                      r="12"
                      fill={u('goldG')}
                      stroke="#2e2a4f"
                      strokeWidth="2.2"
                    />
                    <path
                      d="M124.6 96.5l2.6 10.4 2.8-7 2.8 7 2.6-10.4M123.6 101.6h12.8"
                      fill="none"
                      stroke="#13204a"
                      strokeWidth="2.4"
                      strokeLinecap="round"
                      strokeLinejoin="round"
                    />
                  </g>
                </g>
                <ellipse
                  cx="126"
                  cy="118"
                  rx="8"
                  ry="7.2"
                  fill={u('limbG')}
                  stroke="#2e2a4f"
                  strokeWidth="2.6"
                />
                <path
                  className="dw-g-struggle"
                  d="M114 128l-4 3M118 132l-2.6 4"
                  stroke="#2e2a4f"
                  strokeWidth="1.8"
                  strokeLinecap="round"
                />
              </g>

              <path
                className="dw-g-glint"
                d="M97 80l1.6 4.4 4.4 1.6-4.4 1.6-1.6 4.4-1.6-4.4-4.4-1.6 4.4-1.6z"
                fill="#fff"
                stroke="#f8e3a6"
                strokeWidth="0.8"
              />
            </g>
          </g>
        </g>
      </svg>

      {/* 방어막 앞쪽: 바닥 테두리와 궤도 고리 앞 반쪽 (동전이 위로 겹친다) */}
      <svg className="dw-g-barrier dw-g-front" viewBox="-230 -230 460 460" focusable="false">
        <defs>
          <linearGradient id={`${p}-frontRim`} x1="0" y1="0" x2="0" y2="1">
            <stop offset="0.5" stopColor="#fff" stopOpacity="0" />
            <stop offset="0.9" stopColor="#f8e3a6" stopOpacity="0.9" />
            <stop offset="1" stopColor="#f8e3a6" stopOpacity="0" />
          </linearGradient>
        </defs>
        <g className="dw-g-pad">
          <ellipse cy="176" rx="200" ry="40" fill="none" stroke={u('frontRim')} strokeWidth="3" />
        </g>
        {/* 쾅 하는 순간 방패 자리에서 뻗는 집중선 */}
        <g transform="translate(-29 67)">
          <g className="dw-g-speed">
            {speedLines.map(([x1, y1, x2, y2], i) => (
              <line
                key={i}
                x1={x1}
                y1={y1}
                x2={x2}
                y2={y2}
                stroke={i % 2 ? '#fff' : '#f3cf6e'}
                strokeWidth={i % 2 ? 2 : 3.5}
                strokeLinecap="round"
              />
            ))}
          </g>
        </g>
        <g className="dw-g-orbit" transform="rotate(-14)">
          <path
            d="M-232 0a232 58 0 0 0 464 0"
            fill="none"
            stroke="#f3cf6e"
            strokeWidth="1.6"
            opacity="0.8"
          />
        </g>
      </svg>

      <div className="dw-g-burst-light" />
      <div className="dw-g-ring" />
      <div className="dw-g-ring dw-g-ring-blue" />
      <div className="dw-g-particles">
        {burst.map((b, i) => (
          <i
            key={i}
            style={
              {
                width: b.size,
                height: b.size,
                '--dx': `${b.dx}px`,
                '--dy': `${b.dy}px`,
              } as CSSProperties
            }
          />
        ))}
      </div>
      <div className="dw-g-cards" ref={layer} />
      <p className="dw-g-status">
        <i />
        실시간 보호 중
      </p>
    </div>
  )
}
