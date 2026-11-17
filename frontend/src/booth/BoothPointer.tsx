import { useState } from 'react'
import { boothPointerPosition, type BoothBox } from './boothSpotlight'

/** 실제 마우스와 별개인 안내 표시. 다음 대상까지 이동하며 클릭은 그대로 통과시킨다. */
export function BoothPointer({ box, cueKey }: { box: BoothBox | null; cueKey: string }) {
  const next = box ? boothPointerPosition(box) : null
  const [lastPoint, setLastPoint] = useState(next)
  if (
    next &&
    (next.left !== lastPoint?.left ||
      next.top !== lastPoint?.top ||
      next.scale !== lastPoint?.scale)
  )
    setLastPoint(next)
  const point = next ?? lastPoint
  // 페이지 로딩 중에는 숨기되 직전 위치를 유지해 다음 대상까지 이어서 움직인다.
  if (!point) return null
  return (
    <div
      className="dw-booth-pointer"
      aria-hidden="true"
      style={{
        transform: `translate3d(${point.left}px, ${point.top}px, 0) scale(${point.scale})`,
        opacity: box ? 1 : 0,
      }}
    >
      {box && <span key={cueKey} className="dw-booth-pointer-ring" />}
      <svg width="30" height="36" viewBox="0 0 30 36" fill="none">
        <path
          d="M2 2L25 21L15 22L20 32L14 35L9 24L2 31Z"
          fill="#F59E0B"
          stroke="#FFFFFF"
          strokeWidth="2.5"
          strokeLinejoin="round"
        />
      </svg>
    </div>
  )
}
