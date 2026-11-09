// 장식용 심볼이다. 실제 탐지·서버 연결 상태를 나타내지 않는다.
export function HomeSignal() {
  return (
    <svg className="security-home-signal" viewBox="0 0 160 160" fill="none" aria-hidden="true">
      <circle className="security-home-signal-ring" cx="80" cy="80" r="69" />
      <circle className="security-home-signal-ring inner" cx="80" cy="80" r="54" />
      <g className="security-home-signal-orbit">
        <circle cx="80" cy="11" r="4" fill="#7298e9" />
        <circle cx="80" cy="149" r="3" fill="#a7c0ef" />
      </g>
      <circle className="security-home-signal-disc" cx="80" cy="80" r="40" />
      <path
        className="security-home-signal-shield"
        pathLength="1"
        d="M80 54 101 63V79C101 93 91 103 80 108 69 103 59 93 59 79V63L80 54Z"
      />
      <path className="security-home-signal-check" pathLength="1" d="m70 80 7 7 14-15" />
      <circle className="security-home-signal-spark" cx="132" cy="39" r="3" fill="#91b3ed" />
    </svg>
  )
}
