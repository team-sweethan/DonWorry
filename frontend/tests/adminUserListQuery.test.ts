import assert from 'node:assert/strict'
import { test } from 'node:test'
import { adminUserListParams, parseAdminUserListQuery } from '../src/utils/adminUserListQuery.ts'

const parse = (query: string) => parseAdminUserListQuery(new URLSearchParams(query))

test('홈에서 지정한 필터를 해제한 주소는 새로고침해도 전체 조건을 유지한다', () => {
  const initial = parse('status=ACTIVE&passwordChangeRequired=true')
  assert.equal(initial.status, 'ACTIVE')
  assert.equal(initial.passwordChangeRequired, true)
  const cleared = adminUserListParams({
    ...initial,
    status: undefined,
    passwordChangeRequired: undefined,
  })
  assert.equal(cleared.toString(), '')
  const reloaded = parseAdminUserListQuery(cleared)
  assert.equal(reloaded.status, undefined)
  assert.equal(reloaded.passwordChangeRequired, undefined)
})

test('검색·역할·상태·비밀번호·페이지를 주소로 복원하고 이전 주소로 돌아가면 이전 조건을 읽는다', () => {
  const previous = parse('status=ACTIVE&passwordChangeRequired=true')
  const changed = {
    ...previous,
    keyword: '이름 & 아이디',
    role: 'OPERATOR' as const,
    status: 'DISABLED' as const,
    page: 3,
  }
  const current = adminUserListParams(changed)
  assert.deepEqual(parseAdminUserListQuery(current), changed)
  assert.deepEqual(parse('status=ACTIVE&passwordChangeRequired=true'), previous)
  assert.notEqual(current.toString(), adminUserListParams(previous).toString())
})

test('알 수 없는 필터와 잘못된 페이지는 전체 조건과 첫 페이지로 처리한다', () => {
  const defaults = parse('')
  for (const page of ['-1', '0', '1.5', 'abc', 'Infinity', '2147483648']) {
    assert.deepEqual(
      parse(`role=UNKNOWN&status=UNKNOWN&passwordChangeRequired=false&page=${page}`),
      defaults,
    )
  }
  assert.equal(parse(`keyword=${'a'.repeat(101)}`).keyword.length, 100)
})
