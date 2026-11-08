/**
 * 내부자 관제 화면 자동 갱신 (#679 조각 10 D3, #691). 화면이 보일 때만 POLL_MS마다 다시 읽고,
 * 마지막 사용자 조작(화면 진입 · 직원 선택 · 탭 복귀 · 다시 시작 · 판정)부터 POLL_LIMIT_MS가 지나면 멈춘다.
 * 요청마다 화면 · 서버 세션이 연장되므로(docs/identity/166-session-expiry-design.md), 멈추지 않으면
 * 화면을 띄워 둔 채 자리를 비워도 30분 무활동 만료(ADR-0014)가 오지 않는다. 사건 상세(#238)와 같은 방식이다.
 */
export const INSIDER_MONITOR_POLL_MS = 2_000
export const INSIDER_MONITOR_POLL_LIMIT_MS = 5 * 60_000
