/**
 * 접근 이상 배지는 관리자 화면이 보일 때 30초마다 다시 센다. 화면 진입 · 메뉴 이동 · 탭 복귀부터
 * 5분이 지나면 멈춰 백그라운드 조회가 30분 비활동 만료를 무기한 연장하지 않게 한다.
 */
export const OPERATOR_ACCESS_BADGE_POLL_MS = 30_000
export const OPERATOR_ACCESS_BADGE_POLL_LIMIT_MS = 5 * 60_000
