import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  adminUserListParams,
  parseAdminUserListQuery,
  normalizeAdminUserListStatusQuery,
} from '../src/utils/adminUserListQuery.ts'

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
    status: 'INACTIVE' as const,
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

test('이전 DISABLED 주소도 첫 조회부터 정지 필터를 유지한다', () => {
  const query = parse(
    'status=DISABLED&page=3&keyword=이름&role=OPERATOR&passwordChangeRequired=true',
  )
  assert.deepEqual(query, {
    status: 'INACTIVE',
    page: 3,
    keyword: '이름',
    role: 'OPERATOR',
    passwordChangeRequired: true,
  })
  assert.equal(adminUserListParams(query).get('status'), 'INACTIVE')
})

// 주소 정규화는 필터 밖의 파라미터도 보존하고 반복 실행해도 결과가 같다.
test('adminUserStatus_whenLegacyUrl_normalizesOnlyStatusAndPreservesOriginal', () => {
  const previous = new URLSearchParams(
    'status=DISABLED&page=3&keyword=이름&role=OPERATOR&passwordChangeRequired=true&extra=keep',
  )
  const normalized = normalizeAdminUserListStatusQuery(previous)
  assert.equal(normalized.get('status'), 'INACTIVE')
  assert.equal(previous.get('status'), 'DISABLED')
  for (const [key, value] of previous) {
    if (key !== 'status') assert.equal(normalized.get(key), value)
  }
  assert.deepEqual(parseAdminUserListQuery(normalized), parseAdminUserListQuery(previous))
  assert.equal(normalizeAdminUserListStatusQuery(normalized).toString(), normalized.toString())
})

// 정상·미지정·잘못된 상태의 기존 기본 동작은 정규화로 바뀌지 않는다.
test('adminUserStatus_whenNotLegacy_preservesCurrentQuery', () => {
  for (const status of ['ACTIVE', 'INACTIVE', 'UNKNOWN', '']) {
    const query = new URLSearchParams({ status })
    assert.equal(normalizeAdminUserListStatusQuery(query).toString(), query.toString())
  }
  assert.equal(normalizeAdminUserListStatusQuery(new URLSearchParams()).toString(), '')
})
