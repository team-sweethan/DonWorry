import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  lastListPage,
  parseListKeyword,
  parseListPage,
  sameListCondition,
  withQuery,
} from '../src/utils/listQuery.ts'

// 잘못된 페이지를 API에 보내지 않고 검색어 길이도 제한한다.
test('listQuery_whenInvalid_usesDefaults', () => {
  for (const page of [null, '0', '-1', '1.5', 'abc', 'Infinity', '2147483648'])
    assert.equal(parseListPage(page), 1)
  assert.equal(parseListPage('3'), 3)
  assert.equal(parseListKeyword(null), '')
  assert.equal(parseListKeyword('a'.repeat(101)).length, 100)
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
