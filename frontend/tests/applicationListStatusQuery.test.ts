import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  applicationListStatus,
  normalizeApplicationListStatusQuery,
} from '../src/utils/applicationListStatusQuery.ts'

// 이전 탭·북마크도 첫 조회와 선택기에 같은 새 상태를 사용하고 나머지 조건은 보존한다.
test('applicationStatus_whenLegacyUrl_preservesEmailUnverifiedFilterAndOtherParams', () => {
  const query = new URLSearchParams('status=EMAIL_PENDING&page=3&keyword=가게&mailAttention=true')
  assert.equal(applicationListStatus(query), 'EMAIL_VERIFICATION_PENDING')
  const normalized = normalizeApplicationListStatusQuery(query)
  assert.equal(normalized.get('status'), 'EMAIL_VERIFICATION_PENDING')
  for (const name of ['page', 'keyword', 'mailAttention']) {
    assert.equal(normalized.get(name), query.get(name))
  }
  assert.equal(query.get('status'), 'EMAIL_PENDING')
  assert.equal(applicationListStatus(normalized), 'EMAIL_VERIFICATION_PENDING')
  assert.equal(normalizeApplicationListStatusQuery(normalized).toString(), normalized.toString())
})

// 유효한 명시 필터와 필터 없는 기본 조회는 주소를 불필요하게 바꾸지 않는다.
test('applicationStatus_whenValidOrMissing_preservesSelectionAndDefault', () => {
  for (const status of [
    'ALL',
    'EMAIL_VERIFICATION_PENDING',
    'REVIEW_PENDING',
    'APPROVED',
    'REJECTED',
  ]) {
    const query = new URLSearchParams({ status })
    assert.equal(applicationListStatus(query), status)
    assert.equal(normalizeApplicationListStatusQuery(query).toString(), query.toString())
  }
  for (const [url, expected] of [
    ['', 'REVIEW_PENDING'],
    ['mailAttention=true', 'ALL'],
  ]) {
    const query = new URLSearchParams(url)
    assert.equal(applicationListStatus(query), expected)
    assert.equal(normalizeApplicationListStatusQuery(query).toString(), url)
  }
})

// 지원하지 않는 값도 선택기와 실제 조회가 같은 기본 상태로 정규화한다.
test('applicationStatus_whenUnknown_usesValidDefaultWithMailAttention', () => {
  for (const status of ['UNKNOWN', '', 'toString', '__proto__']) {
    for (const mailAttention of [false, true]) {
      const query = new URLSearchParams({ status, mailAttention: String(mailAttention) })
      const expected = mailAttention ? 'ALL' : 'REVIEW_PENDING'
      assert.equal(applicationListStatus(query), expected)
      assert.equal(normalizeApplicationListStatusQuery(query).get('status'), expected)
    }
  }
})
