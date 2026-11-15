type MerchantChoice = { merchantId: number; status: 'ACTIVE' | 'INACTIVE' }

/**
 * 경보 목록을 처음 열 때 보여 줄 가맹점을 고른다 (#823).
 * 운영 중(ACTIVE)인 가맹점 중 확인할 경보가 가장 많은 곳을 고르고, 같으면 목록 순서(가맹점 번호 순)를 따른다.
 * 비활성 가맹점은 관리 대상이 아니라 고르지 않는다. 건수를 못 받은 가맹점은 비교에서 뺀다.
 * 확인할 경보가 있는 곳이 없으면 첫 운영 중 가맹점, 그것도 없으면 첫 가맹점을 고른다.
 */
export function pickDefaultMerchant<T extends MerchantChoice>(
  merchants: readonly T[],
  attentionByMerchant: ReadonlyMap<number, number>,
): T | undefined {
  const active = merchants.filter((merchant) => merchant.status === 'ACTIVE')
  let busiest: T | undefined
  let busiestCount = 0
  for (const merchant of active) {
    const count = attentionByMerchant.get(merchant.merchantId) ?? 0
    if (count > busiestCount) {
      busiest = merchant
      busiestCount = count
    }
  }
  return busiest ?? active[0] ?? merchants[0]
}
