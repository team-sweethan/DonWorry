import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  lastListPage,
  parseListKeyword,
  parseListPage,
  sameListCondition,
  withQuery,
  listResultForDisplay,
} from '../src/utils/listQuery.ts'

// 잘못된 페이지를 API에 보내지 않고 검색어 길이도 제한한다.
test('listQuery_whenInvalid_usesDefaults', () => {
  for (const page of [null, '0', '-1', '1.5', 'abc', 'Infinity', '2147483648'])
    assert.equal(parseListPage(page), 1)
  assert.equal(parseListPage('3'), 3)
  assert.equal(parseListKeyword(null), '')
  assert.equal(parseListKeyword('a'.repeat(101)).length, 100)
})

// 다음 페이지를 기다리는 동안 행과 복귀 쿼리는 이전 응답의 같은 스냅샷을 쓴다.
test('listResultForDisplay_whenNextPagePending_preservesRowReturnPage', () => {
  const params = (filter: { page: number; keyword: string }) =>
    new URLSearchParams({ keyword: filter.keyword, page: String(filter.page) })
  const previous = { filter: { page: 2, keyword: '가게' }, data: { items: [7] } }
  const nextFilter = { page: 3, keyword: '가게' }
  const pending = listResultForDisplay(previous, nextFilter, params)
  assert.deepEqual(pending?.data.items, [7])
  assert.equal(
    withQuery('/admin/merchants/7', pending!.query),
    '/admin/merchants/7?keyword=%EA%B0%80%EA%B2%8C&page=2',
  )
  const next = { filter: nextFilter, data: { items: [8] } }
  const loaded = listResultForDisplay(next, nextFilter, params)
  assert.deepEqual(loaded?.data.items, [8])
  assert.equal(new URLSearchParams(loaded!.query).get('page'), '3')
  assert.equal(listResultForDisplay(previous, { page: 1, keyword: '다른 가게' }, params), null)
  assert.equal(listResultForDisplay(null, nextFilter, params), null)
})

// 조건이 없으면 물음표 없는 주소로 돌아간다.
test('withQuery_whenQueryIsEmpty_keepsPath', () => {
  assert.equal(withQuery('/admin/users', ''), '/admin/users')
  assert.equal(withQuery('/admin/users/new', 'status=ACTIVE'), '/admin/users/new?status=ACTIVE')
})

// 마지막 행이 사라져 페이지가 줄어도 빈 목록에 머물지 않게 한다.
test('lastListPage_whenTotalChanges_returnsExistingPage', () => {
  assert.equal(lastListPage(0), 1)
  assert.equal(lastListPage(20), 1)
  assert.equal(lastListPage(21), 2)
})

// 페이지 이동은 같은 조건이고 필터가 바뀌면 다른 조건이다.
test('sameListCondition_comparesEverythingExceptPage', () => {
  const list = (query: string) => new URLSearchParams(query)
  assert.equal(sameListCondition(list('status=ACTIVE'), list('status=ACTIVE&page=2')), true)
  assert.equal(sameListCondition(list('status=ACTIVE'), list('status=INACTIVE')), false)
  assert.equal(sameListCondition(list('keyword=a&page=2'), list('page=2')), false)
})
