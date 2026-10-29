import { useId } from 'react'
import './brand.css'

// 돈워리 캐릭터 "동전이" — 몸보다 큰 방패를 끙끙 들고 있는 ₩ 동전 (랜딩 개편 최종안).
// 한 화면에 여러 개 그려도 그라데이션이 섞이지 않게 id 앞에 인스턴스마다 다른 값을 붙인다.
function useSvgIds() {
  const p = `dw${useId().replace(/[^a-zA-Z0-9]/g, '')}`
  return { p, u: (name: string) => `url(#${p}-${name})` }
}

function Defs({ p }: { p: string }) {
  return (
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
    </defs>
  )
}

/** 로고 — 방패를 든 동전이 얼굴. 네이비 둥근 사각 배경과 금 테두리까지 포함한다. */
export function BrandMark({ size = 32, className }: { size?: number; className?: string }) {
  const { p, u } = useSvgIds()
  return (
    <svg
      className={className}
      width={size}
      height={size}
      viewBox="0 0 64 64"
      aria-hidden="true"
      focusable="false"
    >
      <Defs p={p} />
      <rect width="64" height="64" rx="16" fill={u('navyG')} />
      <rect
        x="2"
        y="2"
        width="60"
        height="60"
        rx="14.5"
        fill="none"
        stroke={u('goldG')}
        strokeWidth="1.2"
        opacity="0.7"
      />
      <path
        d="M29 11c13.5 0 22 9.4 22 21.6S41.4 53 29 53 7 44.8 7 32.6 15.5 11 29 11z"
        fill={u('bodyG')}
        stroke="#2e2a4f"
        strokeWidth="2.4"
      />
      <ellipse cx="29" cy="32.6" rx="18" ry="17.4" fill="none" stroke="#e3ad45" strokeWidth="1.2" />
      <path
        d="M19.6 25l4.6 1.8M38.4 25l-4.6 1.8"
        stroke="#2e2a4f"
        strokeWidth="2.4"
        strokeLinecap="round"
      />
      <g className="dw-blink">
        <ellipse cx="22.8" cy="31.6" rx="2.7" ry="3.3" fill="#2b1d12" />
        <circle cx="23.8" cy="30.4" r="1.1" fill="#fff" />
      </g>
      <g className="dw-blink">
        <ellipse cx="35.2" cy="31.6" rx="2.7" ry="3.3" fill="#2b1d12" />
        <circle cx="36.2" cy="30.4" r="1.1" fill="#fff" />
      </g>
      <ellipse cx="15" cy="39" rx="5.4" ry="3.8" fill="#ffa6a2" opacity="0.92" />
      <ellipse cx="43" cy="39" rx="5.4" ry="3.8" fill="#ffa6a2" opacity="0.92" />
      <path
        d="M27.2 39.4l1.8-1.4 1.8 1.4"
        fill="none"
        stroke="#2e2a4f"
        strokeWidth="1.7"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
      <path
        d="M47 33l11 3.6v7c0 6-4.6 9.6-11 11.4-6.4-1.8-11-5.4-11-11.4v-7z"
        fill={u('navyG')}
        stroke="#2e2a4f"
        strokeWidth="2.2"
        strokeLinejoin="round"
      />
      <circle cx="47" cy="44" r="4.8" fill={u('goldG')} stroke="#2e2a4f" strokeWidth="1.5" />
      <path
        d="M44.7 41.7l1 4.2 1.3-2.9 1.3 2.9 1-4.2M44.3 43.8h5.4"
        fill="none"
        stroke="#13204a"
        strokeWidth="1.2"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
      <ellipse
        cx="36"
        cy="46.4"
        rx="3.8"
        ry="3.4"
        fill={u('limbG')}
        stroke="#2e2a4f"
        strokeWidth="1.8"
      />
    </svg>
  )
}

