import { api } from './client.ts'

export type LoginHistory = {
  historyId: number
  eventType: 'LOGIN_SUCCESS' | 'LOGIN_FAILURE' | 'LOGOUT'
  occurredAt: string
  clientIp: string | null
  userAgent: string | null
  logoutScope: 'CURRENT' | 'ALL' | null
}
export type LoginHistoryPage = {
  items: LoginHistory[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}
export const getLoginHistories = (page: number, userId?: number, signal?: AbortSignal) => {
  const path =
    userId === undefined ? '/auth/login-histories' : `/admin/users/${userId}/login-histories`
  return api<LoginHistoryPage>(
    `${path}?${new URLSearchParams({ page: String(page), size: '20' })}`,
    {
      cache: 'no-store',
      signal,
    },
  )
}
export const getLatestLogin = (signal?: AbortSignal) =>
  api<{ latestSuccess: LoginHistory | null }>('/auth/login-histories/latest-success', {
    cache: 'no-store',
    signal,
  })
