import type { MerchantDirectoryEntry } from '../api/merchants.ts'

export function merchantDisplayName(
  merchantId: number,
  merchants: MerchantDirectoryEntry[],
): string | null {
  return merchants.find((merchant) => merchant.merchantId === merchantId)?.displayName ?? null
}
