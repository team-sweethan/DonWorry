import assert from 'node:assert/strict'
import { test } from 'node:test'
import {
  adminUserListParams,
  isAdminUserListFiltered,
  parseAdminUserListQuery,
  normalizeAdminUserListStatusQuery,
} from '../src/utils/adminUserListQuery.ts'

const parse = (query: string) => parseAdminUserListQuery(new URLSearchParams(query))

// 목록 기본은 사용 중 계정만 — 처음 연 목록에 정지 · 중지 계정이 섞이지 않게 (#659)
test('parseAdminUserListQuery_noStatus_defaultsToActive', () => {
  for (const query of ['', 'keyword=kim&role=OPERATOR', 'status=UNKNOWN']) {
    assert.equal(parse(query).status, 'ACTIVE')
  }
  assert.equal(adminUserListParams(parse('')).toString(), '')
})

// "전체 상태"로 바꾼 뒤 새로고침 · 뒤로가기를 해도 사용 중으로 돌아가 정지 계정이 사라진 것처럼 보이지 않게 (#659)
test('adminUserListParams_clearedStatus_staysAllAfterReload', () => {
  const initial = parse('status=ACTIVE&passwordChangeRequired=true')
  assert.equal(initial.status, 'ACTIVE')
  assert.equal(initial.passwordChangeRequired, true)
  const cleared = adminUserListParams({
    ...initial,
    status: undefined,
    passwordChangeRequired: undefined,
  })
  assert.equal(cleared.toString(), 'status=ALL')
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

test('페이지 번호만 있으면 검색 조건이 없는 목록으로 본다', () => {
  assert.equal(isAdminUserListFiltered({ page: 3, keyword: '' }), false)
  assert.equal(isAdminUserListFiltered({ page: 1, keyword: 'cake' }), true)
  assert.equal(isAdminUserListFiltered({ page: 1, keyword: '', role: 'OPERATOR' }), true)
  assert.equal(isAdminUserListFiltered({ page: 1, keyword: '', status: 'INACTIVE' }), true)
  assert.equal(
    isAdminUserListFiltered({ page: 1, keyword: '', passwordChangeRequired: true }),
    true,
  )
})

test('보안 정지(SUSPENDED) 필터는 주소에 저장했다가 다시 읽어도 유지된다', () => {
  const params = adminUserListParams({ page: 1, keyword: '', status: 'SUSPENDED' })
  assert.equal(params.toString(), 'status=SUSPENDED')
  assert.equal(parseAdminUserListQuery(params).status, 'SUSPENDED')
})
