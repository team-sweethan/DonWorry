import { canOpenPath, sessionNavigation } from '../config/navigation.ts'
import type { AuthUser } from '../types/auth.ts'
import type { IntegrationStatus } from '../api/integration.ts'

export type LoginRedirectState = {
  requireLogin?: boolean
  returnTo: string
  previousUserId: number | null
}

/** Router state도 외부 입력으로 보고, 실제 이동과 권한 검사가 같은 경로를 해석하게 한다. */
export function loginDestination(user: AuthUser, state: unknown): string | null {
  const fallback = sessionNavigation(user).landing
  if (user.passwordChangeRequired) return fallback
  if (typeof state !== 'object' || state === null || Array.isArray(state)) return fallback
  // 관리자는 관리자 홈에서 시작한다. 세션이 만료돼 같은 계정으로 다시 로그인할 때만 보던 화면으로 돌아간다.
  if (user.role === 'ADMIN' && !('previousUserId' in state && state.previousUserId != null))
    return fallback
  if (!('returnTo' in state) || typeof state.returnTo !== 'string') return fallback
  const target = state.returnTo
  const hasControlOrSpace = Array.from(target).some((char) => {
    const code = char.charCodeAt(0)
    return code <= 32 || code === 127
  })
  if (
    !target.startsWith('/') ||
    target.startsWith('//') ||
    target.includes('#') ||
    hasControlOrSpace
  )
    return fallback
  const pathname = target.split('?')[0]!
  // 등록된 경로는 인코딩이 필요 없다. 디코딩·정규화로 다른 메뉴가 되는 경로는 버린다.
  if (/[\\%]/.test(pathname) || pathname.includes('//')) return fallback
  if (pathname.split('/').some((segment) => segment === '.' || segment === '..')) return fallback
  // 엄격 비교라 숫자가 아닌 값·다른 계정 ID는 모두 여기서 버린다.
  if ('previousUserId' in state && state.previousUserId != null) {
    if (state.previousUserId !== user.userId) return fallback
  }
  return canOpenPath(user, pathname) ? target : fallback
}

export function requiresLogin(state: unknown): boolean {
  return (
    typeof state === 'object' &&
    state !== null &&
    'requireLogin' in state &&
    state.requireLogin === true
  )
}

/** 일반 로그인은 홈, 같은 계정의 재로그인만 검증된 업무 화면으로 복귀한다. */
export function ownerLoginDestination(user: AuthUser, state: unknown, status: IntegrationStatus) {
  if (user.passwordChangeRequired) return '/account/password'
  if (status !== 'CONNECTED') return '/owner/integration'
  const sameAccount =
    typeof state === 'object' &&
    state !== null &&
    'previousUserId' in state &&
    state.previousUserId === user.userId
  return sameAccount ? (loginDestination(user, state) ?? '/owner') : '/owner'
}
