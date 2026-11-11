import type { AuthState } from './authStore.ts'

export type SessionBody = 'checking' | 'rechecking' | 'unavailable' | 'forbidden' | null

/**
 * 관리 화면 본문 대신 보여 줄 상태. null이면 본문을 그린다.
 *
 * 메뉴를 옮길 때마다 서버에 로그인 상태를 다시 묻고, 확인이 끝난 뒤에 화면을 그린다 (기존 순서 유지).
 * 확인 전에 화면을 그리면 권한이 막 회수된 사장님의 업무 API가 403을 받아 #515 반복 탐지에 오탐이 쌓인다.
 *
 * 'rechecking'은 이미 확인된 사용자의 메뉴 이동 재확인이다. 확인 카드를 그대로 가리되 빠른 응답에는
 * 보이지 않게 해서 화면이 번쩍이지 않게 한다 (#803). 직전 확인이 오류였다면 지금처럼 'checking'으로 둔다.
 */
export function sessionBody(
  state: Pick<AuthState, 'status' | 'user' | 'error'>,
  checked: boolean,
): SessionBody {
  if (!checked)
    return state.status === 'authenticated' && state.user !== null && state.error === null
      ? 'rechecking'
      : 'checking'
  return state.error
}
