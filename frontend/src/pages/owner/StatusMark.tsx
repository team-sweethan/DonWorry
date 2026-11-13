/**
 * 사장님 화면 상태 아이콘. 관리 화면 공통 상태 태그(global.css)와 같은 뜻의 모양을 쓴다 —
 * 완료 체크(초록) · 진행 반쯤 찬 원(파랑) · 대기 점선 원(노랑) · 주의 삼각형(주황) · 위험 느낌표(빨강) · 비활성 빈 원(회색).
 * 색만으로 구분하지 않도록 모양이 모두 다르다. 뜻은 옆 글자가 전하므로 아이콘은 읽지 않는다.
 */
import type { StatusTone } from './statusTone'

export type { StatusTone }

const colors: Record<StatusTone, string> = {
  success: '#15803d',
  progress: '#2563eb',
  pending: '#b7791f',
  warning: '#c2410c',
  danger: '#b91c1c',
  waiting: '#8f8573',
}

export function StatusMark({ tone, size = 18 }: { tone: StatusTone; size?: number }) {
  const color = colors[tone]
  return (
    <svg
      className="ow-status-mark"
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke={color}
      strokeWidth={2.2}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
    >
      {tone === 'warning' ? (
        <>
          <path d="M12 3.5 21.5 20h-19z" />
          <path d="M12 10v4.5M12 17.5v.01" />
        </>
      ) : tone === 'pending' ? (
        <circle cx="12" cy="12" r="9" strokeDasharray="3.2 3.2" />
      ) : (
        <circle cx="12" cy="12" r="9" />
      )}
      {tone === 'success' && <path d="m8 12.2 2.8 2.8L16.5 9" />}
      {tone === 'progress' && <path d="M12 3a9 9 0 0 1 0 18z" fill={color} stroke="none" />}
      {tone === 'danger' && <path d="M12 7.5V13M12 16.5v.01" />}
    </svg>
  )
}
