type Rect = { top: number; bottom: number; left: number; right: number }

// 한 번에 화면의 75%만 이동해 앞뒤 내용이 겹쳐 보이게 한다.
export function boothScrollDelta(
  start: number,
  end: number,
  visibleStart: number,
  visibleEnd: number,
  remaining: number,
) {
  if (end <= visibleEnd + 4 || remaining <= 1) return 0
  if (start >= visibleEnd) return Math.min(remaining, start - visibleStart)
  return Math.max(0, Math.min(remaining, end - visibleEnd + 8, (visibleEnd - visibleStart) * 0.75))
}

function scrollBehavior(): ScrollBehavior {
  return window.matchMedia?.('(prefers-reduced-motion: reduce)').matches ? 'instant' : 'smooth'
}

function clips(value: string) {
  return /auto|scroll|hidden|clip/.test(value)
}

// 중첩된 스크롤 영역과 자막에 가려지는 부분을 모두 제외한 실제 표시 범위.
export function boothVisibleFrame(element: HTMLElement, usableBottom: number): Rect {
  const frame = { top: 8, bottom: usableBottom - 8, left: 8, right: window.innerWidth - 8 }
  for (
    let parent = element.parentElement;
    parent && parent !== document.body;
    parent = parent.parentElement
  ) {
    const style = getComputedStyle(parent)
    const rect = parent.getBoundingClientRect()
    if (clips(style.overflowY)) {
      frame.top = Math.max(frame.top, rect.top + parent.clientTop)
      frame.bottom = Math.min(frame.bottom, rect.top + parent.clientTop + parent.clientHeight)
    }
    if (clips(style.overflowX)) {
      frame.left = Math.max(frame.left, rect.left + parent.clientLeft)
      frame.right = Math.min(frame.right, rect.left + parent.clientLeft + parent.clientWidth)
    }
  }
  return frame
}

function scrollContainers(element: HTMLElement) {
  const containers: HTMLElement[] = []
  const add = (node: HTMLElement) => {
    const style = getComputedStyle(node)
    if (
      (/auto|scroll/.test(style.overflowY) && node.scrollHeight > node.clientHeight + 1) ||
      (/auto|scroll/.test(style.overflowX) && node.scrollWidth > node.clientWidth + 1)
    )
      containers.push(node)
  }
  // 표·타임라인 자체의 가로 스크롤도 끝까지 보여 준다.
  for (const child of element.querySelectorAll<HTMLElement>('*')) {
    if (child.getClientRects().length) add(child)
  }
  for (
    let node: HTMLElement | null = element;
    node && node !== document.body && node !== document.documentElement;
    node = node.parentElement
  )
    add(node)
  const root = document.scrollingElement as HTMLElement | null
  if (root && !/hidden|clip/.test(getComputedStyle(document.body).overflowY)) containers.push(root)
  return containers
}

export function startBoothScroll(element: HTMLElement, usableBottom: number) {
  // 이전 순회에서 남은 내부 표 스크롤 위치도 초기화한다.
  for (const container of scrollContainers(element)) {
    container.scrollTo({
      top: element.contains(container) ? 0 : container.scrollTop,
      left: 0,
      behavior: 'instant',
    })
  }
  element.scrollIntoView({
    block: element.getBoundingClientRect().height > usableBottom * 0.7 ? 'start' : 'center',
    inline: 'nearest',
    behavior: scrollBehavior(),
  })
}

/** 남은 내용이 있을 때만 한 화면씩 이동한다. 제출·페이지네이션은 건드리지 않는다. */
export function advanceBoothScroll(element: HTMLElement, usableBottom: number) {
  for (const container of scrollContainers(element)) {
    const root = container === document.scrollingElement
    const inside = element.contains(container)
    const target = inside ? container : element
    const rect = target.getBoundingClientRect()
    const frame = boothVisibleFrame(target, usableBottom)
    if (frame.bottom <= frame.top || frame.right <= frame.left) continue
    const own = container.getBoundingClientRect()
    const bottom = root
      ? frame.bottom
      : Math.min(frame.bottom, own.top + container.clientTop + container.clientHeight)
    const top = root ? frame.top : Math.max(frame.top, own.top + container.clientTop)
    const remainingY = container.scrollHeight - container.clientHeight - container.scrollTop
    const dy = inside
      ? Math.min(Math.max(0, remainingY), Math.max(0, bottom - top) * 0.75)
      : boothScrollDelta(rect.top, rect.bottom, top, bottom, remainingY)
    const right = root
      ? frame.right
      : Math.min(frame.right, own.left + container.clientLeft + container.clientWidth)
    const left = root ? frame.left : Math.max(frame.left, own.left + container.clientLeft)
    const remainingX = container.scrollWidth - container.clientWidth - container.scrollLeft
    const dx = inside
      ? Math.min(Math.max(0, remainingX), Math.max(0, right - left) * 0.75)
      : boothScrollDelta(rect.left, rect.right, left, right, remainingX)
    if (dy > 1 || dx > 1) {
      container.scrollBy({ top: dy, left: dy > 1 ? 0 : dx, behavior: scrollBehavior() })
      return true
    }
  }
  return false
}
