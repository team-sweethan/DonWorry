// 정산 · 자금 현황 화면 코드 받기. 화면(lazy)과 미리 받기가 같은 import를 써서 한 번만 받는다 (#803).
export const loadFinanceConnected = () => import('./FinanceConnected')

/** 사장님 화면에 들어오면 정산 화면 코드를 미리 받아 둔다. 실패해도 메뉴를 열 때 다시 받는다. */
export function preloadFinanceScreens() {
  loadFinanceConnected().catch(() => undefined)
}
