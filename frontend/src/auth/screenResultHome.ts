import { sessionNavigation } from '../config/navigation.ts'
import type { AuthUser } from '../types/auth.ts'

// 볼 수 있는 메뉴가 없는 계정은 /login으로 보내고, 그곳에서 403과 로그아웃을 보여 준다.
export function screenResultHome(user: AuthUser | null, homePath?: string): string {
  return homePath ?? (user ? sessionNavigation(user).landing : null) ?? '/login'
}

// 로그인 화면으로 가는 버튼을 '내 홈'이라고 부르지 않는다.
export function screenResultHomeLabel(path: string): string {
  return path === '/login' ? '로그인 화면으로 이동' : '내 홈으로 이동'
}