/** 마스코트 — 방패를 든 동전이 전신. 가로세로 비율은 19:16이다. */
export function Mascot({ size = 190, className }: { size?: number; className?: string }) {
  const { p, u } = useSvgIds()
  return (
    <svg
      className={className}
      width={size}
      height={Math.round((size * 160) / 190)}
      viewBox="0 0 190 160"
      aria-hidden="true"
      focusable="false"
    >
      <Defs p={p} />
      <ellipse cx="90" cy="150" rx="54" ry="5" fill="#13204a" opacity="0.14" />
      {/* 반짝 */}
      <path
        d="M24 40l2 5 5 2-5 2-2 5-2-5-5-2 5-2z"
        fill="#ffe08a"
        stroke="#2e2a4f"
        strokeWidth="1.4"
        strokeLinejoin="round"
      />
      <path
        d="M150 30l1.4 3.4 3.4 1.4-3.4 1.4-1.4 3.4-1.4-3.4-3.4-1.4 3.4-1.4z"
        fill="#ffe08a"
        stroke="#2e2a4f"
        strokeWidth="1.2"
        strokeLinejoin="round"
      />
      {/* 발 (동글) */}
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
      {/* 뒷팔 (짧게) */}
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
      {/* 옆면 */}
      <path
        d="M133 86c0 28-24 52-55 52"
        fill="none"
        stroke="#eebc4f"
        strokeWidth="7"
        strokeLinecap="round"
      />
      <path
        d="M133 86c0 28-24 52-55 52"
        fill="none"
        stroke="#9c7a33"
        strokeWidth="7"
        strokeDasharray="1.2 2.6"
        opacity="0.45"
      />
      {/* 찹쌀떡 몸: 넓고 아래가 납작 */}
      <path
        d="M78 34c33 0 55 22 55 52 0 30-24 50-55 50s-55-20-55-50c0-30 22-52 55-52z"
        fill={u('bodyG')}
        stroke="#2e2a4f"
        strokeWidth="3"
      />
      <ellipse cx="78" cy="86" rx="45" ry="42" fill="none" stroke="#e3ad45" strokeWidth="1.6" />
      <ellipse
        cx="78"
        cy="86"
        rx="41.5"
        ry="38.5"
        fill="none"
        stroke="#fff6dc"
        strokeWidth="1.4"
        opacity="0.9"
      />
      {/* 이마 ₩ */}
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
      {/* 짧고 동근 눈썹 */}
      <path
        d="M56 75.5l8 3.2M100 75.5l-8 3.2"
        stroke="#2e2a4f"
        strokeWidth="3.6"
        strokeLinecap="round"
      />
      {/* 콩알 눈 */}
      <g className="dw-blink">
        <ellipse cx="62" cy="89" rx="5" ry="6" fill="#2b1d12" />
        <circle cx="63.8" cy="86.8" r="2" fill="#fff" />
        <circle cx="60.6" cy="91.6" r="0.9" fill="#fff" opacity="0.85" />
      </g>
      <g className="dw-blink">
        <ellipse cx="94" cy="89" rx="5" ry="6" fill="#2b1d12" />
        <circle cx="95.8" cy="86.8" r="2" fill="#fff" />
        <circle cx="92.6" cy="91.6" r="0.9" fill="#fff" opacity="0.85" />
      </g>
      {/* 빵빵한 볼 */}
      <ellipse cx="45" cy="104" rx="12.5" ry="8.6" fill="#ffa6a2" opacity="0.9" />
      <ellipse cx="111" cy="104" rx="12.5" ry="8.6" fill="#ffa6a2" opacity="0.9" />
      <path
        d="M39.5 103l3-3M44.5 104.6l3-3M49.5 103l3-3M105.5 103l3-3M110.5 104.6l3-3M115.5 103l3-3"
        stroke="#e8807c"
        strokeWidth="1.5"
        strokeLinecap="round"
      />
      {/* 아주 작은 へ 입 */}
      <path
        d="M74.6 103.6l3.4-2.6 3.4 2.6"
        fill="none"
        stroke="#2e2a4f"
        strokeWidth="2.4"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
      {/* 넥타이 (작게) */}
      <g transform="translate(78 117) scale(.78)">
        <path
          d="M-5 0h10l-2 4.6h-6z"
          fill={u('navyG')}
          stroke="#2e2a4f"
          strokeWidth="1.8"
          strokeLinejoin="round"
        />
        <path
          d="M-3 4.6h6l2.8 11-5.8 5.4-5.8-5.4z"
          fill={u('navyG')}
          stroke="#2e2a4f"
          strokeWidth="1.8"
          strokeLinejoin="round"
        />
        <path
          d="M-1.6 8l4 6.4M-2.8 12.6l3.6 5.6"
          stroke="#e7c46d"
          strokeWidth="1.3"
          strokeLinecap="round"
        />
      </g>
      {/* 땀 */}
      <path
        className="dw-sweat"
        d="M124 44c0 0-5 6-5 9a5 5 0 0 0 10 0c0-3-5-9-5-9z"
        fill="#bfe3ff"
        stroke="#2e2a4f"
        strokeWidth="2"
      />
      {/* 큰 방패 */}
      <g transform="translate(24 8) rotate(-8 130 108)">
        <path
          d="M118 74l10-4.6"
          stroke="#fff"
          strokeWidth="2.4"
          strokeLinecap="round"
          opacity="0.35"
        />
        <path
          d="M130 66l30 10v24c0 22-13 36-30 44-17-8-30-22-30-44V76z"
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
        <circle cx="130" cy="102" r="12" fill={u('goldG')} stroke="#2e2a4f" strokeWidth="2.2" />
        <circle
          cx="130"
          cy="102"
          r="9.4"
          fill="none"
          stroke="#fff3cf"
          strokeWidth="1"
          opacity="0.8"
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
      {/* 방패 잡은 손 + 끙끙 */}
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
        d="M122 116.5h3M122.5 119.5h3"
        stroke="#2e2a4f"
        strokeWidth="1.6"
        strokeLinecap="round"
      />
      <path
        d="M114 128l-4 3M118 132l-2.6 4"
        stroke="#2e2a4f"
        strokeWidth="1.8"
        strokeLinecap="round"
      />
    </svg>
  )
}
