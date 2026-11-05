export function shouldResetScroll(
  previousPath: string | null,
  pathname: string,
  navigationType: 'POP' | 'PUSH' | 'REPLACE',
): boolean {
  return previousPath !== null && previousPath !== pathname && navigationType !== 'POP'
}

export function routeTitle(handle: unknown): string | null {
  return typeof handle === 'object' &&
    handle !== null &&
    'title' in handle &&
    typeof handle.title === 'string'
    ? handle.title
    : null
}

/** 경로 판별은 라우터에 맡기고, 결제창처럼 홈 이동을 숨길 화면은 라우트 handle에 표시한다. */
export function showRouteErrorHome(handle: unknown): boolean {
  return !(
    typeof handle === 'object' &&
    handle !== null &&
    'hideErrorHome' in handle &&
    handle.hideErrorHome === true
  )
}
