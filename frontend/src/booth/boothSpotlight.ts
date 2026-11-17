export type BoothBox = { top: number; left: number; width: number; height: number }

// 안내 동전이는 가능하면 박스 위 모서리에 앉혀 설명·숫자를 덜 가린다.
// 위 공간이 부족하면 박스 안으로 옮기고, 작은 대상에서는 몸 크기도 줄인다.
export function boothPointerPosition(box: BoothBox) {
  const widthScale = Math.min(1, box.width / 96)
  const above = box.top >= 84 * widthScale + 12
  const scale = above ? widthScale : Math.min(widthScale, box.height / 84)
  return {
    left: box.left + Math.max(0, box.width - 104 * scale),
    top: above
      ? box.top - 80 * scale
      : box.top + Math.max(0, Math.min(8 * scale, box.height - 84 * scale)),
    scale,
  }
}

// 강조 대상 자체가 details인 키 관리 기록도 자식 요소와 같은 방식으로 펼친다.
export function revealBoothDetails(element: HTMLElement) {
  for (let current: HTMLElement | null = element; current; current = current.parentElement) {
    if (current.tagName === 'DETAILS') (current as HTMLDetailsElement).open = true
  }
}

// 줄바꿈·창 크기·글꼴 로딩에 따라 자막 높이가 달라지므로 실제 윗변까지만 사용한다.
export function boothViewportBottom(viewportHeight: number, bar: Pick<DOMRect, 'top'> | null) {
  return Math.max(0, Math.min(viewportHeight, bar?.top ?? viewportHeight))
}

export function clipBoothBox(
  rect: Pick<DOMRect, 'top' | 'bottom' | 'left' | 'right'>,
  viewportWidth: number,
  usableBottom: number,
  pad = 6,
): BoothBox | null {
  const top = Math.max(Math.round(rect.top) - pad, 4)
  const bottom = Math.min(Math.round(rect.bottom) + pad, usableBottom - 4)
  const left = Math.max(Math.round(rect.left) - pad, 4)
  const right = Math.min(Math.round(rect.right) + pad, viewportWidth - 4)
  return bottom - top > 24 && right - left > 24
    ? { top, left, width: right - left, height: bottom - top }
    : null
}

// ref가 붙는 즉시 여백을 맞추고, 자막이 커지거나 작아져도 본문 마지막 줄을 가리지 않는다.
export function observeBoothBar(
  bar: HTMLElement,
  body: HTMLElement,
  createObserver: (onResize: () => void) => Pick<ResizeObserver, 'observe' | 'disconnect'> = (
    onResize,
  ) => new ResizeObserver(onResize),
) {
  const measure = () => {
    body.style.setProperty(
      '--dw-booth-height',
      `${Math.ceil(bar.getBoundingClientRect().height)}px`,
    )
  }
  measure()
  body.classList.add('dw-booth-open')
  const observer = createObserver(measure)
  observer.observe(bar)
  return () => {
    observer.disconnect()
    body.classList.remove('dw-booth-open')
    body.style.removeProperty('--dw-booth-height')
  }
}
