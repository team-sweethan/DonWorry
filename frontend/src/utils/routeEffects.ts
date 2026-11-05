export function shouldResetScroll(
  previousPath: string | null,
  pathname: string,
  navigationType: 'POP' | 'PUSH' | 'REPLACE',
): boolean {
  return previousPath !== null && previousPath !== pathname && navigationType !== 'POP'
}

export function showRouteErrorHome(pathname: string): boolean {
  return !/^\/checkout\/[^/]+\/?$/.test(pathname)
}
