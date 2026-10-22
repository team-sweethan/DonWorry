import { api, ApiError } from './client.ts'
import type { AuthUser } from '../types/auth.ts'

export class InvalidCredentialsError extends Error {
  constructor() {
    super('아이디 또는 비밀번호가 올바르지 않습니다.')
    this.name = 'InvalidCredentialsError'
  }
}

export async function login(loginId: string, password: string): Promise<AuthUser> {
  try {
    return await api<AuthUser>('/auth/login', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ loginId, password }),
    })
  } catch (error) {
    if (error instanceof ApiError && error.status === 401 && error.code === 'AUTH_004') {
      throw new InvalidCredentialsError()
    }
    throw error
  }
}

export const getMe = () => api<AuthUser>('/auth/me', { cache: 'no-store' })
export const logout = () => api<void>('/auth/logout', { method: 'POST' })
