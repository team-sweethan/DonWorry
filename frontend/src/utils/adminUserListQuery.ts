import type { AdminUser } from '../api/adminAccounts.ts'
import { parseListKeyword, parseListPage } from './listQuery.ts'

export type AdminUserListQuery = {
  page: number
  keyword: string
  role?: AdminUser['role']
  status?: AdminUser['status']
  passwordChangeRequired?: true
}

/** 화면·새로고침·뒤로가기가 같은 조건을 쓰도록 적용된 필터는 URL에서 읽는다. */
export function parseAdminUserListQuery(params: URLSearchParams): AdminUserListQuery {
  const role = params.get('role')
  const status = normalizeAdminUserListStatusQuery(params).get('status')
  return {
    page: parseListPage(params.get('page')),
    keyword: parseListKeyword(params.get('keyword')),
    role: role === 'ADMIN' || role === 'OPERATOR' || role === 'SECURITY_STAFF' ? role : undefined,
    status:
      status === 'ACTIVE' || status === 'INACTIVE' || status === 'SUSPENDED' ? status : undefined,
    passwordChangeRequired: params.get('passwordChangeRequired') === 'true' ? true : undefined,
  }
}

/**
 * [계정 만들기] 역할 기본값. 보안 직원 계정 메뉴(역할 필터 SECURITY_STAFF)에서 들어오면 보안 직원,
 * 그 밖에는 사장님 — 역할을 그대로 둔 채 사장님 계정을 만드는 실수를 줄인다 (ADR-0082 8절).
 */
export function adminCreateUserDefaultRole(query: AdminUserListQuery) {
  return query.role === 'SECURITY_STAFF' ? 'SECURITY_STAFF' : 'OPERATOR'
}

/** 페이지를 뺀 검색 조건이 있으면 빈 목록을 "결과 없음"으로 안내한다. */
export function isAdminUserListFiltered(query: AdminUserListQuery) {
  return Boolean(query.keyword || query.role || query.status || query.passwordChangeRequired)
}

/** 전체 조건과 첫 페이지는 생략한다. 해제한 필터가 새로고침 때 다시 적용되지 않게 한다. */
export function adminUserListParams(query: AdminUserListQuery): URLSearchParams {
  const params = new URLSearchParams()
  if (query.keyword) params.set('keyword', query.keyword)
  if (query.role) params.set('role', query.role)
  if (query.status) params.set('status', query.status)
  if (query.passwordChangeRequired) params.set('passwordChangeRequired', 'true')
  if (query.page > 1) params.set('page', String(query.page))
  return params
}

/** 이전 북마크에서도 정지 필터를 유지하고 다른 검색 조건은 바꾸지 않는다. */
export function normalizeAdminUserListStatusQuery(params: URLSearchParams): URLSearchParams {
  const normalized = new URLSearchParams(params)
  if (normalized.get('status') === 'DISABLED') normalized.set('status', 'INACTIVE')
  return normalized
}
