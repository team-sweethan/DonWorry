import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  adminMerchantListParams,
  parseAdminMerchantListQuery,
} from '../src/utils/adminMerchantListQuery.ts'

// 목록의 적용 조건은 상세 복귀와 브라우저 이동에서 동일하게 복원된다.
test('merchantQuery_whenRoundTrip_preservesFiltersAndPage', () => {
  const filter = { page: 3, keyword: '가게 & 코드', status: 'INACTIVE' as const }
  assert.deepEqual(parseAdminMerchantListQuery(adminMerchantListParams(filter)), filter)
  assert.equal(adminMerchantListParams({ page: 1, keyword: '' }).toString(), '')
})

// 잘못된 페이지·상태를 API에 보내지 않고 검색어 길이도 제한한다.
test('merchantQuery_whenInvalid_usesDefaults', () => {
  for (const page of ['0', '-1', '1.5', 'abc', 'Infinity', '2147483648']) {
    const parsed = parseAdminMerchantListQuery(
      new URLSearchParams({ page, status: 'UNKNOWN', keyword: 'a'.repeat(101) }),
    )
    assert.equal(parsed.page, 1)
    assert.equal(parsed.status, undefined)
    assert.equal(parsed.keyword.length, 100)
  }
})
