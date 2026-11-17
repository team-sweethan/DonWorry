import { useState } from 'react'
import { Mascot } from '../components/brand/BrandMark'
import { boothPointerPosition, type BoothBox } from './boothSpotlight'

/** 별 지시봉을 든 안내 동전이. 다음 대상까지 이동하며 클릭은 그대로 통과시킨다. */
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
      {box && (
        <div key={cueKey} className="dw-booth-guide-hop">
          <span className="dw-booth-pointer-ring" />
          <Mascot size={96} pose="guide" className="dw-booth-guide-character" />
        </div>
      )}
    </div>
  )
}
