import { sessionNavigation } from '../config/navigation.ts'
import type { AuthUser } from '../types/auth.ts'

export function screenResultHome(user: AuthUser | null, homePath?: string): string {
  return homePath ?? (user ? sessionNavigation(user).landing : null) ?? '/login'
}
