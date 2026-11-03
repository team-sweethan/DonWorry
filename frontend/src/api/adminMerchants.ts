import { api } from './client.ts'

export type MerchantStatus = 'ACTIVE' | 'INACTIVE'
export type AdminMerchant = {
  merchantId: number
  merchantCode: string
  displayName: string
  status: MerchantStatus
  pgFeeRate: number
  createdAt: string
}
export type MerchantPage = {
  items: AdminMerchant[]
  page: number
  size: number
  totalElements: number
}
export type CreateMerchant = {
  merchantCode: string
  displayName: string
  pgFeeRate: string
}
const json = (method: string, body: unknown): RequestInit => ({
  method,
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify(body),
})
export function getMerchants(page: number, keyword: string, status?: MerchantStatus) {
  const query = new URLSearchParams({ page: String(page), size: '20', keyword })
  if (status) query.set('status', status)
  return api<MerchantPage>(`/admin/merchants?${query}`, { cache: 'no-store' })
}
export const getMerchant = (id: number) =>
  api<AdminMerchant>(`/admin/merchants/${id}`, { cache: 'no-store' })
export const createMerchant = (input: CreateMerchant) =>
  api<AdminMerchant>('/admin/merchants', json('POST', input))
export const setMerchantStatus = (id: number, status: MerchantStatus) =>
  api<void>(`/admin/merchants/${id}/status`, json('PUT', { status }))

// % 입력을 정수 단위로 변환해 부동소수점 반올림 없이 비율 문자열을 만든다.
export function percentToFeeRate(percent: string): string | null {
  const value = percent.trim()
  if (!/^\d{1,2}(\.\d{1,4})?$/.test(value)) return null
  const [integer, fraction = ''] = value.split('.')
  const scaled = Number(integer) * 10000 + Number(fraction.padEnd(4, '0'))
  return `0.${String(scaled).padStart(6, '0')}`
}
export function feeRatePercent(rate: number): string {
  return `${Number((rate * 100).toFixed(4))}%`
}
