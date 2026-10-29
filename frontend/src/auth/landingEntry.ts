import type { AuthState } from './authStore.ts'
import { sessionNavigation } from '../config/navigation.ts'

type LandingEntry =
  | { kind: 'checking' | 'public' | 'unavailable' | 'forbidden' | 'noPermissions' }
  | { kind: 'redirect'; destination: string }

export function landingEntry(
  state: Pick<AuthState, 'checkedKey' | 'error' | 'status' | 'user'>,
  key: string,
): LandingEntry {
  if (state.checkedKey !== key) return { kind: 'checking' }
  // 확인 실패 시 메모리에 남은 사용자 정보로 업무 화면에 보내지 않는다.
  if (state.error) return { kind: state.error }
  if (state.status === 'authenticated' && state.user) {
    const destination = sessionNavigation(state.user).landing
    return destination ? { kind: 'redirect', destination } : { kind: 'noPermissions' }
  }
  return { kind: 'public' }
}
