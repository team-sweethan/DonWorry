import assert from 'node:assert/strict'
import { test } from 'node:test'
import { pickDefaultMerchant } from '../src/utils/alertDefaultMerchant.ts'

const merchant = (merchantId: number, status: 'ACTIVE' | 'INACTIVE' = 'ACTIVE') => ({
  merchantId,
  status,
})

// 경보 목록을 처음 열면 확인할 경보가 가장 많은 운영 중 가맹점을 연다 (#823).
test('pickDefaultMerchant_activeMerchantHasMostAttention_picksThatMerchant', () => {
  const merchants = [merchant(1), merchant(2), merchant(3)]
  const attention = new Map([
    [1, 0],
    [2, 7],
    [3, 3],
  ])

  assert.equal(pickDefaultMerchant(merchants, attention)?.merchantId, 2)
  // 건수가 같으면 목록 순서(가맹점 번호 오름차순)가 앞인 곳
  assert.equal(
    pickDefaultMerchant(
      merchants,
      new Map([
        [2, 5],
        [3, 5],
      ]),
    )?.merchantId,
    2,
  )
})

// 비활성 가맹점은 관리 대상이 아니므로 경보가 더 많아도 고르지 않는다.
test('pickDefaultMerchant_inactiveMerchantHasMostAttention_skipsInactive', () => {
  const merchants = [merchant(1, 'INACTIVE'), merchant(2), merchant(3)]
  const attention = new Map([
    [1, 20],
    [2, 0],
    [3, 4],
  ])

  assert.equal(pickDefaultMerchant(merchants, attention)?.merchantId, 3)
})

// 건수가 모두 0이거나 받지 못했으면 지금처럼 첫 운영 중 가맹점을 연다.
test('pickDefaultMerchant_allZeroOrCountsMissing_fallsBackToFirstActive', () => {
  const merchants = [merchant(1, 'INACTIVE'), merchant(2), merchant(3)]

  assert.equal(
    pickDefaultMerchant(
      merchants,
      new Map([
        [2, 0],
        [3, 0],
      ]),
    )?.merchantId,
    2,
  )
  assert.equal(pickDefaultMerchant(merchants, new Map())?.merchantId, 2)
  // 운영 중 가맹점이 없으면 목록 첫 가맹점, 목록이 비면 없음
  assert.equal(pickDefaultMerchant([merchant(5, 'INACTIVE')], new Map())?.merchantId, 5)
  assert.equal(pickDefaultMerchant([], new Map()), undefined)
})
