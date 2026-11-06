import assert from 'node:assert/strict'
import { test } from 'node:test'
import { merchantDisplayName } from '../src/utils/merchantDisplayName.ts'

// 계정 접근 이상 상세는 소속 ID와 일치하는 가맹점명을 표시한다.
test('merchantDisplayName_whenMerchantExists_returnsDisplayName', () => {
  const merchants = [
    {
      merchantId: 1,
      merchantCode: 'SWEET',
      displayName: '스위트한 베이커리',
      status: 'ACTIVE' as const,
    },
  ]

  assert.equal(merchantDisplayName(1, merchants), '스위트한 베이커리')
  assert.equal(merchantDisplayName(2, merchants), null)
})
