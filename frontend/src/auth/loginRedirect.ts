import { canOpenPath, sessionNavigation } from '../config/navigation.ts'
import type { AuthUser } from '../types/auth.ts'

export type LoginRedirectState = {
  returnTo: string
  previousUserId: number | null
}

/** Router state도 외부 입력으로 보고, 실제 이동과 권한 검사가 같은 경로를 해석하게 한다. */
export function loginDestination(user: AuthUser, state: unknown): string | null {
  const fallback = sessionNavigation(user).landing
  if (user.passwordChangeRequired) return fallback
  if (typeof state !== 'object' || state === null || Array.isArray(state)) return fallback
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
