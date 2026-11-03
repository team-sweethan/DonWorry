import type { AuthUser } from '../types/auth.ts'

export type LoginValues = { loginId: string; password: string; rememberLoginId: boolean }
type LoginStorage = Pick<Storage, 'getItem' | 'setItem' | 'removeItem'>
type StorageProvider = () => LoginStorage

export const REMEMBERED_LOGIN_ID_KEY = 'donworry.auth.rememberedLoginId'
const browserStorage: StorageProvider = () => localStorage

export function readLoginPreferences(storage: StorageProvider = browserStorage) {
  try {
    const loginId = storage().getItem(REMEMBERED_LOGIN_ID_KEY)?.trim() ?? ''
    return { loginId, rememberLoginId: loginId.length > 0 }
  } catch {
    return { loginId: '', rememberLoginId: false }
  }
}

export async function loginWithPreferences(
  values: LoginValues,
  login: (loginId: string, password: string) => Promise<AuthUser | null>,
  onSuccess: (user: AuthUser) => void,
  storage: StorageProvider = browserStorage,
) {
  const { rememberLoginId, password } = values
  const loginId = values.loginId.trim()
  const user = await login(loginId, password)
  if (!user) return
  try {
    if (rememberLoginId) storage().setItem(REMEMBERED_LOGIN_ID_KEY, loginId)
    else storage().removeItem(REMEMBERED_LOGIN_ID_KEY)
  } catch {
    // 브라우저 저장 설정은 로그인 성공과 화면 이동에 영향을 주지 않는다.
  }
  onSuccess(user)
}
