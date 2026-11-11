import type { AuthState } from './authStore.ts'

export type SessionBody = 'checking' | 'unavailable' | 'forbidden' | null

/**
 * 관리 화면 본문 대신 보여 줄 상태. null이면 본문을 그린다.
 * 이미 확인된 사용자가 메뉴를 옮길 때의 재확인은 본문을 가리지 않는다 — 매번 확인 카드로 바뀌어 번쩍였다 (#803).
 * 재확인 자체는 그대로 하고, 결과가 로그아웃 · 만료 · 403 · 서버 오류면 그때 처리한다.
 * 그 사이 새 화면의 API 요청은 서버가 막는다 (ADR-0059). 401이면 화면 중간 만료와 같은 경로로 로그인으로 보낸다.
 */
export function sessionBody(
  state: Pick<AuthState, 'status' | 'user' | 'error'>,
  checked: boolean,
): SessionBody {
  const rechecking = !checked && state.status === 'authenticated' && state.user !== null
  if (!checked && !rechecking) return 'checking'
  return state.error
}
