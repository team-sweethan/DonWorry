/**
 * Case 상세 자동 조회 (#238). 보고서 생성 중 · 금융 처리 대기 중이면 POLL_MS마다 다시 조회하되,
 * 마지막 사용자 동작(화면 진입 · 새로고침 · 판정 · 다시 확인 · 탭 복귀)부터 POLL_LIMIT_MS가 지나면 멈춘다.
 * 요청마다 화면 · 서버 세션이 연장되므로, 멈추지 않으면 화면을 켜 둔 채 자리를 비워도 30분 무활동 만료(ADR-0014)가 오지 않는다.
 */
export const POLL_MS = 3_000
export const POLL_LIMIT_MS = 2 * 60_000

/**
 * 타이머가 돌 때 이번에 조회할지 정한다. from이 null이면 이번이 첫 조회라 지금부터 센다.
 * 반환한 from을 다음 호출에 그대로 넘긴다.
 */
export function pollStep(from: number | null, now: number) {
  const start = from ?? now
  return { from: start, poll: now - start < POLL_LIMIT_MS }
}
