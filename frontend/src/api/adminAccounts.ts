import { api } from './client.ts'
import type { AuthUser } from '../types/auth.ts'

export type AdminUser = {
  userId: number
  loginId: string
  displayName: string
  role: AuthUser['role']
  status: 'ACTIVE' | 'DISABLED'
  merchantId: number | null
  merchantName: string | null
  permissions: string[]
  passwordChangeRequired: boolean
  createdAt: string
}
export type UserPage = { items: AdminUser[]; page: number; size: number; totalElements: number }
export type TemporaryPassword = { user: AdminUser; temporaryPassword: string }
export type CreateUser = {
  loginId: string
  displayName: string
  role: 'OPERATOR' | 'SECURITY_STAFF'
  merchantId?: number
}
export type MerchantOption = { merchantId: number; displayName: string }
const json = (method: string, body: unknown): RequestInit => ({
  method,
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(body),
})
export function getUsers(
  page: number,
  keyword: string,
  role?: string,
  status?: string,
  passwordChangeRequired?: boolean,
) {
  const query = new URLSearchParams({ page: String(page), size: '20', keyword })
  if (role) query.set('role', role)
  if (status) query.set('status', status)
  if (passwordChangeRequired !== undefined)
    query.set('passwordChangeRequired', String(passwordChangeRequired))
  return api<UserPage>(`/admin/users?${query}`, { cache: 'no-store' })
}
export const getUser = (id: number) => api<AdminUser>(`/admin/users/${id}`, { cache: 'no-store' })
export const createUser = (input: CreateUser) =>
  api<TemporaryPassword>('/admin/users', json('POST', input))
export const setUserPermissions = (id: number, permissions: string[]) =>
  api<void>(`/admin/users/${id}/permissions`, json('PUT', { permissions }))
export const setUserStatus = (id: number, status: AdminUser['status']) =>
  api<void>(`/admin/users/${id}/status`, json('PUT', { status }))
export const resetUserPassword = (id: number) =>
  api<TemporaryPassword>(`/admin/users/${id}/password-reset`, { method: 'POST' })
export const getMerchantOptions = (keyword: string) =>
  api<MerchantOption[]>(`/admin/merchants/options?${new URLSearchParams({ keyword })}`, {
    cache: 'no-store',
  })
