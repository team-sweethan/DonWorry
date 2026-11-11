import type { AdminUser } from '../api/adminAccounts.ts'
import { parseListKeyword, parseListPage } from './listQuery.ts'

export type AdminUserListQuery = {
  page: number
  keyword: string
  role?: AdminUser['role']
  status?: AdminUser['status']
  passwordChangeRequired?: true
}

/** 주소에 상태가 없으면 사용 중 계정만 보인다. 전체 상태는 주소에 ALL로 남겨 새로고침 때 기본값으로 돌아가지 않게 한다. */
const DEFAULT_STATUS = 'ACTIVE'
const ALL_STATUS = 'ALL'

/** 화면·새로고침·뒤로가기가 같은 조건을 쓰도록 적용된 필터는 URL에서 읽는다. */
export function parseAdminUserListQuery(params: URLSearchParams): AdminUserListQuery {
  const role = params.get('role')
  const status = normalizeAdminUserListStatusQuery(params).get('status')
  return {
    page: parseListPage(params.get('page')),
    keyword: parseListKeyword(params.get('keyword')),
    role: role === 'ADMIN' || role === 'OPERATOR' || role === 'SECURITY_STAFF' ? role : undefined,
    // 없거나 알 수 없는 값은 기본(사용 중), ALL은 전체 상태
    status:
      status === 'ACTIVE' || status === 'INACTIVE' || status === 'SUSPENDED'
        ? status
        : status === ALL_STATUS
          ? undefined
          : DEFAULT_STATUS,
    passwordChangeRequired: params.get('passwordChangeRequired') === 'true' ? true : undefined,
  }
}

/** 페이지를 뺀 검색 조건이 있으면 빈 목록을 "결과 없음"으로 안내한다. */
export function isAdminUserListFiltered(query: AdminUserListQuery) {
  return Boolean(query.keyword || query.role || query.status || query.passwordChangeRequired)
}

/** 기본 상태(사용 중)와 첫 페이지는 생략한다. 해제한 필터가 새로고침 때 다시 적용되지 않게 전체 상태는 ALL로 남긴다. */
export function adminUserListParams(query: AdminUserListQuery): URLSearchParams {
  const params = new URLSearchParams()
  if (query.keyword) params.set('keyword', query.keyword)
  if (query.role) params.set('role', query.role)
  if (!query.status) params.set('status', ALL_STATUS)
  else if (query.status !== DEFAULT_STATUS) params.set('status', query.status)
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
