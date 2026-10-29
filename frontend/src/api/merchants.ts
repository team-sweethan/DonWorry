import { api } from './client.ts'

export type MerchantDirectoryEntry = {
  merchantId: number
  merchantCode: string
  displayName: string
  status: 'ACTIVE' | 'INACTIVE'
}

export const getMerchantDirectory = () =>
  api<MerchantDirectoryEntry[]>('/merchants', { cache: 'no-store' })
